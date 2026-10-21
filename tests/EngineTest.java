package uy.nico.bareremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Deterministic engine regression tests; no Android device or network access. */
public final class EngineTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        burstAndOwnership();
        repeatingKeysUseForegroundSession();
        queuedReleaseCancelsRepeat();
        repeatingKeysNeverReconnect();
        repeatingSessionEndsAtSync();
        repeatingKeysFollowForegroundLifetime();
        lateRepeatCompletionCannotReportSuccessAfterPause();
        pictureAndInputCommands();
        confirmedPictureCallbacks();
        pictureCancellationAndOrdering();
        pictureErrorsAndScopedCache();
        persistedPictureScope();
        queuedTimeout();
        insufficientDeliveryBudget();
        syncCancelsOldCommands();
        stopThenRestart();
        pauseThenResume();
        syncDuringConnect();
        blockedWriteTimeout();
        identityUpgradeBlocksUntilPairing();
        identityUpgradeFailuresAndCancellation();
        identityUpgradeSavedOnlyOnSuccess();
        freshIdentityPairing();
        changedAddressKeepsPairing();
        changedAddressFailuresKeepTrust();
        changedAddressPersistenceFailure();
        changedAddressValidationAndMigration();
        changedAddressCancellation();
        changedAddressMainThreadCancellation();
        changedAddressDisconnectedBeforeSave();
        changedAddressSuperseded();
        changedAddressDeadlines();
        changedAddressRejectsConcurrentControls();
        changedAddressCancelsOldCommands();
        System.out.println("Engine tests: " + assertions + " assertions passed");
    }

    private static RemoteEngine engine() throws Exception {
        return engine(new Context());
    }

    private static RemoteEngine engine(Context context) throws Exception {
        Handler.reset();
        TvProtocol.Remote.reset();
        TvProtocol.Pairing.reset();
        Constructor<RemoteEngine> constructor = RemoteEngine.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static Context legacyContext() {
        Context context = new Context();
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().remove("pairing_identity_version").apply();
        return context;
    }

    private static void identityUpgradeBlocksUntilPairing() throws Exception {
        Context context = legacyContext();
        RemoteEngine engine = engine(context);
        try {
            check(engine.hasSavedTv() && engine.needsPairingUpgrade() && !engine.isPaired(),
                    "legacy trust is saved but not usable with the new identity");
            check("192.0.2.1".equals(engine.host()), "upgrade preserves saved TV address");
            check(RemoteEngine.PAIRING_UPGRADE_MESSAGE.equals(engine.status()), "upgrade explains one-time pairing");
            engine.resume(); idle(engine);
            engine.setWidgetSessionActive(true);
            List<String> errors = new ArrayList<>();
            engine.reconnect(errors::add);
            engine.sendWidgetKey(21, errors::add);
            engine.togglePictureMode(errors::add);
            engine.requestPictureState(errors::add);
            engine.sendPictureMode(true, errors::add);
            idle(engine);
            check(errors.size() == 5 && errors.stream().allMatch(RemoteEngine.PAIRING_UPGRADE_MESSAGE::equals),
                    "all app and widget operations report migration without generic TLS errors");
            check(TvProtocol.Remote.connections.isEmpty() && TvProtocol.Remote.keys.isEmpty()
                    && TvProtocol.Remote.pictureRequests.isEmpty() && TvProtocol.Pairing.starts == 0,
                    "legacy migration never auto-pairs, connects, sends keys or toggles");
            SharedPreferences prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
            check("AQ==".equals(prefs.getString("certificate", ""))
                    && prefs.getInt("pairing_identity_version", 0) == 0, "blocking leaves legacy trust unchanged");
        } finally { shutdown(engine); }
        RemoteEngine restarted = engine(context);
        try { check(restarted.needsPairingUpgrade(), "restart cannot bypass incomplete migration"); }
        finally { shutdown(restarted); }
    }

    private static void identityUpgradeFailuresAndCancellation() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            Context context = legacyContext();
            SharedPreferences prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
            RemoteEngine engine = engine(context);
            try {
                engine.resume(); idle(engine);
                TvProtocol.Pairing.failStart = scenario == 0;
                engine.startPairing("192.0.2.9"); idle(engine);
                if (scenario == 1) {
                    engine.cancelPairing(); idle(engine);
                } else if (scenario == 2 || scenario == 3) {
                    TvProtocol.Pairing.failFinish = scenario == 2;
                    prefs.failNextCommit = scenario == 3;
                    engine.finishPairing("ABC123"); idle(engine);
                }
                check(engine.needsPairingUpgrade() && !engine.isPaired(), "failed or cancelled pairing keeps migration pending " + scenario);
                check("192.0.2.1".equals(prefs.getString("host", ""))
                        && "AQ==".equals(prefs.getString("certificate", ""))
                        && prefs.getInt("pairing_identity_version", 0) == 0,
                        "failed or cancelled pairing preserves old saved trust and version " + scenario);
                check(TvProtocol.Remote.connections.isEmpty(), "failed migration does not connect " + scenario);
            } finally { shutdown(engine); }
            RemoteEngine restarted = engine(context);
            try { check(restarted.needsPairingUpgrade(), "failed migration remains pending after restart " + scenario); }
            finally { shutdown(restarted); }
        }

        Context context = legacyContext();
        RemoteEngine engine = engine(context);
        CountDownLatch release = new CountDownLatch(1);
        try {
            engine.resume(); idle(engine);
            engine.startPairing("192.0.2.9"); idle(engine);
            TvProtocol.Pairing.finishGate = release;
            TvProtocol.Pairing.finishEntered = new CountDownLatch(1);
            engine.finishPairing("ABC123");
            check(TvProtocol.Pairing.finishEntered.await(2, TimeUnit.SECONDS), "pairing finish waits for remote ACK");
            engine.cancelPairing();
            release.countDown(); idle(engine);
            check(engine.needsPairingUpgrade()
                    && context.getSharedPreferences("remote", Context.MODE_PRIVATE).getInt("pairing_identity_version", 0) == 0,
                    "late successful ACK after cancellation cannot finish migration");
        } finally { release.countDown(); shutdown(engine); }
    }

    private static void identityUpgradeSavedOnlyOnSuccess() throws Exception {
        Context context = legacyContext();
        RemoteEngine engine = engine(context);
        try {
            engine.resume(); idle(engine);
            engine.startPairing("192.0.2.9"); idle(engine);
            check(engine.needsPairingUpgrade(), "requesting a code does not finish migration");
            engine.finishPairing("ABC123"); idle(engine); idle(engine);
            SharedPreferences prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
            check(engine.isPaired() && !engine.needsPairingUpgrade(), "successful explicit pairing enables current identity");
            check("192.0.2.9".equals(prefs.getString("host", ""))
                    && "Ag==".equals(prefs.getString("certificate", ""))
                    && prefs.getInt("pairing_identity_version", 0) == PhoneIdentity.VERSION,
                    "successful pairing saves address pin and identity version together");
            check(TvProtocol.Pairing.starts == 1 && TvProtocol.Pairing.finishes == 1,
                    "successful migration pairs exactly once");
            engine.sendKey(21); idle(engine);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(21)), "newly paired identity sends one key");
        } finally { shutdown(engine); }
        RemoteEngine restarted = engine(context);
        try {
            check(restarted.isPaired() && !restarted.needsPairingUpgrade(), "completed migration survives restart");
            restarted.resume(); idle(restarted);
            check(TvProtocol.Pairing.starts == 0 && TvProtocol.Remote.connections.size() == 1,
                    "restart reconnects with saved identity without another code");
            restarted.forget(); Handler.drain();
            check(!restarted.hasSavedTv() && !restarted.needsPairingUpgrade()
                    && context.getSharedPreferences("remote", Context.MODE_PRIVATE).getInt("pairing_identity_version", 0) == 0,
                    "Forget removes saved trust and version without inventing an upgrade");
        } finally { shutdown(restarted); }
    }

    private static void freshIdentityPairing() throws Exception {
        Context context = legacyContext();
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().remove("host").remove("certificate").apply();
        RemoteEngine engine = engine(context);
        try {
            check(!engine.hasSavedTv() && !engine.needsPairingUpgrade() && !engine.isPaired(),
                    "fresh install uses normal pairing flow");
            engine.resume(); idle(engine);
            check(TvProtocol.Pairing.starts == 0 && TvProtocol.Remote.connections.isEmpty(), "fresh app never auto-pairs");
            engine.startPairing("192.0.2.9"); idle(engine);
            engine.finishPairing("ABC123"); idle(engine); idle(engine);
            check(engine.isPaired() && !engine.needsPairingUpgrade(), "fresh explicit pairing is immediately usable");
        } finally { shutdown(engine); }
    }

    private static void savedTrust(Context context, String expectedHost, String message) {
        SharedPreferences prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        check(expectedHost.equals(prefs.getString("host", ""))
                && "AQ==".equals(prefs.getString("certificate", ""))
                && prefs.getInt("pairing_identity_version", 0) == PhoneIdentity.VERSION, message);
    }

    private static void noReconnectCommands(String message) {
        check(TvProtocol.Pairing.starts == 0 && TvProtocol.Pairing.finishes == 0
                && TvProtocol.Remote.keys.isEmpty() && TvProtocol.Remote.inputs.isEmpty()
                && TvProtocol.Remote.modes.isEmpty() && TvProtocol.Remote.pictureToggles.isEmpty(), message);
    }

    private static CountDownLatch gateNextConnection() {
        CountDownLatch release = new CountDownLatch(1);
        TvProtocol.Remote.connectGate = release;
        TvProtocol.Remote.connectEntered = new CountDownLatch(1);
        return release;
    }

    private static void changedAddressKeepsPairing() throws Exception {
        Context context = new Context();
        RemoteEngine engine = engine(context);
        CountDownLatch release = null;
        try {
            TvProtocol.Remote.holdPictureReplies = true;
            engine.resume(); idle(engine); idle(engine);
            TvProtocol.Remote original = TvProtocol.Remote.connections.get(0);
            PictureStateClient oldPicture = TvProtocol.Remote.pictureRequests.get(0);
            TvProtocol.Remote.pictureRequests.clear(); TvProtocol.Remote.pictureToggles.clear();
            List<String> result = new ArrayList<>();
            release = gateNextConnection();
            engine.reconnectTo(" 192.000.002.009 ", result::add);
            check(TvProtocol.Remote.connectEntered.await(2, TimeUnit.SECONDS), "changed address begins pinned readiness check");
            check(engine.isReconnectingTo() && !original.isOpen() && oldPicture.closed,
                    "checking new address ends old session and callback listener");
            savedTrust(context, "192.0.2.1", "candidate is not saved before authenticated readiness");
            TvProtocol.Remote candidate = TvProtocol.Remote.connections.get(1);
            check("192.0.2.9".equals(candidate.host) && candidate.port == 6466
                    && java.util.Arrays.equals(original.pin, candidate.pin) && candidate.identity == original.identity,
                    "canonical new IPv4 uses saved certificate and the same phone identity on remote port");
            release.countDown(); idle(engine);
            check(!engine.isReconnectingTo() && "192.0.2.9".equals(engine.host()) && engine.isPaired()
                    && candidate.isOpen(), "authenticated candidate becomes the retained connected TV");
            check(result.size() == 1 && result.get(0) == null, "changed address completes successfully once");
            savedTrust(context, "192.0.2.9", "new address saves without replacing certificate or identity version");
            check("unknown".equals(engine.pictureState()) && TvProtocol.Remote.pictureRequests.isEmpty(),
                    "changed address clears scoped picture state without sending a new app link");
            noReconnectCommands("reconnecting sends no keys, pairing requests, toggles or input links");
            engine.sendKey(22); idle(engine);
            check(TvProtocol.Remote.connections.size() == 2 && TvProtocol.Remote.keys.equals(java.util.Arrays.asList(22)),
                    "next explicit key reuses the newly verified socket once");
            List<String> same = new ArrayList<>();
            engine.reconnectTo("192.0.2.9", same::add); idle(engine);
            check(same.size() == 1 && same.get(0) == null && TvProtocol.Remote.connections.size() == 3
                    && !candidate.isOpen() && TvProtocol.Remote.connections.get(2).isOpen(),
                    "selecting saved address still performs a fresh pinned reconnect without a code");
        } finally { if (release != null) release.countDown(); shutdown(engine); }
        RemoteEngine restarted = engine(context);
        try {
            check(restarted.isPaired() && "192.0.2.9".equals(restarted.host()),
                    "new address and pairing survive process recreation");
            restarted.resume(); idle(restarted); idle(restarted);
            check(TvProtocol.Remote.connections.size() == 1
                    && "192.0.2.9".equals(TvProtocol.Remote.connections.get(0).host)
                    && java.util.Arrays.equals(new byte[]{1}, TvProtocol.Remote.connections.get(0).pin)
                    && TvProtocol.Pairing.starts == 0, "restart reconnects at new address with saved pin and no TV code");
        } finally { shutdown(restarted); }
    }

    private static void changedAddressFailuresKeepTrust() throws Exception {
        for (int scenario = 0; scenario < 2; scenario++) {
            Context context = new Context();
            RemoteEngine engine = engine(context);
            try {
                engine.resume(); idle(engine); idle(engine);
                if (scenario == 0) TvProtocol.Remote.failHost = "192.0.2.9";
                else TvProtocol.Remote.pinRejectHost = "192.0.2.9";
                List<String> result = new ArrayList<>();
                engine.reconnectTo("192.0.2.9", result::add); idle(engine);
                check(result.size() == 1 && result.get(0) != null && !engine.isReconnectingTo(),
                        "unreachable or adapter-rejected candidate reports failure once " + scenario);
                check(engine.isPaired() && "192.0.2.1".equals(engine.host()),
                        "failed candidate retains usable saved pairing " + scenario);
                savedTrust(context, "192.0.2.1", "failed candidate never changes persisted trust " + scenario);
                check(!TvProtocol.Remote.connections.get(1).isOpen(), "failed candidate socket closes " + scenario);
                check(TvProtocol.Pairing.starts == 0 && TvProtocol.Pairing.finishes == 0
                        && TvProtocol.Remote.keys.isEmpty(), "failure never falls back to pairing or replays a key " + scenario);
                engine.setWidgetSessionActive(true);
                engine.reconnect(result::add); idle(engine);
                check(result.size() == 2 && result.get(1) == null
                        && "192.0.2.1".equals(TvProtocol.Remote.connections.get(2).host),
                        "later explicit Sync still uses original address after failed candidate " + scenario);
            } finally { shutdown(engine); }
        }
    }

    private static void changedAddressPersistenceFailure() throws Exception {
        Context context = new Context();
        RemoteEngine engine = engine(context);
        try {
            engine.resume(); idle(engine); idle(engine);
            context.getSharedPreferences("remote", Context.MODE_PRIVATE).failNextCommit = true;
            List<String> result = new ArrayList<>();
            engine.reconnectTo("192.0.2.9", result::add); idle(engine);
            check(result.size() == 1 && result.get(0) != null && !engine.isReconnectingTo(),
                    "failed address commit reports failure once");
            check(engine.isPaired() && "192.0.2.1".equals(engine.host())
                    && !TvProtocol.Remote.connections.get(1).isOpen(), "failed save discards candidate and retains original engine trust");
            savedTrust(context, "192.0.2.1", "failed disk commit rolls SharedPreferences in-memory map back to original trust");
            check(TvProtocol.Pairing.starts == 0 && TvProtocol.Remote.keys.isEmpty(), "failed save sends no pairing or keys");
        } finally { shutdown(engine); }
        RemoteEngine restarted = engine(context);
        try { check(restarted.isPaired() && "192.0.2.1".equals(restarted.host()), "restart after failed save uses original pairing"); }
        finally { shutdown(restarted); }
    }

    private static void changedAddressValidationAndMigration() throws Exception {
        Context context = new Context();
        RemoteEngine engine = engine(context);
        try {
            engine.resume(); idle(engine); idle(engine);
            TvProtocol.Remote original = TvProtocol.Remote.connections.get(0);
            List<String> errors = new ArrayList<>();
            for (String address : new String[]{null, "tv.local", "192.0.2.999", "0.0.0.0", "224.0.0.1", "::1"}) {
                engine.reconnectTo(address, errors::add); Handler.drain();
            }
            check(errors.size() == 6 && errors.stream().allMatch(value -> value != null), "invalid or nonnumeric candidates fail before networking");
            check(TvProtocol.Remote.connections.size() == 1 && original.isOpen() && !engine.isReconnectingTo(),
                    "invalid candidate does not disturb existing session");
            savedTrust(context, "192.0.2.1", "invalid candidates preserve saved trust");
            engine.pause();
            engine.reconnectTo("192.0.2.9", errors::add); idle(engine);
            check(errors.size() == 7 && errors.get(6) != null && TvProtocol.Remote.connections.size() == 1,
                    "address checks require a foreground owner even when completion exists");
        } finally { shutdown(engine); }
        for (int scenario = 0; scenario < 2; scenario++) {
            Context unavailable = legacyContext();
            if (scenario == 1) unavailable.getSharedPreferences("remote", Context.MODE_PRIVATE).edit()
                    .remove("host").remove("certificate").apply();
            RemoteEngine unpaired = engine(unavailable);
            try {
                unpaired.resume(); idle(unpaired);
                List<String> errors = new ArrayList<>();
                unpaired.reconnectTo("192.0.2.9", errors::add); idle(unpaired);
                check(errors.size() == 1 && errors.get(0) != null && !unpaired.isReconnectingTo(),
                        "unpaired or legacy identity cannot use address recovery " + scenario);
                check(TvProtocol.Remote.connections.isEmpty() && TvProtocol.Pairing.starts == 0,
                        "recovery cannot bypass required explicit pairing " + scenario);
            } finally { shutdown(unpaired); }
        }
    }

    private static void changedAddressCancellation() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            Context context = new Context();
            RemoteEngine engine = engine(context);
            CountDownLatch release = null;
            try {
                engine.resume(); idle(engine); idle(engine);
                // A widget owner must not keep an Activity-initiated address check alive.
                engine.setWidgetSessionActive(true);
                List<String> result = new ArrayList<>();
                release = gateNextConnection();
                engine.reconnectTo("192.0.2.9", result::add);
                check(TvProtocol.Remote.connectEntered.await(2, TimeUnit.SECONDS), "cancellable address check entered " + scenario);
                if (scenario == 0) engine.cancelReconnectTo();
                else if (scenario == 1) { engine.pause(); engine.resume(); }
                else if (scenario == 2) engine.forget();
                else engine.startPairing("192.0.2.8");
                release.countDown(); idle(engine); idle(engine);
                check(result.size() == 1 && "Control cancelled".equals(result.get(0)) && !engine.isReconnectingTo(),
                        "cancel, pause, Forget or new pairing ends check once " + scenario);
                check(!TvProtocol.Remote.connections.get(1).isOpen(), "late candidate after cancellation is discarded " + scenario);
                check(TvProtocol.Remote.keys.isEmpty(), "cancelled address check never replays a key " + scenario);
                if (scenario == 2) {
                    check(!engine.hasSavedTv()
                            && context.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("host", "").isEmpty(),
                            "Forget cannot be undone by late successful candidate");
                } else savedTrust(context, "192.0.2.1", "cancelled candidate cannot replace saved trust " + scenario);
                check(TvProtocol.Pairing.starts == (scenario == 3 ? 1 : 0),
                        "only explicit new Pair action can request a code " + scenario);
            } finally { if (release != null) release.countDown(); shutdown(engine); }
        }
    }

    private static void changedAddressMainThreadCancellation() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            Context context = new Context();
            RemoteEngine engine = engine(context);
            try {
                engine.resume(); idle(engine); idle(engine);
                List<String> result = new ArrayList<>();
                engine.reconnectTo("192.0.2.9", result::add);
                // Authentication has returned, but its main-thread save has not run.
                worker(engine).submit(() -> {}).get(2, TimeUnit.SECONDS);
                savedTrust(context, "192.0.2.1", "worker readiness cannot directly save candidate " + scenario);
                if (scenario == 0) engine.cancelReconnectTo();
                else if (scenario == 1) engine.pause();
                else engine.forget();
                Handler.drain(); idle(engine);
                check(result.size() == 1 && "Control cancelled".equals(result.get(0))
                        && !TvProtocol.Remote.connections.get(1).isOpen(),
                        "pending main-thread commit cannot outlive cancel, pause or Forget " + scenario);
                if (scenario < 2) savedTrust(context, "192.0.2.1", "stale main-thread success preserves original trust " + scenario);
                else check(!engine.hasSavedTv(), "stale main-thread success cannot undo Forget");
            } finally { shutdown(engine); }
        }
    }

    private static void changedAddressDisconnectedBeforeSave() throws Exception {
        Context context = new Context();
        RemoteEngine engine = engine(context);
        try {
            engine.resume(); idle(engine); idle(engine);
            List<String> result = new ArrayList<>();
            engine.reconnectTo("192.0.2.9", result::add);
            worker(engine).submit(() -> {}).get(2, TimeUnit.SECONDS);
            TvProtocol.Remote.connections.get(1).close();
            Handler.drain(); idle(engine);
            check(result.size() == 1 && result.get(0) != null && !engine.isReconnectingTo(),
                    "candidate disconnected before main-thread save cannot report success");
            savedTrust(context, "192.0.2.1", "disconnected candidate cannot replace persisted address");
            check(engine.isPaired() && "192.0.2.1".equals(engine.host()), "disconnected candidate preserves original engine pairing");
        } finally { shutdown(engine); }
    }

    private static void changedAddressSuperseded() throws Exception {
        Context context = new Context();
        RemoteEngine engine = engine(context);
        CountDownLatch release = null;
        try {
            engine.resume(); idle(engine); idle(engine);
            List<String> first = new ArrayList<>(), second = new ArrayList<>();
            release = gateNextConnection();
            engine.reconnectTo("192.0.2.8", first::add);
            check(TvProtocol.Remote.connectEntered.await(2, TimeUnit.SECONDS), "first candidate check entered");
            engine.reconnectTo("192.0.2.9", second::add);
            release.countDown(); idle(engine); idle(engine);
            check(first.size() == 1 && "Control cancelled".equals(first.get(0))
                    && second.size() == 1 && second.get(0) == null, "latest selected address completes independently of superseded check");
            check(!TvProtocol.Remote.connections.get(1).isOpen() && TvProtocol.Remote.connections.get(2).isOpen()
                    && "192.0.2.9".equals(engine.host()), "stale candidate cannot close or replace latest verified session");
            savedTrust(context, "192.0.2.9", "only latest candidate is persisted");
            Handler.advance(7000); idle(engine);
            check(first.size() == 1 && second.size() == 1 && TvProtocol.Remote.connections.get(2).isOpen(),
                    "old deadlines cannot finish twice or close replacement");
        } finally { if (release != null) release.countDown(); shutdown(engine); }
    }

    private static void changedAddressDeadlines() throws Exception {
        for (int scenario = 0; scenario < 2; scenario++) {
            Context context = new Context();
            RemoteEngine engine = engine(context);
            CountDownLatch release = null;
            try {
                engine.resume(); idle(engine); idle(engine);
                List<String> result = new ArrayList<>();
                if (scenario == 0) release = blockWorker(engine);
                else release = gateNextConnection();
                engine.reconnectTo("192.0.2.9", result::add);
                if (scenario == 1) check(TvProtocol.Remote.connectEntered.await(2, TimeUnit.SECONDS), "timed address check entered");
                Handler.advance(6999);
                check(result.isEmpty() && engine.isReconnectingTo(), "address check keeps its full bounded deadline " + scenario);
                Handler.advance(1);
                check(result.size() == 1 && result.get(0) != null && !engine.isReconnectingTo(), "address check expires once at deadline " + scenario);
                savedTrust(context, "192.0.2.1", "timeout cannot save candidate " + scenario);
                release.countDown(); idle(engine);
                check(result.size() == 1 && "192.0.2.1".equals(engine.host()), "late worker result cannot bypass timeout " + scenario);
                check(TvProtocol.Remote.connections.size() == (scenario == 0 ? 1 : 2)
                        && (scenario == 0 || !TvProtocol.Remote.connections.get(1).isOpen()),
                        "expired queued check never connects and late in-flight candidate closes " + scenario);
                engine.setWidgetSessionActive(true);
                engine.reconnect(result::add); idle(engine);
                check(result.size() == 2 && result.get(1) == null
                        && TvProtocol.Remote.connections.get(TvProtocol.Remote.connections.size() - 1).isOpen(),
                        "original address remains available after recovery timeout " + scenario);
                check(TvProtocol.Pairing.starts == 0 && TvProtocol.Remote.keys.isEmpty(), "timeout never pairs or replays keys " + scenario);
            } finally { if (release != null) release.countDown(); shutdown(engine); }
        }
    }

    private static void changedAddressRejectsConcurrentControls() throws Exception {
        RemoteEngine engine = engine();
        CountDownLatch release = null;
        try {
            engine.resume(); idle(engine); idle(engine);
            engine.setWidgetSessionActive(true);
            TvProtocol.Remote.keys.clear(); TvProtocol.Remote.pictureRequests.clear(); TvProtocol.Remote.pictureToggles.clear();
            List<String> result = new ArrayList<>(), rejected = new ArrayList<>();
            release = gateNextConnection();
            engine.reconnectTo("192.0.2.9", result::add);
            check(TvProtocol.Remote.connectEntered.await(2, TimeUnit.SECONDS), "address check entered before concurrent controls");
            engine.sendWidgetKey(21, rejected::add);
            engine.sendRepeatingKey(22, engine.repeatingSession(), rejected::add);
            engine.reconnect(rejected::add);
            engine.requestPictureState(rejected::add);
            engine.togglePictureMode(rejected::add);
            engine.sendPictureMode(true, rejected::add);
            Handler.drain();
            check(rejected.size() == 6 && rejected.stream().allMatch(value -> value != null),
                    "widget, foreground, Sync and picture requests fail while address is being verified");
            engine.setWidgetSessionActive(false);
            release.countDown(); idle(engine);
            check(result.size() == 1 && result.get(0) == null && "192.0.2.9".equals(engine.host()),
                    "widget Stop does not cancel foreground-owned address check");
            noReconnectCommands("rejected concurrent controls cannot send to old or candidate TV");
            check(TvProtocol.Remote.pictureRequests.isEmpty() && TvProtocol.Remote.connections.size() == 2,
                    "address check sends no picture query and concurrent controls do not create extra connections");
        } finally { if (release != null) release.countDown(); shutdown(engine); }
    }

    private static void changedAddressCancelsOldCommands() throws Exception {
        RemoteEngine engine = engine();
        CountDownLatch release = null;
        try {
            engine.resume(); idle(engine); idle(engine); engine.setWidgetSessionActive(true);
            List<String> previous = new ArrayList<>(), result = new ArrayList<>();
            engine.sendWidgetKey(21, previous::add); idle(engine);
            release = blockWorker(engine);
            engine.sendWidgetKey(22, previous::add);
            engine.reconnectTo("192.0.2.9", result::add);
            release.countDown(); idle(engine); Handler.advance(750);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(21)),
                    "address recovery never replays delivered or queued old-session keys");
            check(previous.size() == 2 && previous.stream().allMatch("Control cancelled"::equals),
                    "old delivery callbacks and queued keys complete cancelled once");
            check(result.size() == 1 && result.get(0) == null && TvProtocol.Remote.connections.get(1).isOpen(),
                    "stale command completions leave new connected TV alive");
        } finally { if (release != null) release.countDown(); shutdown(engine); }
    }

    private static ThreadPoolExecutor worker(RemoteEngine engine) throws Exception {
        Field field = RemoteEngine.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(engine);
    }

    private static void idle(RemoteEngine engine) throws Exception {
        worker(engine).submit(() -> {}).get(2, TimeUnit.SECONDS);
        Handler.drain();
    }

    private static void shutdown(RemoteEngine engine) throws Exception {
        engine.setWidgetSessionActive(false);
        engine.pause();
        worker(engine).shutdownNow();
        Field pictureField = RemoteEngine.class.getDeclaredField("pictureWorker");
        pictureField.setAccessible(true);
        ThreadPoolExecutor pictureWorker = (ThreadPoolExecutor) pictureField.get(engine);
        pictureWorker.shutdownNow();
        check(pictureWorker.awaitTermination(2, TimeUnit.SECONDS), "picture worker terminates");
        check(worker(engine).awaitTermination(2, TimeUnit.SECONDS), "network worker terminates");
    }

    private static CountDownLatch blockWorker(RemoteEngine engine) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker(engine).execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        check(entered.await(2, TimeUnit.SECONDS), "worker blocker entered");
        return release;
    }

    private static void burstAndOwnership() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            List<String> results = new ArrayList<>();
            engine.sendWidgetKey(21, results::add);
            engine.sendWidgetKey(22, results::add);
            idle(engine);
            check(TvProtocol.Remote.keys.size() == 2, "two keys written before first delivery timer");
            check(TvProtocol.Remote.connections.size() == 1, "burst reuses one connection");
            check(results.isEmpty(), "completion waits for dispatch allowance");
            Handler.advance(749);
            check(results.isEmpty(), "delivery allowance does not finish early");
            Handler.advance(1);
            check(results.size() == 2 && results.get(0) == null && results.get(1) == null, "both keys complete once");
            TvProtocol.Remote connection = TvProtocol.Remote.connections.get(0);
            engine.resume(); idle(engine); engine.pause();
            check(connection.isOpen(), "pause preserves widget-owned session");
            engine.resume(); idle(engine); engine.setWidgetSessionActive(false);
            check(connection.isOpen(), "widget stop preserves foreground-owned session");
            engine.pause();
            check(!connection.isOpen(), "last owner closes session");
        } finally { shutdown(engine); }
    }

    private static void repeatingKeysUseForegroundSession() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.resume(); idle(engine);
            BooleanSupplier session = engine.repeatingSession();
            List<String> results = new ArrayList<>();
            engine.sendRepeatingKey(21, session, results::add); idle(engine);
            engine.sendRepeatingKey(21, session, results::add); idle(engine);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(21, 21)),
                    "held app key sends repeated presses without widget ownership");
            check(results.size() == 2 && results.stream().allMatch(error -> error == null),
                    "app repeat completion is immediate without widget delivery allowance");
            check(TvProtocol.Remote.connections.size() == 1,
                    "repeated keys reuse the foreground connection");
            TvProtocol.Remote connection = TvProtocol.Remote.connections.get(0);
            Handler.advance(7000);
            check(results.size() == 2 && connection.isOpen(),
                    "completed repeats leave no active watchdog or duplicate completion");
            engine.pause();
            check(!connection.isOpen(), "repeat completion did not create a widget owner");
        } finally { shutdown(engine); }
    }

    private static void queuedReleaseCancelsRepeat() throws Exception {
        RemoteEngine engine = engine();
        CountDownLatch release = null;
        try {
            engine.resume(); idle(engine);
            BooleanSupplier session = engine.repeatingSession();
            AtomicBoolean firstHeld = new AtomicBoolean(true);
            List<String> old = new ArrayList<>(), next = new ArrayList<>();
            release = blockWorker(engine);
            engine.sendRepeatingKey(21, () -> firstHeld.get() && session.getAsBoolean(), old::add);
            firstHeld.set(false);
            AtomicBoolean nextHeld = new AtomicBoolean(true);
            engine.sendRepeatingKey(22, () -> nextHeld.get() && session.getAsBoolean(), next::add);
            release.countDown(); idle(engine);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(22)),
                    "lifted queued repeat never sends or revives with a new gesture");
            check(old.size() == 1 && "Control cancelled".equals(old.get(0)),
                    "lifted queued repeat completes cancelled once");
            check(next.size() == 1 && next.get(0) == null,
                    "fresh gesture remains usable after the previous queued repeat is cancelled");
            check(TvProtocol.Remote.connections.size() == 1 && TvProtocol.Remote.connections.get(0).isOpen(),
                    "gesture cancellation preserves the shared connection");
        } finally {
            if (release != null) release.countDown();
            shutdown(engine);
        }
    }

    private static void repeatingKeysNeverReconnect() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.resume(); idle(engine);
            BooleanSupplier session = engine.repeatingSession();
            TvProtocol.Remote.connections.get(0).close();
            List<String> results = new ArrayList<>();
            engine.sendRepeatingKey(21, session, results::add); idle(engine);
            check(TvProtocol.Remote.connections.size() == 1 && TvProtocol.Remote.keys.isEmpty(),
                    "repeat on a disconnected session does not reconnect or send");
            check(results.size() == 1 && results.get(0) != null,
                    "disconnected repeat reports one failure to stop the gesture");
            engine.sendKey(22); idle(engine);
            check(TvProtocol.Remote.connections.size() == 2
                    && TvProtocol.Remote.keys.equals(java.util.Arrays.asList(22)),
                    "a later deliberate tap may reconnect without replaying the failed repeat");
        } finally { shutdown(engine); }
    }

    private static void repeatingSessionEndsAtSync() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.resume(); idle(engine);
            BooleanSupplier original = engine.repeatingSession();
            TvProtocol.Remote first = TvProtocol.Remote.connections.get(0);
            engine.reconnect(); idle(engine);
            check(!original.getAsBoolean() && !first.isOpen(),
                    "Sync invalidates a gesture even after the replacement is connected");
            List<String> old = new ArrayList<>(), next = new ArrayList<>();
            engine.sendRepeatingKey(21, original, old::add);
            engine.sendRepeatingKey(22, engine.repeatingSession(), next::add);
            idle(engine);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(22)),
                    "original gesture cannot repeat into Sync's replacement session");
            check(old.size() == 1 && "Control cancelled".equals(old.get(0))
                    && next.size() == 1 && next.get(0) == null,
                    "stale and new gestures complete independently after Sync");
            check(TvProtocol.Remote.connections.size() == 2 && TvProtocol.Remote.connections.get(1).isOpen(),
                    "stale repeat does not close or replace the new connection");
        } finally { shutdown(engine); }
    }

    private static void repeatingKeysFollowForegroundLifetime() throws Exception {
        RemoteEngine engine = engine();
        CountDownLatch release = null;
        try {
            engine.resume(); idle(engine);
            engine.setWidgetSessionActive(true);
            TvProtocol.Remote connection = TvProtocol.Remote.connections.get(0);
            BooleanSupplier original = engine.repeatingSession();
            List<String> old = new ArrayList<>(), next = new ArrayList<>();
            release = blockWorker(engine);
            engine.sendRepeatingKey(21, original, old::add);
            engine.pause();
            check(!original.getAsBoolean() && connection.isOpen(),
                    "foreground pause stops held gesture while widget keeps the connection");
            engine.resume();
            engine.sendRepeatingKey(22, engine.repeatingSession(), next::add);
            release.countDown(); idle(engine);
            check(!original.getAsBoolean() && TvProtocol.Remote.keys.equals(java.util.Arrays.asList(22)),
                    "pause/resume cannot revive a queued repeat on the retained socket");
            check(old.size() == 1 && "Control cancelled".equals(old.get(0))
                    && next.size() == 1 && next.get(0) == null,
                    "resumed foreground gesture works without reviving old app ownership");
            check(TvProtocol.Remote.connections.size() == 1 && connection.isOpen(),
                    "foreground repeat lifecycle leaves widget connection intact");
        } finally {
            if (release != null) release.countDown();
            shutdown(engine);
        }
    }

    private static void lateRepeatCompletionCannotReportSuccessAfterPause() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.resume(); idle(engine);
            engine.setWidgetSessionActive(true);
            List<String> results = new ArrayList<>();
            engine.sendRepeatingKey(21, engine.repeatingSession(), results::add);
            // Finish the write without draining the main-thread completion queue.
            worker(engine).submit(() -> {}).get(2, TimeUnit.SECONDS);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(21)) && results.isEmpty(),
                    "repeat was written while its main-thread completion is still pending");
            engine.pause(); engine.resume(); idle(engine);
            check(results.size() == 1 && "Control cancelled".equals(results.get(0)),
                    "late repeat callback cannot report success for an old foreground lifetime");
            check(TvProtocol.Remote.connections.size() == 1 && TvProtocol.Remote.connections.get(0).isOpen(),
                    "late cancelled callback preserves the retained session");
        } finally { shutdown(engine); }
    }

    private static void pictureAndInputCommands() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            List<String> results = new ArrayList<>();
            engine.sendPictureMode(false, results::add);
            engine.sendPictureMode(true, results::add);
            engine.sendWidgetKey(178, results::add);
            idle(engine);
            check(TvProtocol.Remote.modes.equals(java.util.Arrays.asList(false, true)), "explicit Day then Night preserved");
            check(TvProtocol.Remote.keys.isEmpty() && TvProtocol.Remote.inputs.size() == 1, "input uses one native-picker request, not a key macro");
            check(TvProtocol.Remote.connections.size() == 1, "picture modes and input share session");
            check(results.isEmpty(), "picture and input completion preserve delivery allowance");
            Handler.advance(750);
            check(results.size() == 3 && results.stream().allMatch(error -> error == null), "picture and input complete once");
            CountDownLatch release = blockWorker(engine);
            engine.sendPictureMode(true, results::add);
            engine.setWidgetSessionActive(false);
            release.countDown(); idle(engine);
            check(TvProtocol.Remote.modes.size() == 2, "stopped widget cannot send stale picture request");
        } finally { shutdown(engine); }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Handler.drain();
            Thread.sleep(1);
        }
        Handler.drain();
        check(condition.getAsBoolean(), "asynchronous picture result arrives");
    }

    private static void confirmedPictureCallbacks() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            TvProtocol.Remote.holdPictureReplies = true;
            List<String> results = new ArrayList<>();
            engine.togglePictureMode(results::add); idle(engine);
            check(TvProtocol.Remote.pictureToggles.equals(java.util.Arrays.asList(true)), "one toggle request sent");
            check("unknown".equals(engine.pictureState()) && results.isEmpty(), "sending does not invent confirmed state");
            engine.sendWidgetKey(22, error -> {}); idle(engine);
            check(TvProtocol.Remote.keys.equals(java.util.Arrays.asList(22)), "reply wait does not hold normal key queue");
            TvProtocol.Remote.pictureRequests.get(0).reply("night");
            waitFor(() -> results.size() == 1);
            check(results.get(0) == null && "night".equals(engine.pictureState()), "authenticated result confirms Night");
            check(TvProtocol.Remote.pictureRequests.get(0).closed, "confirmed callback socket closes");
        } finally { shutdown(engine); }
    }

    private static void pictureCancellationAndOrdering() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            TvProtocol.Remote.holdPictureReplies = true;
            List<String> older = new ArrayList<>(), newer = new ArrayList<>();
            engine.requestPictureState(older::add); idle(engine);
            engine.togglePictureMode(newer::add); idle(engine);
            TvProtocol.Remote.pictureRequests.get(1).reply("night");
            waitFor(() -> newer.size() == 1);
            TvProtocol.Remote.pictureRequests.get(0).reply("day");
            waitFor(() -> older.size() == 1);
            check("night".equals(engine.pictureState()), "late older reply cannot overwrite newer snapshot");
            engine.togglePictureMode(older::add); idle(engine);
            PictureStateClient cancelled = TvProtocol.Remote.pictureRequests.get(2);
            engine.setWidgetSessionActive(false); Handler.drain();
            check(cancelled.closed, "widget Stop closes pending UDP listener immediately");
            cancelled.reply("day");
            waitFor(() -> older.size() == 2);
            check("Control cancelled".equals(older.get(1)), "stopped request completes cancelled once");
            check("night".equals(engine.pictureState()), "cancelled response does not alter saved confirmation");
            engine.setWidgetSessionActive(true);
            engine.requestPictureState(newer::add); idle(engine);
            PictureStateClient oldGeneration = TvProtocol.Remote.pictureRequests.get(3);
            engine.reconnect(error -> {}); idle(engine);
            check(oldGeneration.closed, "Sync closes old generation callback socket");
            oldGeneration.reply("day"); Handler.drain();
            check("night".equals(engine.pictureState()), "old generation cannot publish late state");
        } finally { shutdown(engine); }
    }

    private static void pictureErrorsAndScopedCache() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            List<String> results = new ArrayList<>();
            engine.togglePictureMode(results::add); idle(engine);
            waitFor(() -> results.size() == 1);
            check("night".equals(engine.pictureState()), "cache accepts confirmed snapshot");
            TvProtocol.Remote.holdPictureReplies = true;
            engine.requestPictureState(results::add); idle(engine);
            TvProtocol.Remote.pictureRequests.get(1).reply("unavailable");
            waitFor(() -> results.size() == 2);
            check(results.get(1) != null && "unknown".equals(engine.pictureState()), "unavailable reply invalidates cached mode");
            engine.togglePictureMode(results::add); idle(engine);
            PictureStateClient timedOut = TvProtocol.Remote.pictureRequests.get(2);
            Handler.advance(7000);
            waitFor(() -> results.size() == 3);
            check(timedOut.closed && results.get(2) != null, "deadline closes listener and completes once");
            check(TvProtocol.Remote.pictureToggles.stream().filter(value -> value).count() == 2,
                    "timed-out toggle is never repeated");
            TvProtocol.Remote.unsupportedPicture = true;
            List<String> sync = new ArrayList<>();
            engine.reconnect(sync::add); idle(engine);
            check(sync.size() == 1 && sync.get(0) == null, "optional state query does not break Sync success");
            engine.sendWidgetKey(22, error -> {}); idle(engine);
            check(TvProtocol.Remote.keys.size() == 1, "unsupported picture API does not disable regular keys");
            engine.forget(); Handler.drain();
            check("unknown".equals(engine.pictureState()), "Forget clears confirmed mode");
        } finally { shutdown(engine); }
    }

    private static void persistedPictureScope() throws Exception {
        Handler.reset(); TvProtocol.Remote.reset();
        Context context = new Context();
        Constructor<RemoteEngine> constructor = RemoteEngine.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        RemoteEngine original = constructor.newInstance(context);
        try {
            original.setWidgetSessionActive(true);
            List<String> result = new ArrayList<>();
            original.togglePictureMode(result::add); idle(original);
            waitFor(() -> result.size() == 1);
            check("night".equals(original.pictureState()), "confirmed snapshot saved before process restart");
        } finally { shutdown(original); }
        RemoteEngine restored = constructor.newInstance(context);
        try { check("night".equals(restored.pictureState()), "same IP and pinned TV restores last confirmation"); }
        finally { shutdown(restored); }
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("certificate", "Ag==").apply();
        RemoteEngine differentPin = constructor.newInstance(context);
        try { check("unknown".equals(differentPin.pictureState()), "changed TV pin cannot inherit snapshot"); }
        finally { shutdown(differentPin); }
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("certificate", "AQ==")
                .putString("host", "192.0.2.2").apply();
        RemoteEngine differentHost = constructor.newInstance(context);
        try { check("unknown".equals(differentHost.pictureState()), "changed TV address cannot inherit snapshot"); }
        finally { shutdown(differentHost); }
    }

    private static void queuedTimeout() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            engine.sendWidgetKey(21, error -> {}); idle(engine); Handler.advance(750);
            TvProtocol.Remote connection = TvProtocol.Remote.connections.get(0);
            CountDownLatch release = blockWorker(engine);
            List<String> results = new ArrayList<>();
            engine.sendWidgetKey(22, results::add);
            Handler.advance(7000);
            check(results.size() == 1 && results.get(0) != null, "queued request expires once");
            check(connection.isOpen(), "queued timeout cannot close shared connection");
            release.countDown(); idle(engine);
            check(TvProtocol.Remote.keys.size() == 1, "expired queued key never sends");
            check(results.size() == 1, "worker cannot complete expired request twice");
        } finally { shutdown(engine); }
    }

    private static void insufficientDeliveryBudget() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            engine.sendWidgetKey(21, error -> {}); idle(engine); Handler.advance(750);
            TvProtocol.Remote connection = TvProtocol.Remote.connections.get(0);
            CountDownLatch release = blockWorker(engine);
            List<String> results = new ArrayList<>();
            engine.sendWidgetKey(22, results::add);
            Handler.advance(6300); release.countDown(); idle(engine);
            check(TvProtocol.Remote.keys.size() == 1, "less than 750 ms remaining rejects before write");
            check(connection.isOpen(), "pre-write expiry preserves shared connection");
            check(results.size() == 1 && results.get(0) != null, "pre-write expiry reports failure once");
        } finally { shutdown(engine); }
    }

    private static void syncCancelsOldCommands() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            List<String> old = new ArrayList<>(), sync = new ArrayList<>(), next = new ArrayList<>();
            engine.sendWidgetKey(21, old::add); idle(engine);
            TvProtocol.Remote first = TvProtocol.Remote.connections.get(0);
            engine.reconnect(sync::add); idle(engine);
            engine.sendWidgetKey(22, next::add); idle(engine); Handler.advance(750);
            check(!first.isOpen() && TvProtocol.Remote.connections.size() == 2, "Sync replaces session");
            check(old.size() == 1 && "Control cancelled".equals(old.get(0)), "old grace callback is cancelled");
            check(sync.size() == 1 && sync.get(0) == null, "Sync completes once");
            check(next.size() == 1 && next.get(0) == null, "replacement session remains usable");
            check(engine.isPaired() && "192.0.2.1".equals(engine.host()), "Sync preserves pairing");
            engine.setWidgetSessionActive(false);
            check(!TvProtocol.Remote.connections.get(1).isOpen(), "widget Sync did not set foreground flag");
        } finally { shutdown(engine); }
    }

    private static void stopThenRestart() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            CountDownLatch release = blockWorker(engine);
            List<String> old = new ArrayList<>(), next = new ArrayList<>();
            engine.sendWidgetKey(21, old::add);
            engine.setWidgetSessionActive(false); engine.setWidgetSessionActive(false);
            engine.setWidgetSessionActive(true);
            engine.sendWidgetKey(22, next::add);
            release.countDown(); idle(engine); Handler.advance(750);
            check(TvProtocol.Remote.keys.size() == 1 && TvProtocol.Remote.keys.get(0) == 22, "Stop/restart cannot revive old key");
            check(old.size() == 1 && "Control cancelled".equals(old.get(0)), "stopped request completes cancelled");
            check(next.size() == 1 && next.get(0) == null, "new widget session works");
        } finally { shutdown(engine); }
    }

    private static void pauseThenResume() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.resume(); idle(engine);
            CountDownLatch release = blockWorker(engine);
            engine.sendKey(21);
            engine.pause(); engine.resume(); engine.sendKey(22);
            release.countDown(); idle(engine);
            check(TvProtocol.Remote.keys.size() == 1 && TvProtocol.Remote.keys.get(0) == 22, "pause/resume cannot revive old app key");
        } finally { shutdown(engine); }
    }

    private static void syncDuringConnect() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            CountDownLatch release = new CountDownLatch(1);
            TvProtocol.Remote.connectGate = release;
            TvProtocol.Remote.connectEntered = new CountDownLatch(1);
            List<String> old = new ArrayList<>(), sync = new ArrayList<>();
            engine.sendWidgetKey(21, old::add);
            check(TvProtocol.Remote.connectEntered.await(2, TimeUnit.SECONDS), "old connect entered");
            engine.reconnect(sync::add); release.countDown(); idle(engine);
            check(TvProtocol.Remote.keys.isEmpty(), "Sync cancels key pending connection");
            check(TvProtocol.Remote.connections.size() == 2 && !TvProtocol.Remote.connections.get(0).isOpen(), "stale connected socket is discarded");
            check(TvProtocol.Remote.connections.get(1).isOpen(), "fresh Sync socket survives old work");
            check(old.size() == 1 && old.get(0) != null && sync.size() == 1 && sync.get(0) == null, "old and new operations complete once");
        } finally { shutdown(engine); }
    }

    private static void blockedWriteTimeout() throws Exception {
        RemoteEngine engine = engine();
        try {
            engine.setWidgetSessionActive(true);
            TvProtocol.Remote.blockWrite = true;
            TvProtocol.Remote.writeEntered = new CountDownLatch(1);
            List<String> failed = new ArrayList<>(), next = new ArrayList<>();
            engine.sendWidgetKey(21, failed::add);
            check(TvProtocol.Remote.writeEntered.await(2, TimeUnit.SECONDS), "write entered");
            Handler.advance(7000); idle(engine);
            check(!TvProtocol.Remote.connections.get(0).isOpen(), "watchdog aborts its blocked write");
            check(failed.size() == 1 && failed.get(0) != null, "blocked write completes once");
            engine.sendWidgetKey(22, next::add); idle(engine); Handler.advance(750);
            check(TvProtocol.Remote.keys.size() == 1 && TvProtocol.Remote.keys.get(0) == 22, "uncertain key is not retried");
            check(TvProtocol.Remote.connections.get(1).isOpen(), "later command gets a fresh live connection");
        } finally { shutdown(engine); }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
