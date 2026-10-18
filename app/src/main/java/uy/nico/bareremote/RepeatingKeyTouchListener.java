package uy.nico.bareremote;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One immediate click, then bounded repeats while this exact touch remains held. */
final class RepeatingKeyTouchListener implements View.OnTouchListener {
    static final long HOLD_DELAY_MS = 400;
    static final long REPEAT_DELAY_MS = 120;
    interface Sender { void send(BooleanSupplier held, RemoteEngine.Completion completion); }

    private final View button;
    private final Supplier<Sender> start;
    private final Consumer<String> error;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int slop;
    private AtomicBoolean held;
    private Runnable repeat;

    RepeatingKeyTouchListener(View button, Supplier<Sender> start, Consumer<String> error) {
        this.button = button;
        this.start = start;
        this.error = error;
        slop = ViewConfiguration.get(button.getContext()).getScaledTouchSlop();
        button.setOnTouchListener(this);
        // The native tooltip consumes a long press before a click can be sent.
        button.setTooltipText(null);
        button.setLongClickable(false);
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        if (!button.isEnabled()) { cancel(); return false; }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();
                AtomicBoolean gesture = new AtomicBoolean(true);
                held = gesture;
                Sender sender = start.get();
                button.setPressed(true);
                button.getParent().requestDisallowInterceptTouchEvent(true);
                button.performClick();
                repeat = () -> sendRepeat(gesture, sender);
                main.postDelayed(repeat, HOLD_DELAY_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getX() < -slop || event.getY() < -slop
                        || event.getX() >= button.getWidth() + slop
                        || event.getY() >= button.getHeight() + slop) cancel();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                cancel();
                break;
            default:
                break;
        }
        return true;
    }

    private void sendRepeat(AtomicBoolean gesture, Sender sender) {
        if (!gesture.get()) return;
        if (!button.isEnabled() || !button.isShown() || !button.hasWindowFocus()) {
            cancel();
            return;
        }
        // Schedule the next repeat only after this one finishes. A slow TV cannot
        // accumulate a backlog that keeps navigating after the finger is lifted.
        sender.send(gesture::get, failure -> {
            if (!gesture.get()) return;
            if (failure != null) {
                cancel();
                if (!"Control cancelled".equals(failure)) error.accept(failure);
            } else main.postDelayed(repeat, REPEAT_DELAY_MS);
        });
    }

    void cancel() {
        if (held != null) held.set(false);
        held = null;
        if (repeat != null) main.removeCallbacks(repeat);
        repeat = null;
        button.setPressed(false);
        if (button.getParent() != null) button.getParent().requestDisallowInterceptTouchEvent(false);
    }
}
