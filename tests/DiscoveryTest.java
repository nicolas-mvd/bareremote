package uy.nico.bareremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Behavior tests for the production helper; every Android callback is controlled. */
public final class DiscoveryTest {
    private static int assertions;
    private static final String TYPE="_androidtvremote2._tcp.";

    public static void main(String[] args) throws Exception {
        shortRecoveryScan();
        serviceTypeAndSerialResolution();
        resolvedAddressValidation();
        multipleAddressAndSnapshotIsolation();
        legacyResolvedServiceType();
        duplicateAndLostServices();
        lostPendingAndReappearingService();
        boundedCandidatesAndResults();
        timeoutKeepsResultsAndReleasesResources();
        closeSuppressesCallbacks();
        legacyRescanDrainsOldResolution();
        modernRescanAndLateCallbacks();
        discoveryAndResolverFailures();
        unavailablePlatformServices();
        reentrantListenerAndQueuedCancellation();
        System.out.println("Discovery tests: "+assertions+" assertions passed");
    }

    private static void shortRecoveryScan() throws Exception {
        Fixture f=new Fixture();
        f.discovery.start((devices,scanning,message)->f.events.add(new Event(devices,scanning,message)),3000);
        f.found(service("TV",TYPE,null,0));f.resolve(0,"192.168.1.20",6466);
        Handler.advance(2999);
        check(f.last().scanning && f.heldLocks()==1,"short recovery browse remains active until its three second bound");
        Handler.advance(1);
        check(!f.last().scanning && hasHost(f.last(),"192.168.1.20"),"short recovery browse keeps valid discovered candidates at deadline");
        check(f.heldLocks()==0 && f.nsd.stops.size()==1,"short browse releases multicast and NSD resources at deadline");
        int events=f.events.size();
        for(long invalid:new long[]{0,-1,TvDiscovery.SCAN_TIMEOUT_MS+1}) {
            try {f.discovery.start((devices,scanning,message)->{},invalid);throw new AssertionError("invalid bound accepted");}
            catch(IllegalArgumentException expected) {check(f.events.size()==events,"invalid short browse bound cannot start networking");}
        }
    }

    private static void serviceTypeAndSerialResolution() throws Exception {
        Fixture f=new Fixture();
        f.start();
        check(f.last().scanning && f.last().devices.isEmpty(),"start reports an empty active scan");
        NsdManager.Discovery browse=f.browse();
        check("_androidtvremote2._tcp".equals(browse.type) || TYPE.equals(browse.type),
                "browse requests only Android TV Remote v2");
        check(browse.protocol==NsdManager.PROTOCOL_DNS_SD,"browse uses DNS-SD");
        check(f.heldLocks()==1,"scan holds its own multicast lock");
        f.found(service("HTTP","_http._tcp.",null,0));
        f.found(service("Suffix",TYPE+"evil",null,0));
        f.found(service("Prefix",".."+TYPE,null,0));
        f.found(service("Missing",null,null,0));
        f.found(service("Double dot",TYPE+".",null,0));
        f.found(service(null,TYPE,null,0));
        f.found(service("",TYPE,null,0));
        StringBuilder huge=new StringBuilder(); for (int i=0;i<256;i++) huge.append('x');
        f.found(service(huge.toString(),TYPE,null,0));
        check(f.nsd.resolutions.isEmpty(),"unrelated and malformed service types never resolve");

        NsdServiceInfo first=service("Living room",TYPE,null,0);
        f.found(first);
        f.found(first);
        f.found(service("Bedroom","_ANDROIDTVREMOTE2._TCP",null,0));
        f.found(service("Office",TYPE,null,0));
        check(f.nsd.resolutions.size()==1,"one resolver owns the first service despite duplicates");
        f.resolve(0,"192.168.1.20",6466);
        check(f.last().devices.size()==1 && hasHost(f.last(),"192.168.1.20"),"valid IPv4 remote is listed");
        check(f.nsd.resolutions.size()==2,"next queued resolver starts only after completion");
        f.fail(1);
        check(f.nsd.resolutions.size()==3,"failed resolution advances the serial queue");
        f.fail(1);
        check(f.nsd.resolutions.size()==3,"duplicate old failure cannot advance or duplicate work");
        f.resolve(2,"192.168.1.21",6466);
        check(f.last().devices.size()==2,"failed candidate does not prevent a later TV");
        f.discovery.close(); flush();
    }

    private static void resolvedAddressValidation() throws Exception {
        Fixture f=new Fixture(); f.start();
        String[] invalidHosts={null,"::1","2001:db8::2","0.0.0.0","127.0.0.1","239.1.2.3","255.255.255.255"};
        for (int i=0;i<invalidHosts.length;i++) {
            f.found(service("invalid-host-"+i,TYPE,null,0));
            f.resolve(i,invalidHosts[i],6466);
            check(f.last().devices.isEmpty(),"invalid host is rejected: "+invalidHosts[i]);
        }
        int base=f.nsd.resolutions.size();
        int[] invalidPorts={0,6467,-1,65536};
        for (int i=0;i<invalidPorts.length;i++) {
            f.found(service("invalid-port-"+i,TYPE,null,0));
            f.resolve(base+i,"192.168.2.20",invalidPorts[i]);
            check(f.last().devices.isEmpty(),"unexpected remote port is rejected: "+invalidPorts[i]);
        }
        int next=f.nsd.resolutions.size();
        f.found(service("Private TV",TYPE,null,0));
        f.resolve(next,"10.20.30.40",6466);
        f.found(service("Link-local TV",TYPE,null,0));
        f.resolve(next+1,"169.254.2.10",6466);
        check(hasHost(f.last(),"10.20.30.40") && hasHost(f.last(),"169.254.2.10"),
                "private and link-local IPv4 addresses remain usable");
        StringBuilder label=new StringBuilder("Living\nRoom\tTV");
        for (int i=0;i<100;i++) label.append('x');
        f.found(service(label.toString(),TYPE,null,0));
        f.resolve(next+2,"192.168.2.21",6466);
        TvDiscovery.Device named=find(f.last(),"192.168.2.21");
        check(named!=null && named.name.length()<=80,"display name has a bounded length");
        boolean controls=false;
        for (int i=0;i<named.name.length();i++) controls|=Character.isISOControl(named.name.charAt(i));
        check(!controls,"service names cannot inject control characters in the UI");
        f.discovery.close(); flush();
    }

    private static void multipleAddressAndSnapshotIsolation() throws Exception {
        Fixture f=new Fixture(); f.start();
        f.found(service("Dual stack",TYPE,null,0));
        NsdServiceInfo result=service("Dual stack",TYPE,"2001:db8::2",6466);
        List<InetAddress> addresses=new ArrayList<>();
        addresses.add(InetAddress.getByName("2001:db8::2"));
        addresses.add(InetAddress.getByName("192.168.1.23"));
        result.setHostAddresses(addresses);
        f.nsd.resolutions.get(0).listener.onServiceResolved(result); flush();
        check(hasHost(f.last(),"192.168.1.23"),"IPv6-first service still selects an available IPv4 address on API34+");
        Event original=f.last();
        boolean immutable=false;
        try { original.devices.clear(); } catch (UnsupportedOperationException expected) { immutable=true; }
        check(immutable,"listener cannot mutate the helper's result snapshot");
        f.found(service("Second",TYPE,null,0)); f.resolve(1,"192.168.1.24",6466);
        check(original.devices.size()==1 && f.last().devices.size()==2,"later discoveries do not mutate old snapshots");

        f.found(service("Wrong resolved service",TYPE,null,0));
        NsdManager.Resolution wrong=f.nsd.resolutions.get(2);
        wrong.listener.onServiceResolved(service("Wrong resolved service","_http._tcp.","192.168.1.25",6466)); flush();
        check(!hasHost(f.last(),"192.168.1.25"),"resolved response must still advertise the remote service type");
        f.discovery.close(); flush();
    }

    private static void legacyResolvedServiceType() throws Exception {
        Fixture f=new Fixture(26); f.start();
        f.found(service("Android 8 TV",TYPE,null,0));
        NsdManager.Resolution legacy=f.nsd.resolutions.get(0);
        legacy.listener.onServiceResolved(service("Android 8 TV","._androidtvremote2._tcp","192.168.1.26",6466)); flush();
        check(hasHost(f.last(),"192.168.1.26"),"Android 8 leading-dot resolved type is accepted");
        f.found(service("Doubled type",TYPE,null,0));
        f.nsd.resolutions.get(1).listener.onServiceResolved(
                service("Doubled type",".._androidtvremote2._tcp","192.168.1.27",6466)); flush();
        check(!hasHost(f.last(),"192.168.1.27"),"doubled leading dot is not a valid service type");
        f.found(service("Full suffix",TYPE,null,0));
        f.nsd.resolutions.get(2).listener.onServiceResolved(
                service("Full suffix","._androidtvremote2._tcp.local.","192.168.1.28",6466)); flush();
        check(!hasHost(f.last(),"192.168.1.28"),"unexpected DNS suffix is not accepted as the service type");
        f.discovery.close(); flush();
    }

    private static void duplicateAndLostServices() throws Exception {
        Fixture f=new Fixture(); f.start();
        NsdServiceInfo one=service("TV",TYPE,null,0), alias=service("TV alias",TYPE,null,0);
        f.found(one); f.resolve(0,"192.168.1.30",6466);
        f.found(alias); f.resolve(1,"192.168.1.30",6466);
        check(f.last().devices.size()==1,"multiple advertisements of one address produce one row");
        f.lost(one);
        check(f.last().devices.size()==1,"one surviving advertisement keeps a shared TV row");
        f.lost(alias);
        check(f.last().devices.isEmpty(),"losing the last advertisement removes the TV");
        int before=f.events.size(); f.lost(alias);
        check(f.last().devices.isEmpty(),"duplicate lost event cannot resurrect a TV");
        check(f.events.size()<=before+1,"duplicate loss cannot generate an unbounded callback chain");
        f.discovery.close(); flush();
    }

    private static void lostPendingAndReappearingService() throws Exception {
        Fixture f=new Fixture(); f.start();
        NsdServiceInfo first=service("first",TYPE,null,0), queued=service("queued",TYPE,null,0);
        f.found(first); f.found(queued); f.lost(queued);
        f.resolve(0,"192.168.1.40",6466);
        check(f.nsd.resolutions.size()==1,"lost queued service is removed before resolution");
        f.lost(first);
        f.found(first);
        check(f.nsd.resolutions.size()==2,"a service can reappear after loss");
        f.lost(first); f.found(first);
        f.resolve(1,"192.168.1.41",6466);
        check(f.last().devices.isEmpty(),"old pending result cannot revive a lost advertisement");
        check(f.nsd.resolutions.size()==3,"reappearing advertisement resolves after the stale operation drains");
        f.resolve(2,"192.168.1.42",6466);
        check(f.last().devices.size()==1 && hasHost(f.last(),"192.168.1.42"),
                "only the new advertisement result becomes selectable");
        f.discovery.close(); flush();
    }

    private static void boundedCandidatesAndResults() throws Exception {
        Fixture f=new Fixture(); f.start();
        for (int i=0;i<TvDiscovery.MAX_CANDIDATES+40;i++) f.found(service("TV "+i,TYPE,null,0));
        check(f.nsd.resolutions.size()==1,"candidate flood still has one resolver in flight");
        int cursor=0;
        while (cursor<f.nsd.resolutions.size()) {
            check(cursor<TvDiscovery.MAX_CANDIDATES,"candidate work stays within its fixed bound");
            f.resolve(cursor,"192.168.5."+(cursor+1),6466);
            check(f.last().devices.size()<=TvDiscovery.MAX_RESULTS,"visible result count stays bounded");
            cursor++;
        }
        check(f.nsd.resolutions.size()<=TvDiscovery.MAX_CANDIDATES,"excess advertisements are not queued");
        check(f.last().devices.size()==TvDiscovery.MAX_RESULTS,"a crowded network fills the available result rows");
        f.lost(service("TV 0",TYPE,null,0));
        f.found(service("flood after loss",TYPE,null,0));
        check(f.nsd.resolutions.size()<=TvDiscovery.MAX_CANDIDATES,"lost advertisements do not replenish the per-scan work bound");
        f.discovery.close(); flush();
    }

    private static void timeoutKeepsResultsAndReleasesResources() throws Exception {
        Fixture f=new Fixture(); f.start();
        f.found(service("TV",TYPE,null,0)); f.resolve(0,"192.168.1.50",6466);
        f.found(service("slow",TYPE,null,0));
        Handler.advance(TvDiscovery.SCAN_TIMEOUT_MS-1);
        check(f.last().scanning,"scan stays active until its deadline");
        Handler.advance(1);
        check(!f.last().scanning && hasHost(f.last(),"192.168.1.50"),"timeout preserves already found TVs");
        check(f.nsd.stops.size()==1,"timeout stops platform browsing once");
        check(f.heldLocks()==0,"timeout releases multicast resources");
        check(f.nsd.resolutionStops.size()==1,"modern Android pending resolve is cancelled on timeout");
        int events=f.events.size();
        f.resolve(1,"192.168.1.51",6466);
        f.browse().listener.onServiceFound(service("late",TYPE,null,0)); flush();
        f.browse().listener.onDiscoveryStopped(TYPE); flush();
        Handler.advance(TvDiscovery.SCAN_TIMEOUT_MS*2L);
        check(f.events.size()==events,"late callback or old timer cannot publish after timeout");
        check(f.nsd.stops.size()==1 && f.heldLocks()==0,"timeout cleanup remains idempotent");
        f.discovery.close(); flush();
    }

    private static void closeSuppressesCallbacks() throws Exception {
        Fixture f=new Fixture(); f.start();
        f.found(service("pending",TYPE,null,0));
        int events=f.events.size();
        f.discovery.close(); flush();
        check(f.events.size()==events,"close does not invoke an abandoned screen listener");
        check(f.nsd.stops.size()==1 && f.heldLocks()==0,"close stops browsing and releases its lock");
        f.resolve(0,"192.168.1.60",6466);
        f.browse().listener.onStartDiscoveryFailed(TYPE,0);
        f.browse().listener.onStopDiscoveryFailed(TYPE,0);
        f.browse().listener.onServiceLost(service("pending",TYPE,null,0)); flush();
        Handler.advance(TvDiscovery.SCAN_TIMEOUT_MS*2L);
        check(f.events.size()==events,"all pending callbacks are suppressed after close");
        f.discovery.close(); flush();
        check(f.nsd.stops.size()==1,"repeated close does not stop the same browse twice");
    }

    private static void legacyRescanDrainsOldResolution() throws Exception {
        Fixture f=new Fixture(28); f.start();
        NsdManager.Discovery oldBrowse=f.browse();
        f.found(service("old",TYPE,null,0));
        f.discovery.close(); flush();
        f.start();
        f.found(service("new",TYPE,null,0));
        check(f.nsd.resolutionStops.isEmpty(),"pre-34 Android never calls the newer cancellation API");
        check(f.nsd.resolutions.size()==1,"legacy rescan waits for its uncancellable old resolver");
        oldBrowse.listener.onServiceFound(service("stale",TYPE,null,0)); flush();
        f.resolve(0,"192.168.1.70",6466);
        check(f.last().devices.isEmpty(),"old scan resolver cannot publish into a rescan");
        check(f.nsd.resolutions.size()==2,"new queued resolve starts once old operation drains");
        f.resolve(1,"192.168.1.71",6466);
        check(f.last().devices.size()==1 && hasHost(f.last(),"192.168.1.71"),"rescan accepts only its own result");
        f.resolve(0,"192.168.1.72",6466);
        check(f.last().devices.size()==1 && !hasHost(f.last(),"192.168.1.72"),"duplicate stale completion stays ignored");
        f.discovery.close(); flush();
    }

    private static void modernRescanAndLateCallbacks() throws Exception {
        Fixture f=new Fixture(); f.start();
        NsdManager.Discovery oldBrowse=f.browse();
        f.found(service("old",TYPE,null,0));
        NsdManager.Resolution oldResolve=f.nsd.resolutions.get(0);
        f.start();
        check(f.nsd.stops.size()==1 && f.nsd.resolutionStops.size()==1,"rescan cancels its previous platform work");
        check(f.last().devices.isEmpty() && f.last().scanning,"rescan begins with clean active results");
        f.found(service("new",TYPE,null,0));
        oldResolve.listener.onResolutionStopped(oldResolve.info); flush();
        check(f.nsd.resolutions.size()==2,"resolution-stop completion releases the serial slot");
        oldBrowse.listener.onDiscoveryStopped(TYPE);
        oldBrowse.listener.onStartDiscoveryFailed(TYPE,0);
        oldBrowse.listener.onServiceFound(service("ghost",TYPE,null,0)); flush();
        f.resolve(0,"192.168.1.80",6466);
        check(f.last().scanning && f.last().devices.isEmpty(),"old browse/resolve callbacks cannot end or fill the rescan");
        f.resolve(1,"192.168.1.81",6466);
        check(f.last().devices.size()==1 && hasHost(f.last(),"192.168.1.81"),"new scan remains functional after stale callbacks");
        f.discovery.close(); flush();
    }

    private static void discoveryAndResolverFailures() throws Exception {
        Fixture f=new Fixture();
        f.nsd.discoverFailure=new SecurityException("blocked"); f.start();
        check(!f.last().scanning && f.last().message!=null,"synchronous discovery failure reports an actionable end");
        check(f.heldLocks()==0,"synchronous discovery failure releases its lock");
        int events=f.events.size(); Handler.advance(TvDiscovery.SCAN_TIMEOUT_MS*2L);
        check(f.events.size()==events,"failed discovery leaves no live timeout");
        f.discovery.close(); flush();

        f=new Fixture(); f.start();
        f.browse().listener.onStartDiscoveryFailed(TYPE,NsdManager.FAILURE_INTERNAL_ERROR); flush();
        check(!f.last().scanning && f.heldLocks()==0,"asynchronous start failure ends and releases resources");
        f.discovery.close(); flush();

        f=new Fixture(); f.start();
        f.nsd.resolveFailure=new IllegalArgumentException("bad candidate");
        f.found(service("throws",TYPE,null,0));
        check(f.last().scanning && f.last().devices.isEmpty(),"a throwing resolver leaves browsing usable");
        f.nsd.resolveFailure=null;
        f.found(service("works",TYPE,null,0)); f.resolve(1,"192.168.1.90",6466);
        check(hasHost(f.last(),"192.168.1.90"),"later candidates still resolve after a synchronous failure");
        f.nsd.stopFailure=new IllegalArgumentException("already stopped");
        Handler.advance(TvDiscovery.SCAN_TIMEOUT_MS);
        check(!f.last().scanning && f.heldLocks()==0,"stop exception cannot leave scanning or the lock active");
        check(hasHost(f.last(),"192.168.1.90"),"stop exception retains valid results");
        f.discovery.close(); flush();

        f=new Fixture(); f.start();
        f.browse().listener.onDiscoveryStopped(TYPE); flush();
        check(!f.last().scanning && f.heldLocks()==0,"unsolicited platform stop ends the scan promptly");
        f.discovery.close(); flush();
    }

    private static void unavailablePlatformServices() {
        Context noNsd=new Context() {
            @Override public Object getSystemService(String name) {
                return Context.NSD_SERVICE.equals(name)?null:super.getSystemService(name);
            }
        };
        Fixture f=new Fixture(35,noNsd); f.start();
        check(!f.last().scanning && f.last().message!=null,"missing NSD manager reports a completed failure");
        check(f.heldLocks()==0 && f.nsd.discoveries.isEmpty(),"missing NSD does not retain a multicast lock or start browsing");
        f.discovery.close(); flush();

        Context noWifi=new Context() {
            @Override public Object getSystemService(String name) {
                return Context.WIFI_SERVICE.equals(name)?null:super.getSystemService(name);
            }
        };
        f=new Fixture(35,noWifi); f.start();
        check(f.last().scanning && f.nsd.discoveries.size()==1,"missing Wi-Fi manager still permits Ethernet NSD browsing");
        f.discovery.close(); flush();

        f=new Fixture(); f.context.wifi.acquireFailure=new SecurityException("multicast unavailable"); f.start();
        check(!f.last().scanning && f.nsd.discoveries.isEmpty(),"failed lock acquisition ends before platform browsing");
        check(f.heldLocks()==0,"failed lock acquisition leaves no held resource");
        f.discovery.close(); flush();

        Context failedServices=new Context() {
            @Override public Object getSystemService(String name) { throw new IllegalStateException("service unavailable"); }
        };
        f=new Fixture(35,failedServices); f.start();
        check(!f.last().scanning && f.heldLocks()==0,"service lookup failure stays contained and completes");
        f.discovery.close(); flush();
    }

    private static void reentrantListenerAndQueuedCancellation() throws Exception {
        final Fixture closed=new Fixture();
        closed.discovery.start((devices,scanning,message) -> closed.discovery.close()); flush();
        check(closed.nsd.discoveries.isEmpty() && closed.heldLocks()==0,
                "listener can close during initial publication without starting network work");

        final Fixture replaced=new Fixture();
        replaced.discovery.start((devices,scanning,message) -> replaced.start()); flush();
        check(replaced.nsd.discoveries.size()==1 && replaced.last().scanning,
                "listener can replace an initial scan without launching the abandoned browse");
        replaced.discovery.close(); flush();

        Fixture f=new Fixture(); f.start();
        f.browse().listener.onServiceFound(service("posted but abandoned",TYPE,null,0));
        int events=f.events.size(); f.discovery.close(); flush();
        check(f.nsd.resolutions.isEmpty() && f.events.size()==events,
                "callback posted before lifecycle close cannot start a late resolution");
    }

    private static NsdServiceInfo service(String name,String type,String host,int port) throws Exception {
        NsdServiceInfo info=new NsdServiceInfo(); info.setServiceName(name); info.setServiceType(type);
        if (host!=null) info.setHost(InetAddress.getByName(host));
        info.setPort(port); return info;
    }
    private static TvDiscovery.Device find(Event event,String host) {
        for (TvDiscovery.Device device:event.devices) if (host.equals(device.host)) return device;
        return null;
    }
    private static boolean hasHost(Event event,String host) { return find(event,host)!=null; }
    private static void flush() { Handler.advance(0); }
    private static void check(boolean condition,String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static final class Event {
        final List<TvDiscovery.Device> devices; final boolean scanning; final String message;
        Event(List<TvDiscovery.Device> devices,boolean scanning,String message) {
            this.devices=devices; this.scanning=scanning; this.message=message;
        }
    }
    private static final class Fixture {
        final Context context; final NsdManager nsd; final TvDiscovery discovery;
        final List<Event> events=new ArrayList<>();
        Fixture() { this(35); }
        Fixture(int api) { this(api,new Context()); }
        Fixture(int api,Context supplied) {
            Handler.reset(); Build.VERSION.SDK_INT=api;
            context=supplied; nsd=context.nsd; discovery=new TvDiscovery(context);
        }
        void start() { discovery.start((devices,scanning,message) -> events.add(new Event(devices,scanning,message))); flush(); }
        Event last() { return events.get(events.size()-1); }
        NsdManager.Discovery browse() { return nsd.discoveries.get(nsd.discoveries.size()-1); }
        void found(NsdServiceInfo info) { browse().listener.onServiceFound(info); flush(); }
        void lost(NsdServiceInfo info) { browse().listener.onServiceLost(info); flush(); }
        void resolve(int index,String host,int port) throws Exception {
            NsdManager.Resolution attempt=nsd.resolutions.get(index);
            NsdServiceInfo result=service(attempt.info.getServiceName(),attempt.info.getServiceType(),host,port);
            attempt.listener.onServiceResolved(result); flush();
        }
        void fail(int index) {
            NsdManager.Resolution attempt=nsd.resolutions.get(index);
            attempt.listener.onResolveFailed(attempt.info,NsdManager.FAILURE_INTERNAL_ERROR); flush();
        }
        int heldLocks() {
            int count=0; for (WifiManager.MulticastLock lock:context.wifi.locks) if (lock.isHeld()) count++;
            return count;
        }
    }
}
