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
public final class PictureStateTest {
    private static int assertions;
    private static final byte[] TOKEN = PictureStateWire.unhex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final String VECTOR = "BH1|630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd|night|ec25732dbfb5835434bea4e9ac99b545f90763a5a090ec5660aaffcc6917646e";

    public static void main(String[] args) throws Exception {
        vectorsAndBounds();
        receivesOnlyAuthenticatedReply();
        rejectsWrongSender();
        cancellationAndTimeout();
        System.out.println("Picture state: " + assertions + " assertions passed");
    }

    private static void vectorsAndBounds() throws Exception {
        check("night".equals(verify(VECTOR, TOKEN)), "independent SHA256 and HMAC vector");
        for (String state : new String[] {"day", "night", "custom", "unavailable", "error"})
            check(state.equals(verify(reply(TOKEN, state), TOKEN)), "all five explicit reply states");
        for (String bad : new String[] {"", VECTOR + "|", VECTOR + "\n", VECTOR.replace("BH1", "BH2"),
                VECTOR.replace("night", "day"), VECTOR.replace("night", "unknown"),
                VECTOR.replace("ec2573", "Ec2573"), VECTOR.replace("630dcd", "730dcd"),
                VECTOR.substring(0, VECTOR.length() - 1), VECTOR + "\u00e9"})
            check(verify(bad, TOKEN) == null, "malformed or forged reply rejected");
        byte[] other = TOKEN.clone(); other[0] ^= 1;
        check(verify(VECTOR, other) == null, "replay from another request rejected");
        byte[] oversized = new byte[161]; Arrays.fill(oversized, (byte) 'a');
        check(PictureStateWire.verify(oversized, oversized.length, TOKEN) == null, "oversized datagram rejected");
        check(PictureStateWire.verify(new byte[1], 2, TOKEN) == null, "invalid received length rejected");
        String request = PictureStateWire.request(true, InetAddress.getByAddress(new byte[]{(byte)192,(byte)168,1,10}), 34567, TOKEN);
        check(request.equals("barehome://picture/toggle?reply=192.168.1.10&port=34567&token=" + PictureStateWire.hex(TOKEN)), "strict ordered toggle URI");
        check(PictureStateWire.request(false, InetAddress.getByAddress(new byte[]{10,0,2,15}), 1024, TOKEN).startsWith("barehome://picture/state?"), "read-only state URI");
        for (byte[] address : new byte[][] {{127,0,0,1}, {8,8,8,8}, {(byte)172,15,0,1}, {(byte)172,32,0,1}, {(byte)169,(byte)254,1,1}})
            check(!PictureStateWire.isPrivateV4(InetAddress.getByAddress(address)), "public/linklocal/loopback callback rejected");
        for (byte[] address : new byte[][] {{10,1,2,3}, {(byte)172,16,0,1}, {(byte)172,31,0,1}, {(byte)192,(byte)168,2,1}})
            check(PictureStateWire.isPrivateV4(InetAddress.getByAddress(address)), "RFC1918 callback accepted");
        try {
            PictureStateClient.open(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress(), 100);
            throw new AssertionError("production loopback callback accepted");
        } catch (java.io.IOException expected) { check(true, "production factory rejects nonprivate interface"); }
    }

    private static String verify(String value, byte[] token) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return PictureStateWire.verify(bytes, bytes.length, token);
    }

    private static void receivesOnlyAuthenticatedReply() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127,0,0,1});
        DatagramSocket receiver = new DatagramSocket(0, local);
        int port = receiver.getLocalPort();
        PictureStateClient client = new PictureStateClient(receiver, TOKEN, local, 500);
        try (DatagramSocket sender = new DatagramSocket(0, local)) {
            send(sender, local, port, VECTOR.replace("night", "day"));
            byte[] old = TOKEN.clone(); old[0] ^= 4;
            send(sender, local, port, reply(old, "day"));
            send(sender, local, port, new String(new char[200]).replace('\0','x'));
            send(sender, local, port, VECTOR);
            check("night".equals(client.await()), "invalid, replayed, and oversized packets ignored before valid reply");
            check(receiver.isClosed(), "valid receive closes one-shot listener");
        } finally { client.close(); }
    }

    private static void rejectsWrongSender() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127,0,0,1});
        DatagramSocket receiver = new DatagramSocket(0, local);
        int port = receiver.getLocalPort();
        PictureStateClient client = new PictureStateClient(receiver, TOKEN,
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
        PictureStateClient client = new PictureStateClient(receiver, TOKEN, local, 4000);
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
        PictureStateClient shortClient = new PictureStateClient(shortSocket, TOKEN, local, 60);
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
        String prefix = "BH1|" + PictureStateWire.hex(MessageDigest.getInstance("SHA-256").digest(token)) + "|" + state;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        return prefix + "|" + PictureStateWire.hex(mac.doFinal(prefix.getBytes(StandardCharsets.US_ASCII)));
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
