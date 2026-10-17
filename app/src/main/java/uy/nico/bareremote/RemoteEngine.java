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

/** Shared connection while the activity or a bounded widget service session owns it. */
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
    private final AtomicInteger widgetGeneration = new AtomicInteger();
    private final AtomicInteger foregroundGeneration = new AtomicInteger();
    private final Object connectionLock = new Object();
    private volatile Listener listener;
    private volatile boolean foreground;
    private volatile boolean widgetSessionActive;
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

    /** Start a fresh session without changing the saved pairing or phone identity. */
    void reconnect() { reconnect(null); }

    void reconnect(Completion completion) {
        if (!isPaired()) { finish(completion, "Pair your TV in Setup first."); return; }
        if (pairingBusy) { finish(completion, "Finish pairing first."); return; }
        final int token;
        synchronized (connectionLock) {
            token = generation.incrementAndGet();
            closeRemote();
        }
        state("Connecting…", token);
        final Operation operation = new Operation(token, completion);
        startTimeout(operation);
        execute(() -> {
            try {
                if (!canRun(operation)) { complete(operation, "Control cancelled"); return; }
                TvProtocol.Remote connection = connect(token);
                if (!canRun(operation)) { complete(operation, "Control cancelled"); return; }
                if (!connection.isOpen()) throw new IOException("TV disconnected");
                state("Connected", token);
                complete(operation, null, false);
            } catch (Exception failure) {
                complete(operation, message(failure));
            }
        }, () -> complete(operation, "Remote is busy. Try again."));
    }

    /** The widget foreground service owns its idle timer, notification and lifetime. */
    void setWidgetSessionActive(boolean active) {
        synchronized (connectionLock) {
            if (widgetSessionActive && !active) widgetGeneration.incrementAndGet();
            widgetSessionActive = active;
            if (!connectionWanted()) closeRemote();
        }
    }

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
            state("Connecting…", token);
            try {
                TvProtocol.Remote connected = connect(token);
                synchronized (connectionLock) {
                    if (!current(token) || !connectionWanted()) { discard(connected); return; }
                }
                state("Connected", token);
            } catch (Exception failure) {
                if (current(token) && foreground) error(message(failure), token);
            }
        }, null);
    }

    void pause() {
        synchronized (connectionLock) {
            if (foreground) foregroundGeneration.incrementAndGet();
            foreground = false;
            if (!connectionWanted()) closeRemote();
        }
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
        final Operation operation = new Operation(generation.get(), completion);
        startTimeout(operation);
        execute(() -> {
            TvProtocol.Remote connection = null;
            boolean writeAttempted = false;
            try {
                if (!canRun(operation)) { complete(operation, "Control cancelled"); return; }
                connection = connect(operation.token);
                synchronized (connectionLock) {
                    if (!canRun(operation)) { complete(operation, "Control cancelled"); return; }
                    long required = TvProtocol.DELIVERY_WINDOW_MS;
                    if (longPress) required += 650;
                    if (operation.deadline - SystemClock.elapsedRealtime() < required) {
                        throw new SocketTimeoutException("Command expired before sending");
                    }
                    // Only this request's current write may be aborted by its watchdog.
                    // A queued request or a completed write never owns the shared socket.
                    operation.writing = connection;
                    writeAttempted = true;
                }
                if (longPress) connection.sendLongKey(key); else connection.sendKey(key);
                synchronized (connectionLock) { operation.writing = null; }
                if (completion == null) {
                    complete(operation, null);
                } else {
                    final TvProtocol.Remote sentConnection = connection;
                    // Leave the serial network worker free for the next key. The service
                    // retains the session while this bounded dispatch allowance elapses.
                    main.postDelayed(() -> {
                        if (!current(operation)) complete(operation, "Control cancelled");
                        else if (!sentConnection.isOpen()) {
                            complete(operation, "TV disconnected after sending. Tap Sync to reconnect.");
                        } else complete(operation, null);
                    }, TvProtocol.DELIVERY_WINDOW_MS);
                }
            } catch (Exception failure) {
                synchronized (connectionLock) {
                    operation.writing = null;
                    if (writeAttempted && connection != null) discard(connection);
                }
                complete(operation, message(failure));
            }
        }, () -> complete(operation, "Too many taps. Try again."));
    }

    private final class Operation {
        final int token;
        final Completion completion;
        final int widgetToken;
        final int foregroundToken;
        final long deadline = SystemClock.elapsedRealtime() + 7000;
        final AtomicBoolean completed = new AtomicBoolean();
        // Guarded by connectionLock. Non-null only while this operation writes.
        TvProtocol.Remote writing;
        Runnable timeout;
        Operation(int token, Completion completion) {
            this.token = token;
            this.completion = completion;
            this.widgetToken = completion == null ? -1 : widgetGeneration.get();
            this.foregroundToken = completion == null ? foregroundGeneration.get() : -1;
        }
    }

    private void startTimeout(Operation operation) {
        operation.timeout = () -> {
            synchronized (connectionLock) {
                if (!operation.completed.compareAndSet(false, true)) return;
                // The sole network worker cannot start another write until this
                // one returns. Even cancelled writes must release that worker.
                if (operation.writing != null) {
                    discard(operation.writing);
                }
                operation.writing = null;
            }
            finish(operation.completion, "TV did not respond. Check Wi-Fi or tap Sync.",
                    operation.token, operation.widgetToken, operation.foregroundToken, true);
        };
        main.postDelayed(operation.timeout, Math.max(0, operation.deadline - SystemClock.elapsedRealtime()));
    }

    private boolean canRun(Operation operation) throws SocketTimeoutException {
        boolean ownerActive = operation.completion == null ? foreground : widgetSessionActive;
        if (operation.completed.get() || !current(operation) || !ownerActive || pairingBusy) return false;
        if (SystemClock.elapsedRealtime() >= operation.deadline) throw new SocketTimeoutException("Command expired");
        return true;
    }

    private void complete(Operation operation, String error) { complete(operation, error, true); }

    private void complete(Operation operation, String error, boolean sentState) {
        if (!operation.completed.compareAndSet(false, true)) return;
        main.removeCallbacks(operation.timeout);
        finish(operation.completion, error, operation.token, operation.widgetToken, operation.foregroundToken, sentState);
    }

    private boolean current(Operation operation) {
        return current(operation.token) && (operation.widgetToken == -1
                || widgetGeneration.get() == operation.widgetToken) && (operation.foregroundToken == -1
                || foregroundGeneration.get() == operation.foregroundToken);
    }

    private boolean connectionWanted() { return foreground || widgetSessionActive; }

    private TvProtocol.Remote connect(int token) throws Exception {
        final String address;
        final byte[] certificate;
        synchronized (connectionLock) {
            if (!current(token) || !connectionWanted() || pairingBusy) throw new IOException("Control cancelled");
            TvProtocol.Remote existing = remote;
            if (existing != null && existing.isOpen()) return existing;
            closeRemote();
            address = host;
            certificate = pin;
        }
        TvProtocol.Remote connected = TvProtocol.Remote.connect(address, 6466, certificate, identity);
        synchronized (connectionLock) {
            if (!current(token) || !connectionWanted() || pairingBusy) {
                connected.close();
                throw new IOException("Control cancelled");
            }
            remote = connected;
            return connected;
        }
    }

    /** Discard only this exact session; an old callback cannot close its replacement. */
    private void discard(TvProtocol.Remote connection) {
        synchronized (connectionLock) {
            if (remote == connection) remote = null;
            connection.close();
        }
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
    private void state(String value) { state(value, generation.get()); }
    private void state(String value, int token) {
        main.post(() -> publishState(value, token));
    }
    private void publishState(String value, int token) {
        if (!current(token)) return;
        status = value;
        Listener target = listener;
        if (target != null) target.onState(value, isPaired());
        RemoteWidget.updateAll(context);
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
    private void error(String message) { error(message, generation.get()); }
    private void error(String message, int token) {
        main.post(() -> publishError(message, token));
    }
    private void publishError(String message, int token) {
        if (!current(token)) return;
        status = message;
        Listener target = listener;
        if (target != null) target.onError(message);
        RemoteWidget.updateAll(context, "Open app · " + message);
    }
    private void finish(Completion completion, String error) {
        finish(completion, error, generation.get(), -1, -1, true);
    }
    private void finish(Completion completion, String error, int token, int widgetToken, int foregroundToken, boolean sentState) {
        main.post(() -> {
            if (completion != null) {
                boolean valid = current(token) && (widgetToken == -1 || widgetGeneration.get() == widgetToken);
                completion.done(valid ? error : "Control cancelled");
            }
            else if (current(token) && (foregroundToken == -1 || foregroundGeneration.get() == foregroundToken)) {
                if (error != null) publishError(error, token);
                else if (sentState) publishState("Sent", token);
            }
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
        return "Cannot reach TV. Check Wi-Fi and the TV address, then tap Sync.";
    }
}
