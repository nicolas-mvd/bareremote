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
    private final java.util.Map<String,Object> values = new java.util.HashMap<>();
    public boolean failNextCommit;
    public SharedPreferences() { values.put("host", "192.0.2.1"); values.put("certificate", "AQ=="); values.put("pairing_identity_version", 3); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    public Editor edit() { return new Editor(); }
    public class Editor {
        private final java.util.Map<String,Object> changes = new java.util.HashMap<>();
        public Editor putString(String key, String value) { changes.put(key, value); return this; }
        public Editor putInt(String key, int value) { changes.put(key, value); return this; }
        public Editor remove(String key) { changes.put(key, null); return this; }
        public boolean commit() { apply(); if (failNextCommit) { failNextCommit=false; return false; } return true; }
        public void apply() { for (java.util.Map.Entry<String,Object> entry : changes.entrySet()) {
            if (entry.getValue()==null) values.remove(entry.getKey()); else values.put(entry.getKey(),entry.getValue());
        } }
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
package uy.nico.bareremote; final class PhoneIdentity { static final int VERSION=3; }
""",
    "uy/nico/bareremote/RemoteWidget.java": """
package uy.nico.bareremote;
final class RemoteWidget {
    static void updateAll(android.content.Context context) {}
    static void updateAll(android.content.Context context,String status) {}
}
""",
    "uy/nico/bareremote/PictureStateClient.java": """
package uy.nico.bareremote;
import java.io.*;
import java.util.concurrent.*;
final class PictureStateClient {
    final CountDownLatch ready = new CountDownLatch(1);
    volatile boolean closed;
    volatile String state = "day";
    String await() throws Exception {
        if (!ready.await(4, TimeUnit.SECONDS)) throw new java.net.SocketTimeoutException();
        if (closed) throw new IOException("Closed");
        return state;
    }
    void reply(String value) { state = value; ready.countDown(); }
    void close() { closed = true; ready.countDown(); }
}
""",
    "uy/nico/bareremote/DogStateClient.java": """
package uy.nico.bareremote;
import java.io.*;
import java.util.concurrent.*;
final class DogStateClient {
    final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    volatile boolean closed;
    final int timeout;
    DogStateClient(int timeout) { this.timeout=timeout; }
    String await(boolean waitForFinal, java.util.function.Consumer<String> progress) throws Exception {
        for (;;) {
            String state=replies.poll(8, TimeUnit.SECONDS);
            if (closed) throw new IOException("Closed");
            if (state==null) throw new java.net.SocketTimeoutException();
            if (waitForFinal && "starting".equals(state)) { if(progress!=null) progress.accept(state); }
            else return state;
        }
    }
    void reply(String value) { replies.offer(value); }
    void close() { closed=true; replies.offer("closed"); }
}
""",
    "uy/nico/bareremote/TvDiscovery.java": """
package uy.nico.bareremote;
final class TvDiscovery {
    static final int MAX_RESULTS=32;
    static final java.util.List<Device> offered=new java.util.ArrayList<>();
    static int starts,closes;
    static long timeout;
    static boolean hold;
    static TvDiscovery active;
    interface Listener { void onChanged(java.util.List<Device> devices,boolean scanning,String message); }
    static final class Device { final String host; Device(String host) { this.host=host; } }
    Listener listener;
    boolean closed;
    TvDiscovery(android.content.Context context) {}
    void start(Listener value,long duration) {
        starts++; timeout=duration; active=this; listener=value;
        value.onChanged(new java.util.ArrayList<>(offered),true,"Scanning");
        if (!hold) value.onChanged(new java.util.ArrayList<>(offered),false,"Done");
    }
    void close() { if(!closed) {closed=true;closes++;} }
    static void emit(String... hosts) {
        for(String host:hosts)offered.add(new Device(host));
        if(active!=null && !active.closed) active.listener.onChanged(new java.util.ArrayList<>(offered),true,"Scanning");
    }
    static void reset() {offered.clear();starts=0;closes=0;timeout=0;hold=false;active=null;}
}
""",
    "uy/nico/bareremote/TvProtocol.java": """
package uy.nico.bareremote;
import java.util.concurrent.*;
final class TvProtocol {
    static final int DELIVERY_WINDOW_MS=750;
    static final class Pairing {
        static int starts, finishes;
        static boolean failStart, failFinish;
        static volatile CountDownLatch finishGate, finishEntered;
        static Pairing start(String host,int port,String name,PhoneIdentity identity,java.util.function.BooleanSupplier cancelled) throws Exception {
            starts++; if (failStart) throw new java.io.IOException("Pairing rejected"); return new Pairing();
        }
        byte[] finish(String code) throws Exception {
            finishes++;
            CountDownLatch gate=finishGate;
            if (gate!=null) { finishEntered.countDown(); gate.await(); }
            if (failFinish) throw new java.io.IOException("Pairing code rejected");
            return new byte[]{2};
        }
        void close() {}
        static void reset() { starts=0; finishes=0; failStart=false; failFinish=false; finishGate=null; finishEntered=null; }
    }
    static final class Remote {
        static final java.util.List<Remote> connections = new CopyOnWriteArrayList<>();
        static final java.util.List<Integer> budgets = new CopyOnWriteArrayList<>();
        static volatile java.util.function.Consumer<String> readyHook;
        static final java.util.List<Integer> keys = new CopyOnWriteArrayList<>();
        static final java.util.List<Boolean> modes = new CopyOnWriteArrayList<>();
        static final java.util.List<String> inputs = new CopyOnWriteArrayList<>();
        static volatile CountDownLatch connectGate, connectEntered, writeEntered;
        static volatile boolean blockWrite, holdPictureReplies, unsupportedPicture;
        static volatile String failHost, pinRejectHost;
        static final java.util.List<PictureStateClient> pictureRequests = new CopyOnWriteArrayList<>();
        static volatile boolean holdDogReplies, unsupportedDog;
        static final java.util.List<DogStateClient> dogRequests = new CopyOnWriteArrayList<>();
        static final java.util.List<String> dogActions = new CopyOnWriteArrayList<>();
        static final java.util.List<String> dogSetups = new CopyOnWriteArrayList<>();
        static final java.util.List<Boolean> pictureToggles = new CopyOnWriteArrayList<>();
        interface KeyboardListener { void changed(KeyboardState state); }
        volatile KeyboardListener keyboardListener;
        volatile KeyboardState keyboard = KeyboardState.unavailable();
        void setKeyboardListener(KeyboardListener value) { keyboardListener=value; if(value!=null)value.changed(keyboard); }
        KeyboardState keyboardState() { return keyboard; }
        void keyboard(KeyboardState next) { keyboard=next; if(keyboardListener!=null)keyboardListener.changed(next); }
        static final java.util.List<String> texts = new CopyOnWriteArrayList<>();
        void sendText(String text, KeyboardState expected) throws Exception {
            if(!open || !keyboard.sameField(expected))throw new java.io.IOException("Field changed"); texts.add(text);
        }
        void sendKeyboardKey(int key, KeyboardState expected) throws Exception {
            if(!open || !keyboard.sameField(expected))throw new java.io.IOException("Field changed"); sendKey(key);
        }
        volatile boolean open=true;
        final String host;
        final int port;
        final byte[] pin;
        final PhoneIdentity identity;
        final CountDownLatch closed = new CountDownLatch(1);
        Remote(String host,int port,byte[] pin,PhoneIdentity identity) {
            this.host=host; this.port=port; this.pin=pin.clone(); this.identity=identity;
        }
        static Remote connect(String host,int port,byte[] pin,PhoneIdentity identity) throws Exception {
            return connect(host,port,pin,identity,5000,()->false);
        }
        static Remote connect(String host,int port,byte[] pin,PhoneIdentity identity,int timeout,java.util.function.BooleanSupplier cancelled) throws Exception {
            if(cancelled.getAsBoolean())throw new java.io.IOException("Control cancelled");
            budgets.add(timeout);
            Remote next=new Remote(host,port,pin,identity); connections.add(next);
            CountDownLatch gate=connectGate; connectGate=null;
            if (gate!=null) {
                connectEntered.countDown();
                while(!gate.await(10,TimeUnit.MILLISECONDS)) {
                    if(cancelled.getAsBoolean()){next.close();throw new java.io.IOException("Control cancelled");}
                }
            }
            if(cancelled.getAsBoolean()){next.close();throw new java.io.IOException("Control cancelled");}
            // Model adapter failures only. The protocol suite separately exercises
            // the real mutual-TLS certificate pin on loopback sockets.
            if (host.equals(failHost) || host.equals(pinRejectHost)) {
                next.close();
                throw new java.io.IOException(host.equals(pinRejectHost)
                        ? "TV certificate changed. Pair again in Setup." : "Connection refused");
            }
            if(readyHook!=null)readyHook.accept(host);
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
        PictureStateClient openPictureStateRequest(int timeout) {
            if (unsupportedPicture) throw new UnsupportedOperationException("Picture unsupported");
            return new PictureStateClient();
        }
        void sendPictureStateRequest(PictureStateClient client, boolean toggle) {
            pictureRequests.add(client); pictureToggles.add(toggle);
            if (!holdPictureReplies) client.reply(toggle ? "night" : "day");
        }
        void sendDogSetup() throws Exception {
            if (!open) throw new java.io.IOException("Closed");
            if (blockWrite) { blockWrite=false; writeEntered.countDown(); closed.await(); throw new java.io.IOException("Closed while writing"); }
            dogSetups.add("barehome://dog/setup");
        }
        void awaitCommandDeliveryWindow() throws Exception { if (!open) throw new java.io.IOException("Closed"); }
        DogStateClient openDogStateRequest(int timeout) {
            if (unsupportedDog) throw new UnsupportedOperationException("Dog Mode unsupported");
            return new DogStateClient(timeout);
        }
        void sendDogStateRequest(DogStateClient client, String action) {
            dogRequests.add(client); dogActions.add(action);
            if (!holdDogReplies) client.reply("start".equals(action) ? "active" : "off");
        }
        void sendLongKey(int key) throws Exception { sendKey(key); }
        void close() { open=false; closed.countDown(); }
        static void reset() { budgets.clear(); readyHook=null; connections.clear(); texts.clear(); keys.clear(); modes.clear(); inputs.clear(); dogRequests.clear(); dogActions.clear(); dogSetups.clear(); holdDogReplies=false; unsupportedDog=false; pictureRequests.clear(); pictureToggles.clear(); holdPictureReplies=false; unsupportedPicture=false; connectGate=null; connectEntered=null; writeEntered=null; blockWrite=false; failHost=null; pinRejectHost=null; }
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
                        str(ROOT / "app/src/main/java/uy/nico/bareremote/PictureStateWire.java"),
                        str(ROOT / "app/src/main/java/uy/nico/bareremote/DogStateWire.java"),
                        str(ROOT / "app/src/main/java/uy/nico/bareremote/KeyboardState.java"),
                        str(ROOT / "app/src/main/java/uy/nico/bareremote/KeyboardSession.java"),
                        str(ROOT / "app/src/main/java/uy/nico/bareremote/Proto.java"),
                        str(ROOT / "tests/EngineTest.java")]
            subprocess.run(["javac", "--release", "8", "-d", str(base), *sources], check=True)
            subprocess.run(["java", "-cp", str(base), "uy.nico.bareremote.EngineTest"], check=True, timeout=20)


if __name__ == "__main__":
    unittest.main()
