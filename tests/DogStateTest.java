package uy.nico.bareremote;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Fixed independent crypto vector plus real loopback UDP, cancellation and deadlines. */
public final class DogStateTest {
    private static int assertions;
    private static final byte[] TOKEN = DogStateWire.unhex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final String VECTOR = "BH2|630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd|active|5791310f313bcd83266b3f9d6c86e902a2cee9787a77e85ca5f229d357839110";

    public static void main(String[] args) throws Exception {
        vectorsAndBounds();
        startupFailureRepliesAreFiniteAndAuthenticated();
        startProgressWaitsForFinalReply();
        receivesOnlyAuthenticatedReply();
        rejectsWrongSender();
        cancellationAndTimeout();
        System.out.println("Dog Mode state: " + assertions + " assertions passed");
    }

    private static void vectorsAndBounds() throws Exception {
        check("active".equals(verify(VECTOR, TOKEN)), "independent SHA256 and HMAC vector");
        for (String state : new String[] {"off", "starting", "active", "restore_required", "unavailable", "error"})
            check(state.equals(verify(reply(TOKEN, state), TOKEN)), "regular explicit reply states");
        for (String bad : new String[] {"", VECTOR + "|", VECTOR + "\n", VECTOR.replace("BH2", "BH1"),
                VECTOR.replace("active", "off"), VECTOR.replace("active", "unknown"),
                VECTOR.replace("579131", "A79131"), VECTOR.replace("630dcd", "730dcd"),
                VECTOR.substring(0, VECTOR.length() - 1), VECTOR + "\u00e9"})
            check(verify(bad, TOKEN) == null, "malformed or forged reply rejected");
        byte[] other = TOKEN.clone(); other[0] ^= 1;
        check(verify(VECTOR, other) == null, "replay from another request rejected");
        byte[] oversized = new byte[177]; Arrays.fill(oversized, (byte) 'a');
        check(DogStateWire.verify(oversized, oversized.length, TOKEN) == null, "oversized datagram rejected");
        check(DogStateWire.verify(new byte[1], 2, TOKEN) == null, "invalid received length rejected");
        String request = DogStateWire.request("start", InetAddress.getByAddress(new byte[]{(byte)192,(byte)168,1,10}), 34567, TOKEN);
        check(request.equals("barehome://dog/start?reply=192.168.1.10&port=34567&token=" + DogStateWire.hex(TOKEN)), "strict ordered toggle URI");
        check(DogStateWire.request("state", InetAddress.getByAddress(new byte[]{10,0,2,15}), 1024, TOKEN).startsWith("barehome://dog/state?"), "read-only state URI");
        check(DogStateWire.request("stop", InetAddress.getByAddress(new byte[]{10,0,2,15}), 1024, TOKEN).startsWith("barehome://dog/stop?"), "fixed restoration URI");
        try { DogStateWire.request("toggle", InetAddress.getByAddress(new byte[]{10,0,2,15}), 1024, TOKEN); throw new AssertionError("arbitrary action accepted"); }
        catch (IllegalArgumentException expected) { check(true, "unknown mutation rejected"); }
        for (byte[] address : new byte[][] {{127,0,0,1}, {8,8,8,8}, {(byte)172,15,0,1}, {(byte)172,32,0,1}, {(byte)169,(byte)254,1,1}})
            check(!DogStateWire.isPrivateV4(InetAddress.getByAddress(address)), "public/linklocal/loopback callback rejected");
        for (byte[] address : new byte[][] {{10,1,2,3}, {(byte)172,16,0,1}, {(byte)172,31,0,1}, {(byte)192,(byte)168,2,1}})
            check(DogStateWire.isPrivateV4(InetAddress.getByAddress(address)), "RFC1918 callback accepted");
        try {
            DogStateClient.open(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress(), 100);
            throw new AssertionError("production loopback callback accepted");
        } catch (java.io.IOException expected) { check(true, "production factory rejects nonprivate interface"); }
    }

    private static String verify(String value, byte[] token) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return DogStateWire.verify(bytes, bytes.length, token);
    }

    private static void startupFailureRepliesAreFiniteAndAuthenticated() throws Exception {
        // Independent Python hashlib/hmac vectors; these replies assert completed rollback.
        String[][] failures = {
                {"nsnow_access", "a1702bc1bb99f9038ecc14f7f78630a7487af2dc9e3aaaa082b11c69e174fbfd", "controls"},
                {"nsnow_menu", "9cad0b91291eda2512b1d51197c6ae25998c98fef07cabf9b252e2b823097336", "TV EN VIVO"},
                {"nsnow_channel", "fc8d3dcb84167f55b481f46005541ee9213a2b50fa69aa8df71ae710fad30091", "select CANAL 10"},
                {"nsnow_player", "a30a700782aba47aed9d685b96f3615d09552589cb2f8e6e20d122a9514e580c", "confirm CANAL 10"},
                {"nsnow_debug", "74e073aa5618d7eb24a5427212e8f41069704b8f6b13a4073ced969641c30258", "USB debugging"},
                {"nsnow_target", "3b4c7ca8fa9fd737751936121f8ff9072a0d82a878b2e3919106c4efae987743", "could not be identified"},
                {"nsnow_input", "c0eddece5fe09f8f29e43390c730d37e4747783837b3c9dd15c20adeae95aa21", "did not accept"}};
        String requestID = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd";
        for (String[] item : failures) {
            String value = "BH2|" + requestID + "|" + item[0] + "|" + item[1];
            check(item[0].equals(verify(value, TOKEN)), "authenticated finite startup failure accepted");
            check(verify(value.replace(item[0], "error"), TOKEN) == null, "failure state remains authenticated");
            String message = DogStateWire.startupFailureMessage(item[0]);
            check(message.contains(item[2]) && message.contains("were restored and the helper is off"),
                    "verified rollback failure has actionable finite text");
        }
        for (String state : new String[] {"nsnow_unknown", "nsnow_access:details", "nsnow_debug_extra"})
            check(verify(reply(TOKEN, state), TOKEN) == null, "correct MAC cannot admit an arbitrary diagnostic state");
        for (String state : new String[] {"error", "unknown", "restore_required", "off"})
            check(DogStateWire.startupFailureMessage(state) == null, "other states cannot assert startup rollback");
    }

    private static void startProgressWaitsForFinalReply() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127,0,0,1});
        DatagramSocket receiver = new DatagramSocket(0, local);
        DogStateClient client = new DogStateClient(receiver, TOKEN, local, 500);
        java.util.List<String> progress = new java.util.ArrayList<>();
        try (DatagramSocket sender = new DatagramSocket(0, local)) {
            send(sender, local, receiver.getLocalPort(), reply(TOKEN, "starting"));
            send(sender, local, receiver.getLocalPort(), VECTOR);
            check("active".equals(client.await(true, progress::add)), "Start continues after progress to final active reply");
            check(progress.equals(java.util.Arrays.asList("starting")), "only authenticated intermediate state reaches progress callback");
            check(receiver.isClosed(), "final startup confirmation closes callback");
        } finally { client.close(); }
        DatagramSocket querySocket = new DatagramSocket(0, local);
        DogStateClient query = new DogStateClient(querySocket, TOKEN, local, 500);
        try (DatagramSocket sender = new DatagramSocket(0, local)) {
            send(sender, local, querySocket.getLocalPort(), reply(TOKEN, "starting"));
            check("starting".equals(query.await()), "State query returns a starting snapshot without waiting for completion");
        } finally { query.close(); }
        DatagramSocket uncertainSocket = new DatagramSocket(0, local);
        DogStateClient uncertain = new DogStateClient(uncertainSocket, TOKEN, local, 60);
        try (DatagramSocket sender = new DatagramSocket(0, local)) {
            send(sender, local, uncertainSocket.getLocalPort(), reply(TOKEN, "starting"));
            long began = System.nanoTime();
            try { uncertain.await(true, null); throw new AssertionError("progress treated as final success"); }
            catch (SocketTimeoutException expected) {
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began) < 500, "starting cannot extend the absolute deadline");
            }
            check(uncertainSocket.isClosed(), "uncertain startup closes callback for explicit status or restore");
        } finally { uncertain.close(); }
        DatagramSocket longSocket = new DatagramSocket(0, local);
        DogStateClient longRequest = new DogStateClient(longSocket, TOKEN, local, 35000);
        longRequest.close();
        check(longSocket.isClosed(), "35-second Start budget remains immediately cancellable");
    }

    private static void receivesOnlyAuthenticatedReply() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127,0,0,1});
        DatagramSocket receiver = new DatagramSocket(0, local);
        int port = receiver.getLocalPort();
        DogStateClient client = new DogStateClient(receiver, TOKEN, local, 500);
        try (DatagramSocket sender = new DatagramSocket(0, local)) {
            send(sender, local, port, VECTOR.replace("active", "off"));
            byte[] old = TOKEN.clone(); old[0] ^= 4;
            send(sender, local, port, reply(old, "off"));
            send(sender, local, port, new String(new char[200]).replace('\0','x'));
            send(sender, local, port, VECTOR);
            check("active".equals(client.await()), "invalid, replayed, and oversized packets ignored before valid reply");
            check(receiver.isClosed(), "valid receive closes one-shot listener");
        } finally { client.close(); }
    }

    private static void rejectsWrongSender() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127,0,0,1});
        DatagramSocket receiver = new DatagramSocket(0, local);
        int port = receiver.getLocalPort();
        DogStateClient client = new DogStateClient(receiver, TOKEN,
                InetAddress.getByAddress(new byte[]{127,0,0,2}), 60);
        try (DatagramSocket sender = new DatagramSocket(0, local)) {
            send(sender, local, port, VECTOR);
            try { client.await(); throw new AssertionError("wrong sender accepted"); }
            catch (SocketTimeoutException expected) { check(true, "correct MAC from wrong sender rejected"); }
            check(receiver.isClosed(), "timeout closes listener");
        } finally { client.close(); }
    }

    private static void cancellationAndTimeout() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127,0,0,1});
        DatagramSocket receiver = new DatagramSocket(0, local);
        DogStateClient client = new DogStateClient(receiver, TOKEN, local, 4000);
        CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            entered.countDown();
            try { client.await(); } catch (Exception failure) { error.set(failure); }
            finally { exited.countDown(); }
        });
        worker.start();
        check(entered.await(1, TimeUnit.SECONDS), "receive worker entered");
        client.close();
        check(exited.await(500, TimeUnit.MILLISECONDS), "cancellation unblocks UDP immediately");
        check(error.get() != null && receiver.isClosed(), "cancelled request cannot succeed");
        DatagramSocket shortSocket = new DatagramSocket(0, local);
        DogStateClient shortClient = new DogStateClient(shortSocket, TOKEN, local, 60);
        long start = System.nanoTime();
        try { shortClient.await(); throw new AssertionError("empty callback succeeded"); }
        catch (SocketTimeoutException expected) { check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 500, "bounded socket timeout"); }
        check(shortSocket.isClosed(), "empty response timeout releases port");
    }

    private static void send(DatagramSocket socket, InetAddress address, int port, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
    }

    private static String reply(byte[] token, String state) throws Exception {
        String prefix = "BH2|" + DogStateWire.hex(MessageDigest.getInstance("SHA-256").digest(token)) + "|" + state;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        return prefix + "|" + DogStateWire.hex(mac.doFinal(prefix.getBytes(StandardCharsets.US_ASCII)));
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
