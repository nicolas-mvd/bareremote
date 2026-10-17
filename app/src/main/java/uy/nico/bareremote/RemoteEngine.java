package uy.nico.bareremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground connection or one bounded widget command. Nothing scheduled while idle. */
final class RemoteEngine {
    interface Listener {
        void onState(String text, boolean paired);
        void onPairingCodeNeeded();
        void onPaired();
        void onError(String message);
    }
    interface Completion { void done(String error); }
    private static RemoteEngine instance;
    static synchronized RemoteEngine get(Context context) {
        if (instance == null) instance = new RemoteEngine(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final PhoneIdentity identity = new PhoneIdentity();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 8, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), task -> new Thread(task, "BareRemote-network"));
    private final AtomicInteger generation = new AtomicInteger();
    private final Object connectionLock = new Object();
    private volatile Listener listener;
    private volatile boolean foreground;
    private volatile boolean pairingBusy;
    private volatile String host;
    private volatile byte[] pin;
    private volatile String status;
    private volatile String pendingHost;
    private volatile TvProtocol.Pairing pairing;
    private volatile TvProtocol.Remote remote;
    private Runnable pairingExpiry;

    private RemoteEngine(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        host = preferences.getString("host", "");
        try { pin = Base64.decode(preferences.getString("certificate", ""), Base64.NO_WRAP); }
        catch (IllegalArgumentException invalid) { pin = null; }
        status = isPaired() ? "Ready" : "Pair your TV in Setup";
        worker.allowCoreThreadTimeOut(true);
    }

    boolean isPaired() { return !host.isEmpty() && pin != null && pin.length > 0; }
    String host() { return host; }
    String status() { return status; }
    void setListener(Listener value) { listener = value; state(status); }
    void clearListener(Listener value) { if (listener == value) listener = null; }

    void resume() {
        synchronized (connectionLock) { foreground = true; }
        if (pairingBusy) {
            if (pairing != null) notifyCode();
            return;
        }
        if (!isPaired()) { state("Pair your TV in Setup"); return; }
        int token = generation.get();
        execute(() -> {
            if (!current(token) || !foreground || pairingBusy) return;
            state("Connecting…");
            try {
                TvProtocol.Remote connected = connect();
                synchronized (connectionLock) {
                    if (!current(token) || !foreground) { connected.close(); return; }
                    remote = connected;
                }
                state("Connected");
            } catch (Exception failure) {
                if (current(token) && foreground) error(message(failure));
            }
        }, null);
    }

    void pause() {
        synchronized (connectionLock) { foreground = false; closeRemote(); }
        if (pairingBusy) cancelPairing();
        else state(isPaired() ? "Ready" : "Pair your TV in Setup");
    }

    void startPairing(String address) {
        final String clean;
        try { clean = validateAddress(address); }
        catch (IllegalArgumentException invalid) { error(invalid.getMessage()); return; }
        cancelPairing();
        closeRemote();
        int token = generation.incrementAndGet();
        pairingBusy = true;
        pendingHost = clean;
        state("Requesting TV code…");
        execute(() -> {
            try {
                if (!current(token) || !foreground) return;
                TvProtocol.Pairing next = TvProtocol.Pairing.start(clean, 6467, "BareRemote", identity,
                        () -> !current(token) || !foreground);
                main.post(() -> {
                    if (!current(token) || !pairingBusy || !foreground) { next.close(); return; }
                    pairing = next;
                    pairingExpiry = () -> {
                        if (current(token) && pairingBusy) {
                            cancelPairing();
                            error("Pairing expired. Request a new TV code.");
                        }
                    };
                    main.postDelayed(pairingExpiry, 90000);
                    state("Enter the code shown on your TV");
                    notifyCode();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (current(token)) { pairingBusy = false; pendingHost = null; error(message(failure)); }
                });
            }
        }, () -> { pairingBusy = false; error("Remote is busy. Try again."); });
    }

    void finishPairing(String code) {
        if (code == null || !code.trim().matches("(?i)[0-9a-f]{6}")) {
            error("Enter the six letters or numbers shown on the TV.");
            return;
        }
        final TvProtocol.Pairing current = pairing;
        final String address = pendingHost;
        final int token = generation.get();
        if (current == null || address == null) { error("Request a new TV code in Setup."); return; }
        state("Pairing…");
        execute(() -> {
            try {
                byte[] certificate = current.finish(code.trim());
                main.post(() -> {
                    // UI mutations and this final commit run serially, so Forget/Cancel
                    // cannot race a stale pairing into becoming the saved TV.
                    if (!current(token) || !foreground) return;
                    boolean saved = preferences.edit().putString("host", address)
                            .putString("certificate", Base64.encodeToString(certificate, Base64.NO_WRAP)).commit();
                    if (!saved) {
                        cancelPairing();
                        error("Could not save pairing. Request a new code to retry.");
                        return;
                    }
                    host = address;
                    pin = certificate;
                    pairingBusy = false;
                    pairing = null;
                    pendingHost = null;
                    removePairingExpiry();
                    state("Paired");
                    Listener target = listener;
                    if (target != null) target.onPaired();
                    if (foreground) resume();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (current(token)) {
                        pairingBusy = false;
                        pairing = null;
                        pendingHost = null;
                        removePairingExpiry();
                        error(message(failure) + " Request a new code to retry.");
                    }
                });
            } finally { current.close(); }
        }, null);
    }

    void cancelPairing() {
        generation.incrementAndGet();
        TvProtocol.Pairing old = pairing;
        pairing = null;
        pendingHost = null;
        pairingBusy = false;
        if (old != null) old.close();
        removePairingExpiry();
        state(isPaired() ? "Ready" : "Pair your TV in Setup");
    }

    private void removePairingExpiry() {
        if (pairingExpiry != null) { main.removeCallbacks(pairingExpiry); pairingExpiry = null; }
    }

    void forget() {
        cancelPairing();
        closeRemote();
        preferences.edit().remove("host").remove("certificate").apply();
        host = "";
        pin = null;
        state("Pair your TV in Setup");
    }

    void sendKey(int key) { command(key, false, null); }
    void sendLongKey(int key) { command(key, true, null); }
    void sendWidgetKey(int key, Completion completion) { command(key, false, completion); }

    private void command(int key, boolean longPress, Completion completion) {
        if (!allowedKey(key)) { finish(completion, "Unknown control"); return; }
        if (!isPaired()) { finish(completion, "Open BareRemote and pair your TV"); return; }
        if (pairingBusy) { finish(completion, "Finish pairing in the app first"); return; }
        final int token = generation.get();
        final long deadline = SystemClock.elapsedRealtime() + 7000;
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<TvProtocol.Remote> active = new AtomicReference<>();
        final Runnable timeout = () -> {
            if (completed.compareAndSet(false, true)) {
                TvProtocol.Remote blocked = active.get();
                if (blocked != null) blocked.close();
                finish(completion, "TV did not respond. Check Wi-Fi or open Setup.");
            }
        };
        main.postDelayed(timeout, 7000);
        execute(() -> {
            TvProtocol.Remote connection = null;
            String failureMessage = null;
            try {
                if (completed.get() || !current(token)) return;
                connection = connect();
                active.set(connection);
                if (completed.get() || !current(token)) return;
                if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException("Command expired");
                if (longPress) connection.sendLongKey(key); else connection.sendKey(key);
                // The TV may queue input after reading the key. A widget's short
                // connection must survive that dispatch, within the same deadline.
                if (completion != null) connection.awaitCommandDeliveryWindow();
                // There is no key-delivery ACK; a successful write means Sent, not observed TV action.
                synchronized (connectionLock) {
                    if (foreground && current(token)) remote = connection;
                }
            } catch (Exception failure) {
                failureMessage = message(failure);
                closeRemote();
            } finally {
                if (connection != null && (!foreground || !current(token) || connection != remote)) connection.close();
                main.removeCallbacks(timeout);
                if (completed.compareAndSet(false, true)) {
                    if (!current(token)) failureMessage = "Control cancelled";
                    finish(completion, failureMessage);
                }
            }
        }, () -> {
            main.removeCallbacks(timeout);
            if (completed.compareAndSet(false, true)) finish(completion, "Too many taps. Try again.");
        });
    }

    private TvProtocol.Remote connect() throws Exception {
        TvProtocol.Remote existing = remote;
        if (existing != null && existing.isOpen()) return existing;
        return TvProtocol.Remote.connect(host, 6466, pin, identity);
    }
    private void closeRemote() {
        synchronized (connectionLock) {
            TvProtocol.Remote old = remote;
            remote = null;
            if (old != null) old.close();
        }
    }
    private boolean current(int token) { return generation.get() == token; }
    private void execute(Runnable action, Runnable rejected) {
        try { worker.execute(action); }
        catch (RejectedExecutionException busy) {
            if (rejected != null) rejected.run(); else error("Remote is busy. Try again.");
        }
    }
    private void state(String value) {
        status = value;
        main.post(() -> {
            Listener target = listener;
            if (target != null) target.onState(value, isPaired());
            RemoteWidget.updateAll(context);
        });
    }
    private void notifyCode() {
        final int token = generation.get();
        main.post(() -> {
            Listener target = listener;
            if (target != null && current(token) && foreground && pairingBusy && pairing != null) {
                target.onPairingCodeNeeded();
            }
        });
    }
    private void error(String message) {
        status = message;
        main.post(() -> {
            Listener target = listener;
            if (target != null) target.onError(message);
            RemoteWidget.updateAll(context, "Open app · " + message);
        });
    }
    private void finish(Completion completion, String error) {
        main.post(() -> {
            if (completion != null) completion.done(error);
            else if (error != null) error(error);
            else state("Sent");
        });
    }
    static boolean allowedKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_POWER:
                return true;
            default: return false;
        }
    }
    static String validateAddress(String address) {
        String value = address == null ? "" : address.trim();
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Enter the TV's IPv4 address, such as 192.168.1.20.");
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Enter a valid TV IPv4 address.");
            int number = Integer.parseInt(parts[i]);
            if (number > 255 || (i == 0 && (number == 0 || number >= 224))) {
                throw new IllegalArgumentException("Enter a valid TV IPv4 address.");
            }
            if (i > 0) canonical.append('.');
            canonical.append(number);
        }
        return canonical.toString();
    }
    private static String message(Exception failure) {
        if (failure instanceof SocketTimeoutException) return "TV timed out. Check that both devices are on the same Wi-Fi.";
        String detail = failure.getMessage();
        if (detail != null && (detail.contains("code") || detail.contains("Code")
                || detail.contains("pair") || detail.contains("Pair") || detail.contains("identity"))) return detail;
        return "Cannot reach TV. Check Wi-Fi and the TV address, or pair again in Setup.";
    }
}
