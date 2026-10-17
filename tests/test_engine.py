"""Run the real engine with deterministic Android scheduling/network test doubles.

These tests exercise command ownership and deadlines, not Android service or TLS
compatibility. Those paths still require the APK/emulator and protocol suites.
"""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/Context.java": """
package android.content;
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final SharedPreferences preferences = new SharedPreferences();
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
}
""",
    "android/content/SharedPreferences.java": """
package android.content;
public class SharedPreferences {
    private final java.util.Map<String,String> values = new java.util.HashMap<>();
    public SharedPreferences() { values.put("host", "192.0.2.1"); values.put("certificate", "AQ=="); }
    public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public Editor edit() { return new Editor(); }
    public class Editor {
        public Editor putString(String key, String value) { values.put(key, value); return this; }
        public Editor remove(String key) { values.remove(key); return this; }
        public boolean commit() { return true; }
        public void apply() {}
    }
}
""",
    "android/os/Looper.java": """
package android.os;
public class Looper { private static final Looper MAIN = new Looper(); public static Looper getMainLooper() { return MAIN; } }
""",
    "android/os/SystemClock.java": """
package android.os;
public class SystemClock { public static volatile long now; public static long elapsedRealtime() { return now; } }
""",
    "android/os/Handler.java": """
package android.os;
public class Handler {
    private static final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<>();
    private static long sequence;
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { return postDelayed(action, 0); }
    public boolean postDelayed(Runnable action, long delay) {
        synchronized (tasks) { tasks.add(new Task(action, SystemClock.now + delay, sequence++)); }
        return true;
    }
    public void removeCallbacks(Runnable action) { synchronized (tasks) { tasks.removeIf(t -> t.action == action); } }
    public static void drain() {
        for (;;) {
            Task next;
            synchronized (tasks) {
                if (tasks.isEmpty() || tasks.peek().due > SystemClock.now) return;
                next = tasks.remove();
            }
            next.action.run();
        }
    }
    public static void advance(long millis) { SystemClock.now += millis; drain(); }
    public static void reset() { synchronized (tasks) { tasks.clear(); SystemClock.now = 0; } }
    private static final class Task implements Comparable<Task> {
        final Runnable action; final long due, sequence;
        Task(Runnable action, long due, long sequence) { this.action=action; this.due=due; this.sequence=sequence; }
        public int compareTo(Task other) { int result=Long.compare(due,other.due); return result==0?Long.compare(sequence,other.sequence):result; }
    }
}
""",
    "android/util/Base64.java": """
package android.util;
public class Base64 {
    public static final int NO_WRAP=2;
    public static byte[] decode(String value,int flags) { return java.util.Base64.getDecoder().decode(value); }
    public static String encodeToString(byte[] value,int flags) { return java.util.Base64.getEncoder().encodeToString(value); }
}
""",
    "android/view/KeyEvent.java": """
package android.view;
public class KeyEvent {
    public static final int KEYCODE_DPAD_UP=19, KEYCODE_DPAD_DOWN=20, KEYCODE_DPAD_LEFT=21,
        KEYCODE_DPAD_RIGHT=22, KEYCODE_DPAD_CENTER=23, KEYCODE_BACK=4, KEYCODE_HOME=3,
        KEYCODE_VOLUME_UP=24, KEYCODE_VOLUME_DOWN=25, KEYCODE_VOLUME_MUTE=164,
        KEYCODE_MEDIA_PLAY_PAUSE=85, KEYCODE_POWER=26, KEYCODE_TV_INPUT=178;
}
""",
    "uy/nico/bareremote/PhoneIdentity.java": """
package uy.nico.bareremote; final class PhoneIdentity {}
""",
    "uy/nico/bareremote/RemoteWidget.java": """
package uy.nico.bareremote;
final class RemoteWidget {
    static void updateAll(android.content.Context context) {}
    static void updateAll(android.content.Context context,String status) {}
}
""",
    "uy/nico/bareremote/TvProtocol.java": """
package uy.nico.bareremote;
import java.util.concurrent.*;
final class TvProtocol {
    static final int DELIVERY_WINDOW_MS=750;
    static final class Pairing {
        static Pairing start(String host,int port,String name,PhoneIdentity identity,java.util.function.BooleanSupplier cancelled) { return new Pairing(); }
        byte[] finish(String code) { return new byte[]{1}; }
        void close() {}
    }
    static final class Remote {
        static final java.util.List<Remote> connections = new CopyOnWriteArrayList<>();
        static final java.util.List<Integer> keys = new CopyOnWriteArrayList<>();
        static final java.util.List<Boolean> modes = new CopyOnWriteArrayList<>();
        static final java.util.List<String> inputs = new CopyOnWriteArrayList<>();
        static volatile CountDownLatch connectGate, connectEntered, writeEntered;
        static volatile boolean blockWrite;
        volatile boolean open=true;
        final CountDownLatch closed = new CountDownLatch(1);
        static Remote connect(String host,int port,byte[] pin,PhoneIdentity identity) throws Exception {
            Remote next=new Remote(); connections.add(next);
            CountDownLatch gate=connectGate; connectGate=null;
            if (gate!=null) { connectEntered.countDown(); gate.await(); }
            return next;
        }
        boolean isOpen() { return open; }
        void sendKey(int key) throws Exception {
            if (!open) throw new java.io.IOException("Closed");
            if (blockWrite) { blockWrite=false; writeEntered.countDown(); closed.await(); throw new java.io.IOException("Closed while writing"); }
            keys.add(key);
        }
        void sendInputPicker() throws Exception { if (!open) throw new java.io.IOException("Closed"); inputs.add("picker"); }
        void sendPictureMode(boolean night) throws Exception { if (!open) throw new java.io.IOException("Closed"); modes.add(night); }
        void sendLongKey(int key) throws Exception { sendKey(key); }
        void close() { open=false; closed.countDown(); }
        static void reset() { connections.clear(); keys.clear(); modes.clear(); inputs.clear(); connectGate=null; connectEntered=null; writeEntered=null; blockWrite=false; }
    }
}
""",
}


class EngineTest(unittest.TestCase):
    def test_connection_ownership_and_deadlines(self):
        with tempfile.TemporaryDirectory(prefix="bareremote-engine-") as directory:
            base = pathlib.Path(directory)
            sources = []
            for relative, source in STUBS.items():
                path = base / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                sources.append(str(path))
            sources += [str(ROOT / "app/src/main/java/uy/nico/bareremote/RemoteEngine.java"),
                        str(ROOT / "tests/EngineTest.java")]
            subprocess.run(["javac", "--release", "8", "-d", str(base), *sources], check=True)
            subprocess.run(["java", "-cp", str(base), "uy.nico.bareremote.EngineTest"], check=True, timeout=20)


if __name__ == "__main__":
    unittest.main()
