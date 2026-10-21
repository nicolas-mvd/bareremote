"""Exercise the actual Android NSD helper with deterministic framework doubles.

No sockets, multicast packets, Android device, or physical TV are used. Platform
NSD integration and network visibility still need an installed-app check.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/Context.java": """
package android.content;
public class Context {
    public static final String NSD_SERVICE="servicediscovery", WIFI_SERVICE="wifi";
    public final android.net.nsd.NsdManager nsd = new android.net.nsd.NsdManager();
    public final android.net.wifi.WifiManager wifi = new android.net.wifi.WifiManager();
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String service) {
        if (NSD_SERVICE.equals(service)) return nsd;
        if (WIFI_SERVICE.equals(service)) return wifi;
        return null;
    }
}
""",
    "android/os/Build.java": """
package android.os;
public final class Build {
    public static final class VERSION { public static int SDK_INT=35; }
    public static final class VERSION_CODES { public static final int UPSIDE_DOWN_CAKE=34; }
}
""",
    "android/os/Looper.java": """
package android.os;
public final class Looper {
    private static final Looper MAIN=new Looper();
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MAIN; }
}
""",
    "android/os/Handler.java": """
package android.os;
public final class Handler {
    private static final java.util.PriorityQueue<Task> tasks=new java.util.PriorityQueue<>();
    private static long now, sequence;
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { return postDelayed(action, 0); }
    public boolean postDelayed(Runnable action, long delay) {
        if (action==null) throw new NullPointerException("action");
        tasks.add(new Task(action,now+Math.max(0,delay),sequence++));
        return true;
    }
    public void removeCallbacks(Runnable action) { tasks.removeIf(t -> t.action==action); }
    public static void advance(long millis) {
        long target=now+millis;
        int remaining=10000;
        while (!tasks.isEmpty() && tasks.peek().due<=target) {
            if (--remaining==0) throw new AssertionError("handler did not quiesce");
            Task task=tasks.remove(); now=task.due; task.action.run();
        }
        now=target;
    }
    public static void reset() { tasks.clear(); now=0; sequence=0; }
    private static final class Task implements Comparable<Task> {
        final Runnable action; final long due, sequence;
        Task(Runnable action,long due,long sequence) {
            this.action=action; this.due=due; this.sequence=sequence;
        }
        public int compareTo(Task other) {
            int result=Long.compare(due,other.due);
            return result==0?Long.compare(sequence,other.sequence):result;
        }
    }
}
""",
    "android/net/nsd/NsdServiceInfo.java": """
package android.net.nsd;
public class NsdServiceInfo {
    private String name,type; private java.net.InetAddress host; private int port;
    private java.util.List<java.net.InetAddress> addresses;
    public String getServiceName() { return name; }
    public void setServiceName(String value) { name=value; }
    public String getServiceType() { return type; }
    public void setServiceType(String value) { type=value; }
    public java.net.InetAddress getHost() { return host; }
    public void setHost(java.net.InetAddress value) { host=value; }
    public java.util.List<java.net.InetAddress> getHostAddresses() {
        if (android.os.Build.VERSION.SDK_INT<34) throw new AssertionError("API 34 method on an older Android");
        return addresses!=null?addresses:host==null?java.util.Collections.emptyList():java.util.Collections.singletonList(host);
    }
    public void setHostAddresses(java.util.List<java.net.InetAddress> value) { addresses=value; }
    public int getPort() { return port; }
    public void setPort(int value) { port=value; }
}
""",
    "android/net/nsd/NsdManager.java": """
package android.net.nsd;
public class NsdManager {
    public static final int PROTOCOL_DNS_SD=1, FAILURE_INTERNAL_ERROR=0;
    public interface DiscoveryListener {
        void onDiscoveryStarted(String type);
        void onStartDiscoveryFailed(String type,int error);
        void onStopDiscoveryFailed(String type,int error);
        void onDiscoveryStopped(String type);
        void onServiceFound(NsdServiceInfo info);
        void onServiceLost(NsdServiceInfo info);
    }
    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo info,int error);
        void onServiceResolved(NsdServiceInfo info);
        default void onResolutionStopped(NsdServiceInfo info) {}
        default void onStopResolutionFailed(NsdServiceInfo info,int error) {}
    }
    public static final class Discovery {
        public final String type; public final int protocol; public final DiscoveryListener listener;
        Discovery(String type,int protocol,DiscoveryListener listener) {
            this.type=type; this.protocol=protocol; this.listener=listener;
        }
    }
    public static final class Resolution {
        public final NsdServiceInfo info; public final ResolveListener listener;
        public boolean stopped;
        Resolution(NsdServiceInfo info,ResolveListener listener) { this.info=info; this.listener=listener; }
    }
    public final java.util.List<Discovery> discoveries=new java.util.ArrayList<>();
    public final java.util.List<DiscoveryListener> stops=new java.util.ArrayList<>();
    public final java.util.List<Resolution> resolutions=new java.util.ArrayList<>();
    public final java.util.List<ResolveListener> resolutionStops=new java.util.ArrayList<>();
    public RuntimeException discoverFailure,stopFailure,resolveFailure,resolutionStopFailure;
    public void discoverServices(String type,int protocol,DiscoveryListener listener) {
        discoveries.add(new Discovery(type,protocol,listener));
        if (discoverFailure!=null) throw discoverFailure;
    }
    public void stopServiceDiscovery(DiscoveryListener listener) {
        stops.add(listener);
        if (stopFailure!=null) throw stopFailure;
    }
    public void resolveService(NsdServiceInfo info,ResolveListener listener) {
        resolutions.add(new Resolution(info,listener));
        if (resolveFailure!=null) throw resolveFailure;
    }
    public void stopServiceResolution(ResolveListener listener) {
        resolutionStops.add(listener);
        if (resolutionStopFailure!=null) throw resolutionStopFailure;
        for (Resolution resolution:resolutions) if (resolution.listener==listener) resolution.stopped=true;
    }
}
""",
    "android/net/wifi/WifiManager.java": """
package android.net.wifi;
public class WifiManager {
    public final java.util.List<MulticastLock> locks=new java.util.ArrayList<>();
    public RuntimeException createFailure,acquireFailure;
    public MulticastLock createMulticastLock(String name) {
        if (createFailure!=null) throw createFailure;
        MulticastLock lock=new MulticastLock(acquireFailure); locks.add(lock); return lock;
    }
    public static final class MulticastLock {
        private final RuntimeException acquireFailure;
        private boolean held;
        MulticastLock(RuntimeException acquireFailure) { this.acquireFailure=acquireFailure; }
        public void setReferenceCounted(boolean value) {}
        public void acquire() { if (acquireFailure!=null) throw acquireFailure; held=true; }
        public void release() { held=false; }
        public boolean isHeld() { return held; }
    }
}
""",
}


class DiscoveryTests(unittest.TestCase):
    def test_nsd_validation_bounds_and_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix="bareremote-discovery-") as directory:
            base = Path(directory)
            sources = []
            for relative, source in STUBS.items():
                path = base / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                sources.append(str(path))
            sources += [str(ROOT / "app/src/main/java/uy/nico/bareremote/TvDiscovery.java"),
                        str(ROOT / "tests/DiscoveryTest.java")]
            subprocess.run(["javac", "--release", "8", "-d", str(base), *sources], check=True)
            subprocess.run(["java", "-cp", str(base), "uy.nico.bareremote.DiscoveryTest"],
                           check=True, timeout=10)


if __name__ == "__main__":
    unittest.main()
