package uy.nico.bareremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Emulator-only native lifecycle test; the host verifies that no app link reaches its fake TV. */
public final class PassiveOpenInstrumentation extends Instrumentation {
    private int assertions;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("ranchu".equals(android.os.Build.HARDWARE), "requires the explicitly selected emulator");
            // The isolated emulator must never contact a previously saved physical TV.
            check(getTargetContext().getSharedPreferences("remote", 0).edit().clear().commit(), "clear test preferences");
            check(getTargetContext().getSharedPreferences("ui", 0).edit()
                    .putBoolean("widget_notifications_requested", true).commit(), "keep unrelated notification prompts outside this test");
            Activity activity = open();
            RemoteEngine engine = RemoteEngine.get(getTargetContext());
            java.io.File code = new java.io.File(getTargetContext().getExternalFilesDir(null), "passive-test-code");
            code.delete();
            runOnMainSync(() -> engine.startPairing("10.0.2.2"));
            waitFor(code::isFile, 60000, "host supplies the ephemeral loopback code");
            String value = new String(Files.readAllBytes(code.toPath()), StandardCharsets.US_ASCII).trim();
            code.delete();
            check(value.matches("[0-9A-Fa-f]{6}"), "valid loopback pairing code");
            runOnMainSync(() -> engine.finishPairing(value));
            connected(engine);
            closeSettings(activity);
            check("10.0.2.2".equals(engine.host()) && engine.isPaired(), "native Keystore pairing uses only loopback");
            for (int attempt = 0; attempt < 2; attempt++) {
                final Activity previous = activity;
                runOnMainSync(previous::finish);
                waitForIdleSync();
                activity = open();
                connected(engine);
                check("unknown".equals(engine.dogMode()), "reopen does not invent a fresh Dog Mode state");
                click(activity.getWindow().getDecorView(), "Setup");
                final AlertDialog dialog = settings(activity);
                check(dialog != null && dialog.isShowing(), "native Settings opens");
                check("unknown".equals(engine.dogMode()), "opening Settings remains passive");
                View reconnect = findText(dialog.getWindow().getDecorView(), "Reconnect");
                check(reconnect != null, "Settings reconnect exists");
                runOnMainSync(() -> check(reconnect.performClick(), "Settings reconnect click"));
                connected(engine);
                closeSettings(activity);
                click(activity.getWindow().getDecorView(), "Sync: reconnect to saved TV");
                connected(engine);
            }
            java.io.File keyAck = new java.io.File(getTargetContext().getExternalFilesDir(null), "passive-test-key-applied");
            keyAck.delete();
            runOnMainSync(() -> engine.sendKey(19));
            idle(engine);
            waitFor(keyAck::isFile, 5000, "fake TV applied the explicit key before the session ends");
            keyAck.delete();
            result.putString("stream", "Native passive open: " + assertions + " assertions passed\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(0, result);
        }
    }
    private Activity open() {
        return startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(
                "uy.nico.bareremote", "uy.nico.bareremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void connected(RemoteEngine engine) throws Exception {
        waitFor(() -> "Connected".equals(engine.status()) && engine.keyboardState().supported, 10000, "pinned native session ready");
        idle(engine);
        android.os.SystemClock.sleep(350); // The independent fixture delays readiness for real key delivery.
    }
    private void idle(RemoteEngine engine) throws Exception {
        Field field = RemoteEngine.class.getDeclaredField("worker"); field.setAccessible(true);
        CountDownLatch done = new CountDownLatch(1);
        ((ThreadPoolExecutor) field.get(engine)).execute(done::countDown);
        check(done.await(5, TimeUnit.SECONDS), "native network queue completes");
        waitForIdleSync();
    }
    private AlertDialog settings(Activity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("setupDialog"); field.setAccessible(true);
        return (AlertDialog) field.get(activity);
    }
    private void closeSettings(Activity activity) throws Exception {
        AlertDialog dialog = settings(activity);
        if (dialog != null) runOnMainSync(() -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        waitForIdleSync();
    }
    private void click(View root, String description) {
        View target = find(root, description);
        check(target != null && target.isShown() && target.isEnabled(), "visible " + description);
        runOnMainSync(() -> check(target.performClick(), "click " + description));
        waitForIdleSync();
    }
    private View find(View node, String description) {
        if (description.equals(String.valueOf(node.getContentDescription()))) return node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
            View child = find(((ViewGroup) node).getChildAt(i), description);
            if (child != null) return child;
        }
        return null;
    }
    private View findText(View node, String text) {
        if (node instanceof TextView && text.contentEquals(((TextView) node).getText()) && node.isShown() && node.isEnabled()) return node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
            View child = findText(((ViewGroup) node).getChildAt(i), text);
            if (child != null) return child;
        }
        return null;
    }
    private void waitFor(java.util.function.BooleanSupplier condition, long timeout, String message) {
        long end = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(25);
        check(condition.getAsBoolean(), message);
    }
    private void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
