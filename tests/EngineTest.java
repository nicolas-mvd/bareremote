package uy.nico.bareremote;

import android.content.Context;
import android.os.Handler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Deterministic engine regression tests; no Android device or network access. */
public final class EngineTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        burstAndOwnership();
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
        System.out.println("Engine tests: " + assertions + " assertions passed");
    }

    private static RemoteEngine engine() throws Exception {
        Handler.reset();
        TvProtocol.Remote.reset();
        Constructor<RemoteEngine> constructor = RemoteEngine.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Context());
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
