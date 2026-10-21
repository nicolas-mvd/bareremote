package uy.nico.bareremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A bounded, user-requested DNS-SD browse. It never connects to or pairs a TV. */
@SuppressWarnings("deprecation")
public final class TvDiscovery {
    static final String SERVICE_TYPE = "_androidtvremote2._tcp";
    static final long SCAN_TIMEOUT_MS = 12_000;
    static final int MAX_RESULTS = 32;
    static final int MAX_CANDIDATES = 64;
    private static final String SCAN_ERROR =
            "Couldn’t scan for TVs. Try again, or enter the TV’s IP address.";

    public interface Listener {
        void onChanged(List<Device> devices, boolean scanning, String message);
    }

    public static final class Device {
        public final String name;
        public final String host;

        private Device(String name, String host) {
            this.name = name;
            this.host = host;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final WifiManager wifi;
    private Scan current;
    // Older Android versions cannot cancel a resolve. Keep its slot until its
    // terminal callback, including across close()/start(), rather than overlap it.
    private Resolution resolving;

    public TvDiscovery(Context context) {
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        NsdManager discovery = null;
        WifiManager wireless = null;
        try { discovery = (NsdManager) application.getSystemService(Context.NSD_SERVICE); }
        catch (RuntimeException unavailable) { /* The manual address path remains available. */ }
        try { wireless = (WifiManager) application.getSystemService(Context.WIFI_SERVICE); }
        catch (RuntimeException unavailable) { /* Ethernet discovery can still work. */ }
        nsd = discovery;
        wifi = wireless;
    }

    /** Called on the main thread. Starting again replaces the previous browse. */
    public void start(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        close();
        Scan scan = new Scan(listener);
        current = scan;
        publish(scan, "Scanning for TVs…");
        // A listener may synchronously close or replace its scan.
        if (!active(scan)) return;
        if (nsd == null) { finish(scan, SCAN_ERROR); return; }
        if (!acquireMulticast(scan)) { finish(scan, SCAN_ERROR); return; }
        scan.timeout = () -> finish(scan, null);
        main.postDelayed(scan.timeout, SCAN_TIMEOUT_MS);
        scan.discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }

            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> found(scan, info));
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                main.post(() -> lost(scan, info));
            }

            @Override public void onStartDiscoveryFailed(String type, int error) {
                main.post(() -> {
                    scan.discoveryRequested = false;
                    finish(scan, SCAN_ERROR);
                });
            }

            @Override public void onStopDiscoveryFailed(String type, int error) {
                main.post(() -> finish(scan, SCAN_ERROR));
            }

            @Override public void onDiscoveryStopped(String type) {
                main.post(() -> {
                    scan.discoveryRequested = false;
                    if (active(scan)) finish(scan, null);
                });
            }
        };
        try {
            scan.discoveryRequested = true;
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, scan.discovery);
        } catch (RuntimeException unavailable) {
            finish(scan, SCAN_ERROR);
        }
    }

    /** Main-thread lifecycle cancellation. This helper can be started again. */
    public void close() {
        Scan scan = current;
        current = null;
        if (scan == null) return;
        scan.listener = null;
        scan.scanning = false;
        release(scan);
        scan.candidates.clear();
    }

    private boolean active(Scan scan) { return current == scan && scan.scanning; }

    private void found(Scan scan, NsdServiceInfo info) {
        if (!active(scan)) return;
        String key = serviceKey(info);
        if (key == null) return;
        Candidate candidate = scan.candidates.get(key);
        if (candidate == null) {
            if (scan.candidates.size() >= MAX_CANDIDATES) return;
            candidate = new Candidate();
            scan.candidates.put(key, candidate);
        } else if (candidate.present) {
            return; // Android can announce the same service more than once.
        }
        candidate.present = true;
        candidate.revision++;
        candidate.info = info;
        if (!candidate.queued) {
            candidate.queued = true;
            scan.queue.add(candidate);
        }
        resolveNext();
    }

    private void lost(Scan scan, NsdServiceInfo info) {
        if (!active(scan)) return;
        String key = serviceKey(info);
        Candidate candidate = key == null ? null : scan.candidates.get(key);
        if (candidate == null || !candidate.present) return;
        candidate.present = false;
        candidate.revision++;
        candidate.device = null;
        if (candidate.queued) {
            scan.queue.remove(candidate);
            candidate.queued = false;
        }
        if (resolving != null && resolving.scan == scan && resolving.candidate == candidate) {
            cancelResolution(resolving);
        }
        publish(scan, "Scanning for TVs…");
    }

    private void resolveNext() {
        Scan scan = current;
        if (resolving != null || scan == null || !active(scan)) return;
        Candidate candidate;
        do {
            candidate = scan.queue.poll();
            if (candidate == null) return;
            candidate.queued = false;
        } while (!candidate.present);
        Resolution operation = new Resolution(scan, candidate);
        resolving = operation;
        operation.listener = new NsdManager.ResolveListener() {
            @Override public void onServiceResolved(NsdServiceInfo info) {
                main.post(() -> resolved(operation, info));
            }

            @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                main.post(() -> resolved(operation, null));
            }

            @Override public void onResolutionStopped(NsdServiceInfo info) {
                main.post(() -> resolved(operation, null));
            }

            @Override public void onStopResolutionFailed(NsdServiceInfo info, int error) {
                // The original terminal callback still owns the resolve slot.
            }
        };
        try {
            nsd.resolveService(candidate.info, operation.listener);
        } catch (RuntimeException unavailable) {
            // Continue asynchronously so a burst of rejected services cannot
            // recursively exhaust the main thread's stack.
            main.post(() -> resolved(operation, null));
        }
    }

    private void resolved(Resolution operation, NsdServiceInfo info) {
        if (resolving != operation) return;
        resolving = null;
        Candidate candidate = operation.candidate;
        if (active(operation.scan) && candidate.present
                && candidate.revision == operation.revision) {
            Device device = device(info, candidate.info);
            if (device != null) {
                candidate.device = device;
                publish(operation.scan, "Scanning for TVs…");
            }
        }
        resolveNext();
    }

    private static Device device(NsdServiceInfo info, NsdServiceInfo discovered) {
        if (info == null || serviceKey(info) == null || info.getPort() != 6466) return null;
        InetAddress host = null;
        if (Build.VERSION.SDK_INT >= 34) {
            for (InetAddress address : info.getHostAddresses()) {
                if (usableAddress(address)) { host = address; break; }
            }
        } else {
            InetAddress address = info.getHost();
            if (usableAddress(address)) host = address;
        }
        if (host == null) return null;
        return new Device(displayName(discovered.getServiceName()), host.getHostAddress());
    }

    private static boolean usableAddress(InetAddress host) {
        return host instanceof Inet4Address && !host.isAnyLocalAddress()
                && !host.isLoopbackAddress() && !host.isMulticastAddress()
                && !"255.255.255.255".equals(host.getHostAddress());
    }

    private static String serviceKey(NsdServiceInfo info) {
        if (info == null) return null;
        String type = info.getServiceType();
        String name = info.getServiceName();
        if (type == null || name == null || name.isEmpty() || name.length() > 255) return null;
        // Legacy Android NSD includes the separator before the resolved type.
        if (type.startsWith(".")) type = type.substring(1);
        if (type.endsWith(".")) type = type.substring(0, type.length() - 1);
        if (!SERVICE_TYPE.equals(type.toLowerCase(Locale.ROOT))) return null;
        return name;
    }

    private static String displayName(String name) {
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < name.length() && display.length() < 80; i++) {
            char character = name.charAt(i);
            if (!Character.isISOControl(character) && Character.getType(character) != Character.FORMAT) {
                display.append(character);
            }
        }
        String result = display.toString().trim();
        return result.isEmpty() ? "Android TV" : result;
    }

    private void finish(Scan scan, String error) {
        if (!active(scan)) return;
        scan.scanning = false;
        release(scan);
        String message = error != null ? error : snapshot(scan).isEmpty()
                ? "No TVs found. Check the network, or enter the TV’s IP address."
                : "Scan complete. Choose a TV below.";
        publish(scan, message);
    }

    private void release(Scan scan) {
        if (scan.timeout != null) main.removeCallbacks(scan.timeout);
        scan.queue.clear();
        if (scan.discoveryRequested && scan.discovery != null) {
            scan.discoveryRequested = false;
            try { nsd.stopServiceDiscovery(scan.discovery); }
            catch (RuntimeException stopped) { /* Already stopped or NSD became unavailable. */ }
        }
        if (resolving != null && resolving.scan == scan) cancelResolution(resolving);
        WifiManager.MulticastLock lock = scan.multicast;
        scan.multicast = null;
        if (lock != null) {
            try { if (lock.isHeld()) lock.release(); }
            catch (RuntimeException stopped) { /* Some devices revoke locks on network changes. */ }
        }
    }

    private void cancelResolution(Resolution operation) {
        if (Build.VERSION.SDK_INT < 34 || operation.stopRequested) return;
        operation.stopRequested = true;
        try { nsd.stopServiceResolution(operation.listener); }
        catch (RuntimeException stopped) { /* Leave the slot owned until its terminal callback. */ }
    }

    private boolean acquireMulticast(Scan scan) {
        if (wifi == null) return true;
        try {
            scan.multicast = wifi.createMulticastLock("BareRemote TV scan");
            scan.multicast.setReferenceCounted(false);
            scan.multicast.acquire();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void publish(Scan scan, String message) {
        Listener listener = scan.listener;
        if (current == scan && listener != null) listener.onChanged(snapshot(scan), scan.scanning, message);
    }

    private static List<Device> snapshot(Scan scan) {
        Map<String, Device> devices = new LinkedHashMap<>();
        for (Candidate candidate : scan.candidates.values()) {
            if (candidate.present && candidate.device != null) {
                devices.putIfAbsent(candidate.device.host, candidate.device);
                if (devices.size() == MAX_RESULTS) break;
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(devices.values()));
    }

    private static final class Candidate {
        NsdServiceInfo info;
        Device device;
        boolean present;
        boolean queued;
        int revision;
    }

    private static final class Scan {
        Listener listener;
        boolean scanning = true;
        boolean discoveryRequested;
        Runnable timeout;
        NsdManager.DiscoveryListener discovery;
        WifiManager.MulticastLock multicast;
        final Map<String, Candidate> candidates = new LinkedHashMap<>();
        final ArrayDeque<Candidate> queue = new ArrayDeque<>();
        Scan(Listener listener) { this.listener = listener; }
    }

    private static final class Resolution {
        final Scan scan;
        final Candidate candidate;
        final int revision;
        NsdManager.ResolveListener listener;
        boolean stopRequested;
        Resolution(Scan scan, Candidate candidate) {
            this.scan = scan;
            this.candidate = candidate;
            revision = candidate.revision;
        }
    }
}
