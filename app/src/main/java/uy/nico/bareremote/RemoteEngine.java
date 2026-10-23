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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Shared connection while the activity or a bounded widget service session owns it. */
final class RemoteEngine {
    static final String PAIRING_UPGRADE_MESSAGE =
            "Pair once after this update to keep both phones connected.";
    interface Listener {
        void onState(String text, boolean paired);
        void onPairingCodeNeeded();
        void onPaired();
        void onError(String message);
        default void onPictureState(String state) {}
        default void onKeyboardState(KeyboardState state) {}
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
    private final ThreadPoolExecutor pictureWorker = new ThreadPoolExecutor(2, 2, 8, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), task -> new Thread(task, "BareRemote-picture"));
    private final Set<PictureOperation> pictureRequests = new HashSet<>();
    private final AtomicInteger pictureRevision = new AtomicInteger();
    private volatile String confirmedPicture = "unknown";
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger widgetGeneration = new AtomicInteger();
    private final AtomicInteger foregroundGeneration = new AtomicInteger();
    private final AtomicInteger keyboardGeneration = new AtomicInteger();
    private final Object connectionLock = new Object();
    private volatile Listener listener;
    private volatile boolean foreground;
    private volatile boolean widgetSessionActive;
    private volatile boolean pairingBusy;
    private volatile String host;
    private volatile byte[] pin;
    private volatile int pairedIdentityVersion;
    private volatile String status;
    private volatile String pendingHost;
    private volatile TvProtocol.Pairing pairing;
    private volatile TvProtocol.Remote remote;
    private volatile AddressReconnect addressReconnect;
    private Runnable pairingExpiry;

    private RemoteEngine(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        host = preferences.getString("host", "");
        try { pin = Base64.decode(preferences.getString("certificate", ""), Base64.NO_WRAP); }
        catch (IllegalArgumentException invalid) { pin = null; }
        pairedIdentityVersion = preferences.getInt("pairing_identity_version", 0);
        status = readyStatus();
        worker.allowCoreThreadTimeOut(true);
        pictureWorker.allowCoreThreadTimeOut(true);
        String cached = preferences.getString("picture_state", "unknown");
        if (pictureScope().equals(preferences.getString("picture_scope", "")) && confirmedState(cached))
            confirmedPicture = cached;
    }

    boolean hasSavedTv() { return !host.isEmpty() && pin != null && pin.length > 0; }
    boolean needsPairingUpgrade() { return hasSavedTv() && pairedIdentityVersion != PhoneIdentity.VERSION; }
    boolean isPaired() { return hasSavedTv() && !needsPairingUpgrade(); }
    String pairingRequiredMessage() {
        return needsPairingUpgrade() ? PAIRING_UPGRADE_MESSAGE : "Pair your TV in Setup first.";
    }
    private String readyStatus() {
        return needsPairingUpgrade() ? PAIRING_UPGRADE_MESSAGE : isPaired() ? "Ready" : "Pair your TV in Setup";
    }
    String host() { return host; }
    String status() { return status; }
    String pictureState() { return confirmedPicture; }
    boolean isReconnectingTo() { return addressReconnect != null; }
    void setListener(Listener value) {
        listener = value; state(status); value.onPictureState(confirmedPicture);
        value.onKeyboardState(keyboardState());
    }
    KeyboardState keyboardState() {
        TvProtocol.Remote connection = remote;
        return foreground && connection != null && connection.isOpen()
                ? connection.keyboardState() : KeyboardState.unavailable();
    }
    private void observeKeyboard(TvProtocol.Remote connection, int token) {
        connection.setKeyboardListener(ignored -> publishKeyboard(connection, token));
    }
    private void publishKeyboard(TvProtocol.Remote source, int token) {
        final int owner = foregroundGeneration.get();
        main.post(() -> {
            if (!current(token) || !foreground || owner != foregroundGeneration.get()
                    || source != remote) return;
            Listener target = listener;
            if (target != null) target.onKeyboardState(keyboardState());
        });
    }
    void clearListener(Listener value) { if (listener == value) listener = null; }

    /** Start a fresh session without changing the saved pairing or phone identity. */
    void reconnect() { reconnect(null); }

    void reconnect(Completion completion) {
        if (!isPaired()) { finish(completion, pairingRequiredMessage()); return; }
        if (pairingBusy) { finish(completion, "Finish pairing first."); return; }
        final int token;
        synchronized (connectionLock) {
            if (isReconnectingTo()) { finish(completion, "Finish reconnecting in Setup first."); return; }
            token = generation.incrementAndGet();
            cancelInactivePictureRequests();
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
                pictureRequest(false, null, operation.widgetToken != -1, true);
            } catch (Exception failure) {
                complete(operation, message(failure));
            }
        }, () -> complete(operation, "Remote is busy. Try again."));
    }

    /** Verify the existing TV identity at a new address before replacing only its saved host. */
    void reconnectTo(String address, Completion completion) {
        final String clean;
        try { clean = validateAddress(address); }
        catch (IllegalArgumentException invalid) { finish(completion, invalid.getMessage()); return; }
        if (!isPaired()) { finish(completion, pairingRequiredMessage()); return; }
        if (pairingBusy) { finish(completion, "Finish pairing first."); return; }
        cancelReconnectTo();
        final AddressReconnect request;
        synchronized (connectionLock) {
            if (!foreground) { finish(completion, "Control cancelled"); return; }
            int token = generation.incrementAndGet();
            cancelInactivePictureRequests();
            closeRemote();
            request = new AddressReconnect(token, clean, pin.clone(), completion);
            addressReconnect = request;
        }
        state("Checking saved TV…", request.token);
        request.timeout = () -> endReconnectTo(request,
                "TV did not respond. Check Wi-Fi and the TV address, then scan again.");
        main.postDelayed(request.timeout, Math.max(0, request.deadline - SystemClock.elapsedRealtime()));
        execute(() -> {
            try {
                synchronized (connectionLock) {
                    if (!canReconnectTo(request)) { endReconnectTo(request, "Control cancelled"); return; }
                }
                // Normal control-port readiness proves both the saved server pin and
                // acceptance of this phone's existing Keystore identity. No code or key is sent.
                TvProtocol.Remote candidate = TvProtocol.Remote.connect(clean, 6466, request.certificate, identity);
                synchronized (connectionLock) {
                    try {
                        if (!canReconnectTo(request)) {
                            candidate.close();
                            endReconnectTo(request, "Control cancelled");
                            return;
                        }
                        request.candidate = candidate;
                    } catch (Exception failure) {
                        candidate.close();
                        throw failure;
                    }
                }
                main.post(() -> acceptReconnectTo(request));
            } catch (Exception failure) {
                endReconnectTo(request, reconnectToError(failure));
            }
        }, () -> endReconnectTo(request, "Remote is busy. Try again."));
    }

    /** Cancel only an address check; it never changes the saved pairing. */
    void cancelReconnectTo() {
        synchronized (connectionLock) {
            AddressReconnect request = addressReconnect;
            if (request == null) return;
            generation.incrementAndGet();
            endReconnectTo(request, "Control cancelled");
        }
        state(readyStatus());
    }

    private final class AddressReconnect {
        final int token, foregroundToken = foregroundGeneration.get();
        final String address;
        final byte[] certificate;
        final Completion completion;
        final long deadline = SystemClock.elapsedRealtime() + 7000;
        final AtomicBoolean completed = new AtomicBoolean();
        TvProtocol.Remote candidate; // Guarded by connectionLock; never used for controls before commit.
        Runnable timeout;
        AddressReconnect(int token, String address, byte[] certificate, Completion completion) {
            this.token = token;
            this.address = address;
            this.certificate = certificate;
            this.completion = completion;
        }
    }

    private boolean canReconnectTo(AddressReconnect request) throws SocketTimeoutException {
        if (request.completed.get() || addressReconnect != request || !current(request.token)
                || !foreground || foregroundGeneration.get() != request.foregroundToken || pairingBusy) return false;
        if (SystemClock.elapsedRealtime() >= request.deadline) throw new SocketTimeoutException("Reconnect expired");
        return true;
    }

    private void acceptReconnectTo(AddressReconnect request) {
        synchronized (connectionLock) {
            try {
                if (!canReconnectTo(request)) { endReconnectTo(request, "Control cancelled"); return; }
                if (request.candidate == null || !request.candidate.isOpen())
                    throw new IOException("TV disconnected");
                // This commit and lifecycle/Setup mutations run serially on the main
                // handler. Cancellation cannot turn a stale verification into saved trust.
                if (!saveAddress(request.address)) {
                    endReconnectTo(request, "Could not save the TV address. Scan again to retry.");
                    return;
                }
                host = request.address;
                remote = request.candidate;
                observeKeyboard(remote, request.token);
                request.candidate = null;
                request.completed.set(true);
                addressReconnect = null;
            } catch (Exception failure) {
                endReconnectTo(request, reconnectToError(failure));
                return;
            }
        }
        main.removeCallbacks(request.timeout);
        publishPictureState("unknown");
        state("Connected", request.token);
        finish(request.completion, null, request.token, -1, request.foregroundToken, false);
    }

    private boolean saveAddress(String address) {
        String oldHost = preferences.getString("host", "");
        if (preferences.edit().putString("host", address).commit()) return true;
        // Failed commits can still alter SharedPreferences' in-memory map.
        preferences.edit().putString("host", oldHost).commit();
        return false;
    }

    private void endReconnectTo(AddressReconnect request, String error) {
        synchronized (connectionLock) {
            if (!request.completed.compareAndSet(false, true)) return;
            if (request.candidate != null) request.candidate.close();
            request.candidate = null;
            if (addressReconnect == request) addressReconnect = null;
        }
        main.removeCallbacks(request.timeout);
        state(readyStatus(), request.token);
        finish(request.completion, error, request.token, -1, request.foregroundToken, false);
    }

    private static String reconnectToError(Exception failure) {
        if (failure instanceof SocketTimeoutException)
            return "TV timed out. Check Wi-Fi and the TV address, then scan again.";
        return "Could not reconnect with the saved pairing. Check the TV address. If this TV was reset or its trust changed, choose Pair with a TV code.";
    }

    /** The widget foreground service owns its idle timer, notification and lifetime. */
    void setWidgetSessionActive(boolean active) {
        synchronized (connectionLock) {
            if (widgetSessionActive && !active) widgetGeneration.incrementAndGet();
            widgetSessionActive = active;
            cancelInactivePictureRequests();
            if (!connectionWanted()) closeRemote();
        }
    }

    void resume() {
        synchronized (connectionLock) {
            foreground = true;
            if (remote != null) publishKeyboard(remote, generation.get());
        }
        if (isReconnectingTo()) return;
        if (pairingBusy) {
            if (pairing != null) notifyCode();
            return;
        }
        if (!isPaired()) { state(readyStatus()); return; }
        int token = generation.get();
        execute(() -> {
            if (!current(token) || !foreground || pairingBusy || isReconnectingTo()) return;
            state("Connecting…", token);
            try {
                TvProtocol.Remote connected = connect(token);
                synchronized (connectionLock) {
                    if (!current(token) || !connectionWanted()) { discard(connected); return; }
                }
                state("Connected", token);
                pictureRequest(false, null, false, true);
            } catch (Exception failure) {
                if (current(token) && foreground) error(message(failure), token);
            }
        }, null);
    }

    void pause() {
        synchronized (connectionLock) {
            if (foreground) foregroundGeneration.incrementAndGet();
            foreground = false;
            cancelInactivePictureRequests();
            if (!connectionWanted()) closeRemote();
        }
        cancelReconnectTo();
        if (pairingBusy) cancelPairing();
        else state(readyStatus());
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
                    boolean saved = savePairing(address, certificate);
                    if (!saved) {
                        cancelPairing();
                        error("Could not save pairing. Request a new code to retry.");
                        return;
                    }
                    host = address;
                    pin = certificate;
                    pairedIdentityVersion = PhoneIdentity.VERSION;
                    publishPictureState("unknown");
                    pairingBusy = false;
                    pairing = null;
                    pendingHost = null;
                    removePairingExpiry();
                    RemoteWidget.updateAll(context, "Tap to connect");
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

    private boolean savePairing(String address, byte[] certificate) {
        String oldHost = preferences.getString("host", "");
        String oldCertificate = preferences.getString("certificate", "");
        int oldIdentityVersion = preferences.getInt("pairing_identity_version", 0);
        if (preferences.edit().putString("host", address)
                .putString("certificate", Base64.encodeToString(certificate, Base64.NO_WRAP))
                .putInt("pairing_identity_version", PhoneIdentity.VERSION).commit()) return true;
        // A failed disk commit can still replace SharedPreferences' in-memory map.
        // Restore the old trust and migration marker before another Activity reads it.
        preferences.edit().putString("host", oldHost).putString("certificate", oldCertificate)
                .putInt("pairing_identity_version", oldIdentityVersion).commit();
        return false;
    }

    void cancelPairing() {
        cancelReconnectTo();
        generation.incrementAndGet();
        synchronized (connectionLock) { cancelInactivePictureRequests(); }
        TvProtocol.Pairing old = pairing;
        pairing = null;
        pendingHost = null;
        pairingBusy = false;
        if (old != null) old.close();
        removePairingExpiry();
        state(readyStatus());
    }

    private void removePairingExpiry() {
        if (pairingExpiry != null) { main.removeCallbacks(pairingExpiry); pairingExpiry = null; }
    }

    void forget() {
        cancelPairing();
        closeRemote();
        preferences.edit().remove("host").remove("certificate").remove("pairing_identity_version").apply();
        host = "";
        pin = null;
        pairedIdentityVersion = 0;
        publishPictureState("unknown");
        state("Pair your TV in Setup");
    }

    void sendKey(int key) { command(key, false, null); }
    BooleanSupplier repeatingSession() {
        final int token = generation.get();
        final int foregroundToken = foregroundGeneration.get();
        return () -> foreground && current(token) && foregroundGeneration.get() == foregroundToken;
    }
    void sendRepeatingKey(int key, BooleanSupplier held, Completion completion) {
        command(key, false, null, completion, false, held);
    }
    void sendLongKey(int key) { command(key, true, null); }
    void sendWidgetKey(int key, Completion completion) { command(key, false, completion); }

    void requestPictureState(Completion completion) {
        pictureRequest(false, completion, completion != null, false);
    }

    void togglePictureMode(Completion completion) {
        pictureRequest(true, completion, completion != null, false);
    }

    private void pictureRequest(boolean toggle, Completion completion, boolean widgetOwner, boolean quiet) {
        final PictureOperation request;
        synchronized (connectionLock) {
            if (!isPaired() || pairingBusy || isReconnectingTo()) {
                if (!quiet) finish(completion, !isPaired() ? pairingRequiredMessage()
                        : pairingBusy ? "Finish pairing first." : "Finish reconnecting in Setup first.");
                return;
            }
            if (pictureRequests.size() >= 6) {
                if (!quiet) finish(completion, "Picture sync is busy. Try again.");
                return;
            }
            request = new PictureOperation(generation.get(), completion, widgetOwner, toggle, quiet);
            pictureRequests.add(request);
        }
        request.operation.timeout = () -> {
            synchronized (connectionLock) {
                if (request.operation.completed.get()) return;
                if (request.operation.writing != null) discard(request.operation.writing);
                request.operation.writing = null;
                finishPicture(request, null, "Could not confirm TV picture mode. Tap Sync before trying again.");
            }
        };
        main.postDelayed(request.operation.timeout, 7000);
        execute(() -> {
            TvProtocol.Remote connection = null;
            boolean attempted = false;
            try {
                if (!canRun(request.operation)) { finishPicture(request, null, "Control cancelled"); return; }
                connection = connect(request.operation.token);
                synchronized (connectionLock) {
                    if (!canRun(request.operation)) { finishPicture(request, null, "Control cancelled"); return; }
                    long remaining = request.operation.deadline - SystemClock.elapsedRealtime();
                    if (remaining < TvProtocol.DELIVERY_WINDOW_MS)
                        throw new SocketTimeoutException("Picture request expired before sending");
                    request.client = connection.openPictureStateRequest((int) Math.min(4000, remaining));
                    // Reserve a bounded waiter before writing. Replies cannot delay normal keys.
                    pictureWorker.execute(() -> awaitPicture(request));
                    request.operation.writing = connection;
                    attempted = true;
                }
                connection.sendPictureStateRequest(request.client, request.toggle);
                synchronized (connectionLock) { request.operation.writing = null; }
            } catch (Exception failure) {
                synchronized (connectionLock) {
                    request.operation.writing = null;
                    if (attempted && connection != null) discard(connection);
                }
                finishPicture(request, null, pictureError(failure));
            }
        }, () -> finishPicture(request, null, "Remote is busy. Try again."));
    }

    private final class PictureOperation {
        final Operation operation;
        final boolean toggle, quiet;
        final int revision = pictureRevision.incrementAndGet();
        PictureStateClient client; // Guarded by connectionLock; close cancels receive immediately.
        PictureOperation(int token, Completion completion, boolean widgetOwner, boolean toggle, boolean quiet) {
            operation = new Operation(token, completion, widgetOwner);
            this.toggle = toggle;
            this.quiet = quiet;
        }
    }

    private void awaitPicture(PictureOperation request) {
        try {
            String result = request.client.await();
            String error = "unavailable".equals(result)
                    ? "Picture controls are unavailable. Update BareHome and check its picture setup."
                    : "error".equals(result) ? "TV could not apply the picture preset. Check Picture settings." : null;
            finishPicture(request, error == null ? result : null, error);
        } catch (Exception failure) {
            finishPicture(request, null, pictureError(failure));
        }
    }

    private void finishPicture(PictureOperation request, String result, String error) {
        Operation operation = request.operation;
        synchronized (connectionLock) {
            if (!operation.completed.compareAndSet(false, true)) return;
            if (request.client != null) request.client.close();
            pictureRequests.remove(request);
        }
        main.removeCallbacks(operation.timeout);
        main.post(() -> {
            if (current(operation) && request.revision == pictureRevision.get()
                    && !"Control cancelled".equals(error)) {
                publishPictureState(error == null && confirmedState(result) ? result : "unknown");
            }
            if (operation.completion != null || request.revision == pictureRevision.get())
                finish(operation.completion, request.quiet ? null : error, operation.token,
                        operation.widgetToken, operation.foregroundToken, false);
        });
    }

    private void cancelInactivePictureRequests() {
        for (PictureOperation request : new HashSet<>(pictureRequests)) {
            Operation operation = request.operation;
            boolean active = operation.widgetToken != -1 ? widgetSessionActive : foreground;
            if (!current(operation) || !active) finishPicture(request, null, "Control cancelled");
        }
    }

    private static String pictureError(Exception failure) {
        if (failure instanceof UnsupportedOperationException) return failure.getMessage();
        return "Could not confirm TV picture mode. Update BareHome or tap Sync; the command is not repeated.";
    }

    private static boolean confirmedState(String state) {
        return "day".equals(state) || "night".equals(state) || "custom".equals(state);
    }

    private String pictureScope() {
        if (!isPaired()) return "";
        try { return host + ":" + PictureStateWire.hex(MessageDigest.getInstance("SHA-256").digest(pin)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void publishPictureState(String value) {
        confirmedPicture = value;
        preferences.edit().putString("picture_scope", pictureScope()).putString("picture_state", value).apply();
        Listener target = listener;
        if (target != null) target.onPictureState(value);
        RemoteWidget.updateAll(context);
    }

    void sendPictureMode(boolean night) { sendPictureMode(night, null); }
    void sendPictureMode(boolean night, Completion completion) { command(0, false, night, completion); }

    private void command(int key, boolean longPress, Completion completion) {
        command(key, longPress, null, completion);
    }

    private void command(int key, boolean longPress, Boolean pictureNight, Completion completion) {
        command(key, longPress, pictureNight, completion, completion != null, null);
    }

    private void command(int key, boolean longPress, Boolean pictureNight, Completion completion,
            boolean widgetOwner, BooleanSupplier held) {
        if (pictureNight == null && !allowedKey(key)) { finish(completion, "Unknown control"); return; }
        final Operation operation;
        synchronized (connectionLock) {
            if (!isPaired()) { finish(completion, pairingRequiredMessage()); return; }
            if (pairingBusy) { finish(completion, "Finish pairing in the app first"); return; }
            if (isReconnectingTo()) { finish(completion, "Finish reconnecting in Setup first."); return; }
            operation = new Operation(generation.get(), completion, widgetOwner, held);
        }
        startTimeout(operation);
        execute(() -> {
            TvProtocol.Remote connection = null;
            boolean writeAttempted = false;
            try {
                if (!canRun(operation)) { complete(operation, "Control cancelled"); return; }
                if (held == null) connection = connect(operation.token);
                else {
                    // A held button continues a live session. A lost connection
                    // stops the gesture; it must never reconnect and replay repeats.
                    synchronized (connectionLock) { connection = remote; }
                    if (connection == null || !connection.isOpen())
                        throw new IOException("TV disconnected. Tap Sync to reconnect.");
                }
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
                if (pictureNight != null) connection.sendPictureMode(pictureNight);
                else if (key == KeyEvent.KEYCODE_TV_INPUT) connection.sendInputPicker();
                else if (longPress) connection.sendLongKey(key); else connection.sendKey(key);
                synchronized (connectionLock) { operation.writing = null; }
                if (pictureNight != null) pictureRequest(false, null, operation.widgetToken != -1, true);
                if (!widgetOwner) {
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

    /** Foreground-only keyboard work never reconnects or migrates to another field. */
    void sendText(String text) {
        try { KeyboardSession.validateCommit(text); }
        catch (IllegalArgumentException invalid) { error(invalid.getMessage()); return; }
        keyboardCommand(text, 0);
    }
    void sendKeyboardKey(int key) {
        if (key != 67 && key != 112 && key != 66 && key != 21 && key != 22 && key != 61) {
            error("Unknown keyboard control"); return;
        }
        keyboardCommand(null, key);
    }
    void cancelKeyboardInput() { keyboardGeneration.incrementAndGet(); }
    private void keyboardCommand(String text, int key) {
        final int keyboardToken = keyboardGeneration.get();
        final TvProtocol.Remote connection;
        final KeyboardState field;
        final Operation operation;
        synchronized (connectionLock) {
            if (!foreground || pairingBusy || isReconnectingTo()) return;
            connection = remote;
            field = keyboardState();
            if (connection == null || (text != null ? !field.canType() : !field.supported || !field.active)) {
                error(field.supported ? "Select a text field on the TV first"
                        : "This TV has not made its keyboard available. Tap Sync or select a text field.");
                return;
            }
            operation = new Operation(generation.get(), null, false);
        }
        startTimeout(operation);
        execute(() -> {
            boolean writeAttempted = false;
            try {
                synchronized (connectionLock) {
                    if (!canRun(operation) || keyboardToken != keyboardGeneration.get() || remote != connection || !connection.isOpen()) {
                        complete(operation, "Keyboard input cancelled"); return;
                    }
                    if (!connection.keyboardState().sameField(field)) {
                        complete(operation, "TV text field changed; type again"); return;
                    }
                    operation.writing = connection;
                    writeAttempted = true;
                }
                if (text != null) connection.sendText(text, field);
                else connection.sendKeyboardKey(key, field);
                synchronized (connectionLock) { operation.writing = null; }
                complete(operation, null, false);
            } catch (Exception failure) {
                synchronized (connectionLock) {
                    operation.writing = null;
                    if (writeAttempted && !(failure instanceof KeyboardState.InputUnavailableException)) discard(connection);
                }
                // Messages describe metadata/delivery only; text is never logged or included.
                complete(operation, message(failure));
            }
        }, () -> complete(operation, "Keyboard is busy. Type again."));
    }

    private final class Operation {
        final int token;
        final Completion completion;
        final int widgetToken;
        final int foregroundToken;
        final BooleanSupplier held;
        final long deadline = SystemClock.elapsedRealtime() + 7000;
        final AtomicBoolean completed = new AtomicBoolean();
        // Guarded by connectionLock. Non-null only while this operation writes.
        TvProtocol.Remote writing;
        Runnable timeout;
        Operation(int token, Completion completion) { this(token, completion, completion != null); }
        Operation(int token, Completion completion, boolean widgetOwner) {
            this(token, completion, widgetOwner, null);
        }
        Operation(int token, Completion completion, boolean widgetOwner, BooleanSupplier held) {
            this.token = token;
            this.completion = completion;
            this.widgetToken = widgetOwner ? widgetGeneration.get() : -1;
            this.foregroundToken = widgetOwner ? -1 : foregroundGeneration.get();
            this.held = held;
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
        boolean ownerActive = operation.widgetToken == -1 ? foreground : widgetSessionActive;
        if (operation.completed.get() || !current(operation) || !ownerActive || pairingBusy || isReconnectingTo()
                || (operation.held != null && !operation.held.getAsBoolean())) return false;
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
            if (!current(token) || !connectionWanted() || pairingBusy || isReconnectingTo())
                throw new IOException("Control cancelled");
            if (!isPaired()) throw new IOException(pairingRequiredMessage());
            TvProtocol.Remote existing = remote;
            if (existing != null && existing.isOpen()) return existing;
            closeRemote();
            address = host;
            certificate = pin;
        }
        TvProtocol.Remote connected = TvProtocol.Remote.connect(address, 6466, certificate, identity);
        synchronized (connectionLock) {
            if (!current(token) || !connectionWanted() || pairingBusy || isReconnectingTo()) {
                connected.close();
                throw new IOException("Control cancelled");
            }
            remote = connected;
            observeKeyboard(connected, token);
            return connected;
        }
    }

    /** Discard only this exact session; an old callback cannot close its replacement. */
    private void discard(TvProtocol.Remote connection) {
        synchronized (connectionLock) {
            if (remote == connection) {
                remote = null;
                connection.setKeyboardListener(null);
                publishKeyboard(null, generation.get());
            }
            connection.close();
        }
    }
    private void closeRemote() {
        synchronized (connectionLock) {
            TvProtocol.Remote old = remote;
            remote = null;
            if (old != null) { old.setKeyboardListener(null); old.close(); }
            publishKeyboard(null, generation.get());
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
                boolean valid = current(token) && (widgetToken == -1 || widgetGeneration.get() == widgetToken)
                        && (foregroundToken == -1 || foregroundGeneration.get() == foregroundToken);
                completion.done(valid ? error : "Control cancelled");
            }
            else if (current(token) && (foregroundToken == -1 || foregroundGeneration.get() == foregroundToken)
                    && (widgetToken == -1 || widgetGeneration.get() == widgetToken)) {
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
            case KeyEvent.KEYCODE_TV_INPUT:
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
        if (failure instanceof UnsupportedOperationException) return failure.getMessage();
        if (failure instanceof SocketTimeoutException) return "TV timed out. Check that both devices are on the same Wi-Fi.";
        String detail = failure.getMessage();
        if (detail != null && (detail.contains("code") || detail.contains("Code")
                || detail.contains("pair") || detail.contains("Pair") || detail.contains("identity"))) return detail;
        return "Cannot reach TV. Check Wi-Fi and the TV address, then tap Sync.";
    }
}
