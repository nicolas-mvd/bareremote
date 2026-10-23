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
import java.util.function.Consumer;

/** One bounded UDP listener; Start accepts authenticated progress before its final reply. Never retries a mutation. */
final class DogStateClient implements Closeable {
    private final DatagramSocket socket;
    private final byte[] token;
    private final InetAddress peer;
    private final InetAddress local;
    private final int port;
    private final long deadline;

    static DogStateClient open(InetAddress local, InetAddress peer, int timeoutMillis)
            throws IOException {
        if (!DogStateWire.isPrivateV4(local) || !DogStateWire.isPrivateV4(peer))
            throw new IOException("Dog Mode needs a local IPv4 network");
        byte[] token = new byte[DogStateWire.TOKEN_BYTES];
        new SecureRandom().nextBytes(token);
        DatagramSocket socket = new DatagramSocket(null);
        try {
            socket.bind(new InetSocketAddress(local, 0));
            if (socket.getLocalPort() < 1024) throw new IOException("Invalid callback port");
            return new DogStateClient(socket, token, peer, timeoutMillis);
        } catch (IOException | RuntimeException failure) {
            socket.close();
            throw failure;
        }
    }

    // Socket-injected constructor permits actual loopback tests without weakening open().
    DogStateClient(DatagramSocket socket, byte[] token, InetAddress peer, int timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 35000 || token.length != DogStateWire.TOKEN_BYTES)
            throw new IllegalArgumentException("Invalid Dog Mode reply budget");
        this.socket = socket;
        this.token = token.clone();
        this.peer = peer;
        this.local = socket.getLocalAddress();
        this.port = socket.getLocalPort();
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    String request(String action) {
        return DogStateWire.request(action, local, port, token);
    }

    String await() throws IOException { return await(false, null); }

    String await(boolean waitForFinal, Consumer<String> progress) throws IOException {
        try { return receive(waitForFinal, progress); }
        finally { close(); }
    }

    private String receive(boolean waitForFinal, Consumer<String> progress) throws IOException {
        byte[] bytes = new byte[DogStateWire.MAX_REPLY_BYTES + 1];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        for (;;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Dog Mode confirmation timed out");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            packet.setLength(bytes.length);
            socket.receive(packet);
            if (!MessageDigest.isEqual(peer.getAddress(), packet.getAddress().getAddress())) continue;
            try {
                String state = DogStateWire.verify(bytes, packet.getLength(), token);
                if (state != null) {
                    if (waitForFinal && "starting".equals(state)) {
                        if (progress != null) progress.accept(state);
                    } else return state;
                }
            } catch (GeneralSecurityException unavailable) {
                throw new IOException("Dog Mode confirmation authentication unavailable", unavailable);
            }
        }
    }

    @Override public void close() { socket.close(); }
}
