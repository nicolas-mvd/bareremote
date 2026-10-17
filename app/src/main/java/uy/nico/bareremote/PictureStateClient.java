package uy.nico.bareremote;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/** One UDP listener, one authenticated reply, at most four seconds. Never retries a toggle. */
final class PictureStateClient implements Closeable {
    private final DatagramSocket socket;
    private final byte[] token;
    private final InetAddress peer;
    private final InetAddress local;
    private final int port;
    private final long deadline;

    static PictureStateClient open(InetAddress local, InetAddress peer, int timeoutMillis)
            throws IOException {
        if (!PictureStateWire.isPrivateV4(local) || !PictureStateWire.isPrivateV4(peer))
            throw new IOException("Picture sync needs a local IPv4 network");
        byte[] token = new byte[PictureStateWire.TOKEN_BYTES];
        new SecureRandom().nextBytes(token);
        DatagramSocket socket = new DatagramSocket(null);
        try {
            socket.bind(new InetSocketAddress(local, 0));
            if (socket.getLocalPort() < 1024) throw new IOException("Invalid callback port");
            return new PictureStateClient(socket, token, peer, timeoutMillis);
        } catch (IOException | RuntimeException failure) {
            socket.close();
            throw failure;
        }
    }

    // Socket-injected constructor permits actual loopback tests without weakening open().
    PictureStateClient(DatagramSocket socket, byte[] token, InetAddress peer, int timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 4000 || token.length != PictureStateWire.TOKEN_BYTES)
            throw new IllegalArgumentException("Invalid picture reply budget");
        this.socket = socket;
        this.token = token.clone();
        this.peer = peer;
        this.local = socket.getLocalAddress();
        this.port = socket.getLocalPort();
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    String request(boolean toggle) {
        return PictureStateWire.request(toggle, local, port, token);
    }

    String await() throws IOException {
        try { return receive(); }
        finally { close(); }
    }

    private String receive() throws IOException {
        byte[] bytes = new byte[PictureStateWire.MAX_REPLY_BYTES + 1];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        for (;;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Picture confirmation timed out");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            packet.setLength(bytes.length);
            socket.receive(packet);
            if (!MessageDigest.isEqual(peer.getAddress(), packet.getAddress().getAddress())) continue;
            try {
                String state = PictureStateWire.verify(bytes, packet.getLength(), token);
                if (state != null) return state;
            } catch (GeneralSecurityException unavailable) {
                throw new IOException("Picture confirmation authentication unavailable", unavailable);
            }
        }
    }

    @Override public void close() { socket.close(); }
}
