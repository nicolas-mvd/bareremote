package uy.nico.bareremote;

import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Deterministic gesture regressions; no Android device or network access. */
public final class RepeatTouchTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        immediateTapAndAccessibilityClick();
        holdPacingAndBackpressure();
        synchronousCompletion();
        touchCancellation();
        movingOutsideCancels();
        explicitAndDetachedCancellation();
        freshGestureIgnoresOldCompletion();
        invalidViewCancels();
        repeatFailureStopsGesture();
        heldPredicateVisibleToWorker();
        System.out.println("Repeat touch tests: " + assertions + " assertions passed");
    }

    private static void immediateTapAndAccessibilityClick() {
        Fixture f = new Fixture();
        check(f.button.getTooltipText() == null && !f.button.isLongClickable(),
                "native tooltip cannot consume the held touch");
        check(f.touch(MotionEvent.ACTION_DOWN), "touch down is consumed");
        check(f.clicks == 1 && f.order.equals(Arrays.asList("start", "click")),
                "touch immediately clicks after capturing its sender");
        check(f.button.isPressed() && f.parent.disallowed, "down keeps button pressed and owns touch");
        Handler.advance(399);
        check(f.sends() == 0, "short hold sends no repeat before 400 ms");
        check(f.touch(MotionEvent.ACTION_UP), "release is consumed to avoid a native duplicate click");
        Handler.advance(1000);
        check(f.clicks == 1 && f.sends() == 0, "short tap sends exactly its immediate click");
        check(!f.button.isPressed() && !f.parent.disallowed, "release clears pressed state and interception");

        f = new Fixture();
        f.button.performClick();
        Handler.advance(1000);
        check(f.clicks == 1 && f.senders.isEmpty(), "accessibility click remains a single ordinary click");
    }

    private static void holdPacingAndBackpressure() {
        Fixture f = new Fixture();
        f.touch(MotionEvent.ACTION_DOWN);
        f.destination = "changed after down";
        Handler.advance(400);
        Attempt first = f.latest();
        check(f.sends() == 1 && first.at == 400 && first.held.getAsBoolean(),
                "first repeat begins after 400 ms while still held");
        check("initial destination".equals(f.senders.get(0).destination),
                "repeats retain the sender captured for this gesture");
        Handler.advance(2000);
        check(f.sends() == 1, "slow completion cannot accumulate repeat commands");
        first.completion.done(null);
        Handler.advance(119);
        check(f.sends() == 1, "next repeat waits 120 ms after completion");
        Handler.advance(1);
        check(f.sends() == 2 && f.latest().at == 2520, "successful repeat resumes at completion plus 120 ms");
        f.latest().completion.done(null);
        Handler.advance(120);
        check(f.sends() == 3 && f.clicks == 1, "repeats use their sender without extra native clicks");
        Attempt pending = f.latest();
        f.touch(MotionEvent.ACTION_UP);
        check(!first.held.getAsBoolean() && !pending.held.getAsBoolean(),
                "release invalidates every queued operation from the exact held gesture");
        pending.completion.done(null);
        Handler.advance(1000);
        check(f.sends() == 3 && f.clicks == 1, "late completion after release sends nothing else");
    }

    private static void synchronousCompletion() {
        Fixture f = new Fixture();
        f.completeImmediately = true;
        f.touch(MotionEvent.ACTION_DOWN);
        Handler.advance(760);
        check(f.sends() == 4, "immediate completions produce repeats at 400, 520, 640, and 760 ms");
        f.touch(MotionEvent.ACTION_UP);
        Handler.advance(1000);
        check(f.sends() == 4, "release removes the next already scheduled repeat");
    }

    private static void touchCancellation() {
        int[] endings = {MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_DOWN | 0x100, MotionEvent.ACTION_POINTER_UP | 0x100};
        for (int ending : endings) {
            Fixture f = new Fixture();
            f.touch(MotionEvent.ACTION_DOWN);
            Handler.advance(400);
            Attempt pending = f.latest();
            check(f.touch(ending), "gesture ending is consumed: " + ending);
            check(!pending.held.getAsBoolean() && !f.button.isPressed() && !f.parent.disallowed,
                    "release, cancel, or multiple fingers cancel the captured gesture: " + ending);
            pending.completion.done(null);
            Handler.advance(1000);
            check(f.sends() == 1 && f.clicks == 1, "cancelled touch cannot send or click again: " + ending);
        }
    }

    private static void movingOutsideCancels() {
        Fixture f = new Fixture();
        f.touch(MotionEvent.ACTION_DOWN);
        f.touch(MotionEvent.ACTION_MOVE, -8, 50);
        f.touch(MotionEvent.ACTION_MOVE, 107.9f, 50);
        Handler.advance(400);
        check(f.sends() == 1, "movement within touch slop keeps the hold active");
        Attempt pending = f.latest();
        f.touch(MotionEvent.ACTION_MOVE, 108, 50);
        f.touch(MotionEvent.ACTION_MOVE, 50, 50);
        pending.completion.done(null);
        Handler.advance(1000);
        check(!pending.held.getAsBoolean() && f.sends() == 1 && !f.button.isPressed(),
                "leaving right edge cancels even if finger moves back inside");

        float[][] outside = {{-8.1f, 50}, {50, -8.1f}, {50, 108}};
        for (float[] point : outside) {
            f = new Fixture();
            f.touch(MotionEvent.ACTION_DOWN);
            f.touch(MotionEvent.ACTION_MOVE, point[0], point[1]);
            Handler.advance(1000);
            check(f.sends() == 0 && !f.button.isPressed() && !f.parent.disallowed,
                    "leaving another edge cancels before first repeat: " + Arrays.toString(point));
        }
    }

    private static void explicitAndDetachedCancellation() {
        Fixture f = new Fixture();
        f.touch(MotionEvent.ACTION_DOWN);
        Handler.advance(400);
        Attempt pending = f.latest();
        f.button.setParent(null);
        f.listener.cancel();
        f.listener.cancel();
        pending.completion.done("TV unavailable");
        Handler.advance(1000);
        check(!pending.held.getAsBoolean() && !f.button.isPressed() && f.sends() == 1,
                "explicit lifecycle cancellation is idempotent after detachment");
        check(f.errors.isEmpty(), "completion from cancelled lifecycle does not surface stale errors");
    }

    private static void freshGestureIgnoresOldCompletion() {
        for (String result : new String[] {null, "TV unavailable"}) {
            for (boolean releaseFirst : new boolean[] {false, true}) {
                Fixture f = new Fixture();
                f.touch(MotionEvent.ACTION_DOWN);
                Handler.advance(400);
                Attempt old = f.latest();
                if (releaseFirst) f.touch(MotionEvent.ACTION_UP);
                f.destination = "new destination";
                f.touch(MotionEvent.ACTION_DOWN);
                old.completion.done(result);
                check(f.clicks == 2 && f.senders.size() == 2 && f.button.isPressed(),
                        "old completion cannot stop or duplicate a new gesture");
                check(f.errors.isEmpty() && !old.held.getAsBoolean(), "old gesture stays cancelled without stale errors");
                Handler.advance(399);
                check(f.sends() == 1, "old completion cannot accelerate the new gesture");
                Handler.advance(1);
                check(f.sends() == 2 && f.senders.get(0).attempts.size() == 1
                                && f.senders.get(1).attempts.size() == 1
                                && "new destination".equals(f.senders.get(1).destination),
                        "new gesture repeats only through its freshly captured sender");
                f.latest().completion.done(null);
                Handler.advance(120);
                check(f.senders.get(1).attempts.size() == 2 && f.latest().held.getAsBoolean(),
                        "new gesture retains its normal repeat cadence after old callback");
                f.listener.cancel();
            }
        }
    }

    private static void invalidViewCancels() {
        for (int condition = 0; condition < 3; condition++) {
            for (boolean inFlight : new boolean[] {false, true}) {
                Fixture f = new Fixture();
                f.touch(MotionEvent.ACTION_DOWN);
                Attempt pending = null;
                if (inFlight) { Handler.advance(400); pending = f.latest(); }
                setAvailable(f.button, condition, false);
                if (pending != null) pending.completion.done(null);
                Handler.advance(inFlight ? 120 : 400);
                check(f.sends() == (inFlight ? 1 : 0) && !f.button.isPressed() && !f.parent.disallowed,
                        "disabled, hidden, or unfocused control cancels repeat: " + condition + "/" + inFlight);
                if (pending != null) check(!pending.held.getAsBoolean(), "invalid control invalidates queued predicate");
                setAvailable(f.button, condition, true);
                Handler.advance(1000);
                check(f.sends() == (inFlight ? 1 : 0), "restoring view cannot revive the old gesture");
            }
        }
        Fixture f = new Fixture();
        f.touch(MotionEvent.ACTION_DOWN);
        f.button.setEnabled(false);
        f.touch(MotionEvent.ACTION_UP);
        Handler.advance(1000);
        check(f.clicks == 1 && f.sends() == 0 && !f.button.isPressed(),
                "disabled release cancels without a fallback native click");
        f = new Fixture();
        f.button.setEnabled(false);
        f.touch(MotionEvent.ACTION_DOWN);
        f.touch(MotionEvent.ACTION_UP);
        Handler.advance(1000);
        check(f.clicks == 0 && f.senders.isEmpty(), "disabled control cannot start a gesture or click");
    }

    private static void setAvailable(View button, int condition, boolean value) {
        if (condition == 0) button.setEnabled(value);
        else if (condition == 1) button.setShown(value);
        else button.setWindowFocus(value);
    }

    private static void repeatFailureStopsGesture() {
        for (String failure : new String[] {"TV unavailable", "Control cancelled"}) {
            Fixture f = new Fixture();
            f.touch(MotionEvent.ACTION_DOWN);
            Handler.advance(400);
            Attempt failed = f.latest();
            failed.completion.done(failure);
            Handler.advance(1000);
            check(!failed.held.getAsBoolean() && f.sends() == 1 && !f.button.isPressed(),
                    "failed repeat stops the current gesture without retrying");
            check(f.errors.equals("Control cancelled".equals(failure)
                            ? new ArrayList<String>() : Arrays.asList(failure)),
                    "only an actionable repeat failure is reported");
        }
    }

    private static void heldPredicateVisibleToWorker() throws Exception {
        Fixture f = new Fixture();
        f.touch(MotionEvent.ACTION_DOWN);
        Handler.advance(400);
        BooleanSupplier held = f.latest().held;
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean before = new AtomicBoolean(), after = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            before.set(held.getAsBoolean());
            waiting.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            after.set(held.getAsBoolean());
        });
        worker.start();
        check(waiting.await(2, TimeUnit.SECONDS), "worker receives the held predicate");
        f.touch(MotionEvent.ACTION_UP);
        release.countDown();
        worker.join(2000);
        check(!worker.isAlive() && before.get() && !after.get(),
                "network worker sees release through its captured held predicate");
    }

    private static final class Parent implements ViewParent {
        boolean disallowed;
        @Override public void requestDisallowInterceptTouchEvent(boolean value) { disallowed=value; }
    }

    private static final class Attempt {
        final BooleanSupplier held;
        final RemoteEngine.Completion completion;
        final long at = Handler.now();
        Attempt(BooleanSupplier held, RemoteEngine.Completion completion) {
            this.held=held; this.completion=completion;
        }
    }

    private static final class RecordingSender implements RepeatingKeyTouchListener.Sender {
        final String destination;
        final boolean completeImmediately;
        final List<Attempt> attempts = new ArrayList<>();
        RecordingSender(String destination, boolean completeImmediately) {
            this.destination=destination; this.completeImmediately=completeImmediately;
        }
        @Override public void send(BooleanSupplier held, RemoteEngine.Completion completion) {
            attempts.add(new Attempt(held, completion));
            if (completeImmediately) completion.done(null);
        }
    }

    private static final class Fixture {
        final View button = new View();
        final Parent parent = new Parent();
        final List<RecordingSender> senders = new ArrayList<>();
        final List<String> errors = new ArrayList<>(), order = new ArrayList<>();
        final RepeatingKeyTouchListener listener;
        int clicks;
        String destination = "initial destination";
        boolean completeImmediately;
        Fixture() {
            Handler.reset();
            button.setParent(parent);
            button.setOnClickListener(view -> { clicks++; order.add("click"); });
            listener = new RepeatingKeyTouchListener(button, () -> {
                order.add("start");
                RecordingSender sender = new RecordingSender(destination, completeImmediately);
                senders.add(sender);
                return sender;
            }, errors::add);
        }
        boolean touch(int action) { return touch(action, 50, 50); }
        boolean touch(int action, float x, float y) {
            return button.dispatchTouchEvent(new MotionEvent(action,x,y));
        }
        int sends() { return senders.stream().mapToInt(sender -> sender.attempts.size()).sum(); }
        Attempt latest() {
            List<Attempt> attempts = senders.get(senders.size()-1).attempts;
            return attempts.get(attempts.size()-1);
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
