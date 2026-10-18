"""Exercise the real touch repeater with deterministic Android UI doubles.

This verifies gesture ownership and repeat scheduling without a device. Native
touch dispatch and launcher behavior still require emulator/device checks.
"""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/Context.java": """
package android.content;
public class Context {}
""",
    "android/os/Looper.java": """
package android.os;
public class Looper {
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
}
""",
    "android/os/Handler.java": """
package android.os;
public class Handler {
    private static final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<>();
    private static long now, sequence;
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable action, long delay) {
        if (action == null) throw new NullPointerException("action");
        tasks.add(new Task(action, now + delay, sequence++));
        return true;
    }
    public void removeCallbacks(Runnable action) { tasks.removeIf(t -> t.action == action); }
    public static long now() { return now; }
    public static void advance(long millis) {
        long target = now + millis;
        while (!tasks.isEmpty() && tasks.peek().due <= target) {
            Task next = tasks.remove();
            now = next.due;
            next.action.run();
        }
        now = target;
    }
    public static void reset() { tasks.clear(); now = 0; sequence = 0; }
    private static final class Task implements Comparable<Task> {
        final Runnable action; final long due, sequence;
        Task(Runnable action, long due, long sequence) {
            this.action=action; this.due=due; this.sequence=sequence;
        }
        public int compareTo(Task other) {
            int order=Long.compare(due,other.due);
            return order==0?Long.compare(sequence,other.sequence):order;
        }
    }
}
""",
    "android/view/ViewParent.java": """
package android.view;
public interface ViewParent { void requestDisallowInterceptTouchEvent(boolean disallow); }
""",
    "android/view/ViewConfiguration.java": """
package android.view;
public class ViewConfiguration {
    public static ViewConfiguration get(android.content.Context context) { return new ViewConfiguration(); }
    public int getScaledTouchSlop() { return 8; }
}
""",
    "android/view/MotionEvent.java": """
package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3,
        ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6;
    private final int action; private final float x,y;
    public MotionEvent(int action, float x, float y) { this.action=action; this.x=x; this.y=y; }
    public int getActionMasked() { return action & 255; }
    public float getX() { return x; }
    public float getY() { return y; }
}
""",
    "android/view/View.java": """
package android.view;
public class View {
    public interface OnTouchListener { boolean onTouch(View view, MotionEvent event); }
    public interface OnClickListener { void onClick(View view); }
    private final android.content.Context context = new android.content.Context();
    private OnTouchListener touch; private OnClickListener click; private ViewParent parent;
    private boolean enabled=true, shown=true, focused=true, pressed, longClickable=true;
    private CharSequence tooltip="Control";
    public android.content.Context getContext() { return context; }
    public void setOnTouchListener(OnTouchListener listener) { touch=listener; }
    public void setOnClickListener(OnClickListener listener) { click=listener; }
    public boolean performClick() { if (click == null) return false; click.onClick(this); return true; }
    public boolean dispatchTouchEvent(MotionEvent event) {
        boolean consumed=touch != null && touch.onTouch(this,event);
        if (!consumed && enabled && event.getActionMasked()==MotionEvent.ACTION_UP) performClick();
        return consumed;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled=value; }
    public boolean isShown() { return shown; }
    public void setShown(boolean value) { shown=value; }
    public boolean hasWindowFocus() { return focused; }
    public void setWindowFocus(boolean value) { focused=value; }
    public void setPressed(boolean value) { pressed=value; }
    public boolean isPressed() { return pressed; }
    public int getWidth() { return 100; }
    public int getHeight() { return 100; }
    public ViewParent getParent() { return parent; }
    public void setParent(ViewParent value) { parent=value; }
    public void setTooltipText(CharSequence value) { tooltip=value; }
    public CharSequence getTooltipText() { return tooltip; }
    public void setLongClickable(boolean value) { longClickable=value; }
    public boolean isLongClickable() { return longClickable; }
}
""",
    "uy/nico/bareremote/RemoteEngine.java": """
package uy.nico.bareremote;
final class RemoteEngine { interface Completion { void done(String error); } }
""",
}


class RepeatTouchTest(unittest.TestCase):
    def test_touch_repeat_lifecycle_and_pacing(self):
        with tempfile.TemporaryDirectory(prefix="bareremote-repeat-touch-") as directory:
            base = pathlib.Path(directory)
            sources = []
            for relative, source in STUBS.items():
                path = base / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                sources.append(str(path))
            sources += [str(ROOT / "app/src/main/java/uy/nico/bareremote/RepeatingKeyTouchListener.java"),
                        str(ROOT / "tests/RepeatTouchTest.java")]
            subprocess.run(["javac", "--release", "8", "-d", str(base), *sources], check=True)
            subprocess.run(["java", "-cp", str(base), "uy.nico.bareremote.RepeatTouchTest"],
                           check=True, timeout=10)


if __name__ == "__main__":
    unittest.main()
