package uy.nico.bareremote;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** Blocking local-network protocol. Call connection and command methods off the UI thread. */
public final class TvProtocol {
    public static final int REMOTE_PORT = 6466;
    public static final int PAIRING_PORT = 6467;
    public static final int REMOTE_CONNECT_TIMEOUT_MS = 5000;
    public static final int DELIVERY_WINDOW_MS = 750;
    public static final int INPUT_READY_DELAY_MS = 250;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int HANDSHAKE_TIMEOUT_MS = 6000;
    private static final int REMOTE_IDLE_TIMEOUT_MS = 16000;
    private static final int FEATURES = 1 | 2 | 32 | 64 | 512; // Ping, keys, power, volume, app links. No IME/voice.
    // Conscrypt close() can send close_notify. Never run TLS shutdown on an Android UI caller.
    // Raw transports close immediately; this bounded native cleanup worker disappears when idle.
    private static final ThreadPoolExecutor TLS_CLEANUP = new ThreadPoolExecutor(0, 1, 1,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32), new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "BareRemote-TLS-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }
            }, new ThreadPoolExecutor.DiscardPolicy());

    private TvProtocol() {}

    public interface Identity {
        /** null permits initial pairing; non-null MUST authenticate this exact server DER. */
        SSLContext context(byte[] pinnedServerCertificate) throws Exception;
    }

    public static final class Pairing implements Closeable {
        private final Connection connection;
        private final SSLSocket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Certificate clientCertificate;
        private final Certificate serverCertificate;
        private boolean finished;

        private Pairing(Connection connection) throws Exception {
            this.connection = connection;
            this.socket = connection.socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
            Certificate[] local = socket.getSession().getLocalCertificates();
            Certificate[] peer = socket.getSession().getPeerCertificates();
            if (local == null || local.length == 0 || peer.length == 0) {
                throw new IOException("TLS client certificate was not accepted");
            }
            clientCertificate = local[0];
            serverCertificate = peer[0];
        }

        /** Returns when the TV is displaying a code. No pairing code is logged or retained. */
        public static Pairing start(String host, int port, String clientName, Identity identity)
                throws Exception {
            return start(host, port, clientName, identity, new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return false; }
            });
        }

        /** Cancellation suppresses later handshake requests if the app leaves pairing. */
        public static Pairing start(String host, int port, String clientName, Identity identity,
                BooleanSupplier cancelled) throws Exception {
            if (clientName == null || clientName.length() == 0 || clientName.length() > 80) {
                throw new IllegalArgumentException("Invalid remote name");
            }
            checkCancelled(cancelled);
            Connection connection = connectSocket(host, port, null, identity,
                    deadline(HANDSHAKE_TIMEOUT_MS));
            try {
                checkCancelled(cancelled);
                Pairing pairing = new Pairing(connection);
                checkCancelled(cancelled);
                pairing.send(10, new Proto.Writer().text(1, "atvremote").text(2, clientName));
                pairing.expect(11);
                Proto.Writer encoding = new Proto.Writer().number(1, 3).number(2, 6);
                checkCancelled(cancelled);
                pairing.send(20, new Proto.Writer().message(1, encoding).number(3, 1));
                pairing.expect(20);
                checkCancelled(cancelled);
                pairing.send(30, new Proto.Writer().message(1, encoding).number(2, 1));
                pairing.expect(31);
                checkCancelled(cancelled);
                return pairing;
            } catch (Exception failure) {
                connection.close();
                throw failure;
            }
        }

        /** Returns the server certificate to persist ONLY after a successful SECRET_ACK. */
        public synchronized byte[] finish(String sixHex) throws Exception {
            if (finished || !connection.isOpen()) throw new IOException("Pairing session is closed");
            // A malformed/mistyped local code can be corrected without restarting the TV dialog.
            byte[] secret = pairingSecret(clientCertificate.getPublicKey(),
                    serverCertificate.getPublicKey(), sixHex);
            try {
                send(40, new Proto.Writer().bytes(1, secret));
                Proto.Message ack = expect(41);
                byte[] echo = ack.bytes(1);
                // Google's original implementation allows servers that omit the echo.
                if (echo != null && echo.length != 0 && !MessageDigest.isEqual(secret, echo)) {
                    throw new IOException("TV did not acknowledge the pairing secret");
                }
                finished = true;
                return serverCertificate.getEncoded();
            } finally {
                Arrays.fill(secret, (byte) 0);
                close();
            }
        }

        private void send(int field, Proto.Writer body) throws IOException {
            Proto.writeFrame(out, new Proto.Writer().number(1, 2).number(2, 200)
                    .message(field, body).build());
        }

        private Proto.Message expect(int field) throws IOException {
            Proto.Message response = Proto.parse(readFrame(socket, in, deadline(HANDSHAKE_TIMEOUT_MS)));
            if (response.integer(1, -1) != 2 || response.integer(2, -1) != 200) {
                throw new IOException("TV rejected pairing; start again and check the code");
            }
            return response.message(field);
        }

        @Override public void close() {
            connection.close();
        }

        private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
            if (cancelled == null || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("Pairing was cancelled");
            }
        }
    }

    public static final class Remote implements Closeable {
        private final Connection connection;
        private final SSLSocket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object outputLock = new Object();
        private final Object keyLock = new Object();
        private final Object deliveryLock = new Object();
        private volatile boolean closed;
        private long lastKeyWriteNanos;
        private volatile int features;
        private boolean configured;

        private Remote(Connection connection) throws IOException {
            this.connection = connection;
            this.socket = connection.socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        /** Connects only to a previously paired server and waits until it can receive keys. */
        public static Remote connect(String host, int port, byte[] pinnedCertificate, Identity identity)
                throws Exception {
            if (pinnedCertificate == null || pinnedCertificate.length == 0) {
                throw new IllegalArgumentException("Pair with the TV first");
            }
            long deadline = deadline(REMOTE_CONNECT_TIMEOUT_MS);
            Connection connection = connectSocket(host, port, pinnedCertificate, identity, deadline);
            SSLSocket socket = connection.socket;
            try {
                final Remote remote = new Remote(connection);
                for (int frames = 0; ; frames++) {
                    if (frames >= 64) throw new IOException("Too many TV handshake messages");
                    if (remote.handle(Proto.parse(readFrame(socket, remote.in, deadline)))) break;
                }
                socket.setSoTimeout(REMOTE_IDLE_TIMEOUT_MS);
                Thread reader = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            while (!remote.closed) remote.handle(Proto.parse(readFrame(
                                    remote.socket, remote.in, deadline(REMOTE_IDLE_TIMEOUT_MS))));
                        } catch (IOException failure) {
                            remote.close();
                        }
                    }
                }, "BareRemote-TV");
                reader.setDaemon(true);
                reader.start();
                remote.awaitInputReady(deadline);
                return remote;
            } catch (Exception failure) {
                connection.close();
                throw failure;
            }
        }

        public boolean isOpen() {
            return !closed && connection.isOpen();
        }

        /** START can precede Android InputReader registering the TV's virtual remote. */
        private void awaitInputReady(long connectionDeadline) throws IOException {
            long inputReadyAt = System.nanoTime() + INPUT_READY_DELAY_MS * 1000000L;
            synchronized (deliveryLock) {
                for (;;) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("TV activation wait interrupted");
                    }
                    if (!isOpen()) throw new IOException("TV disconnected while activating the remote");
                    long now = System.nanoTime();
                    if (now >= connectionDeadline) throw new java.net.SocketTimeoutException("TV activation timed out");
                    long remaining = inputReadyAt - now;
                    if (remaining <= 0) return;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(deliveryLock, Math.min(remaining, connectionDeadline - now));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("TV activation wait interrupted");
                    }
                }
            }
        }

        public void sendKey(int androidKeyCode) throws IOException {
            synchronized (keyLock) {
                sendKeyDirection(androidKeyCode, 3);
            }
        }

        /** The public API exposes only fixed BareHome commands, never arbitrary links. */
        public void sendPictureMode(boolean night) throws IOException {
            sendBareHomeLink(night ? "barehome://picture/night" : "barehome://picture/day");
        }

        public void sendInputPicker() throws IOException {
            sendBareHomeLink("barehome://inputs");
        }

        PictureStateClient openPictureStateRequest(int timeoutMillis) throws IOException {
            if ((features & 512) == 0)
                throw new UnsupportedOperationException("This TV remote service does not support BareHome commands.");
            return PictureStateClient.open(socket.getLocalAddress(), socket.getInetAddress(), timeoutMillis);
        }

        void sendPictureStateRequest(PictureStateClient request, boolean toggle) throws IOException {
            sendBareHomeLink(request.request(toggle));
        }

        private void sendBareHomeLink(String link) throws IOException {
            synchronized (keyLock) {
                if ((features & 512) == 0) {
                    throw new UnsupportedOperationException("This TV remote service does not support BareHome commands.");
                }
                send(new Proto.Writer().message(90, new Proto.Writer().text(1, link)));
                synchronized (deliveryLock) {
                    lastKeyWriteNanos = System.nanoTime();
                    deliveryLock.notifyAll();
                }
            }
        }

        /**
         * Keep a one-command session alive while the TV dispatches its queued input.
         * Worker-only: waits at most 750 ms from the latest successful key write while
         * the reader continues answering pings. This is a grace period, not a key ACK.
         * Cancellation/close interrupts the wait; no key is sent or retried here.
         */
        public void awaitCommandDeliveryWindow() throws IOException {
            synchronized (deliveryLock) {
                if (lastKeyWriteNanos == 0) throw new IllegalStateException("No command has been sent");
                for (;;) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Command delivery wait interrupted");
                    }
                    if (!isOpen()) throw new IOException("TV disconnected after send; delivery is uncertain");
                    long remaining = lastKeyWriteNanos + DELIVERY_WINDOW_MS * 1000000L - System.nanoTime();
                    if (remaining <= 0) return;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(deliveryLock, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Command delivery wait interrupted");
                    }
                }
            }
        }

        /** Sends a bounded 650 ms press and releases it even if the worker is interrupted. */
        public void sendLongKey(int androidKeyCode) throws IOException {
            synchronized (keyLock) {
                sendKeyDirection(androidKeyCode, 1);
                boolean interrupted = false;
                try {
                    Thread.sleep(650);
                } catch (InterruptedException stop) {
                    interrupted = true;
                } finally {
                    try {
                        sendKeyDirection(androidKeyCode, 2);
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void sendKeyDirection(int key, int direction) throws IOException {
            if (key < 1 || key > 304) throw new IllegalArgumentException("Unsupported Android key");
            send(new Proto.Writer().message(10, new Proto.Writer().number(1, key).number(2, direction)));
            synchronized (deliveryLock) {
                lastKeyWriteNanos = System.nanoTime();
                deliveryLock.notifyAll();
            }
        }

        private boolean handle(Proto.Message message) throws IOException {
            if (message.has(3)) throw new IOException("TV reported a remote error");
            if (message.has(1)) {
                long supported = message.message(1).integer(1, 0);
                features = (int) supported & FEATURES;
                if ((features & 2) == 0) throw new IOException("TV remote service does not support keys");
                Proto.Writer device = new Proto.Writer().number(3, 1).text(4, "1")
                        .text(5, "uy.nico.bareremote").text(6, "0.4.0");
                send(new Proto.Writer().message(1,
                        new Proto.Writer().number(1, features).message(2, device)));
                configured = true;
            }
            if (message.has(2)) {
                if (!configured) throw new IOException("TV remote configuration missing");
                message.message(2); // Validate the known nested message before answering.
                send(new Proto.Writer().message(2, new Proto.Writer().number(1, features)));
            }
            if (message.has(8)) {
                long ping = message.message(8).integer(1, 0);
                send(new Proto.Writer().message(9, new Proto.Writer().number(1, ping)));
            }
            if (message.has(40)) {
                if (!configured) throw new IOException("TV remote configuration missing");
                message.message(40);
                // started=false means standby, but the protocol is ready for a wake command.
                return true;
            }
            return false;
        }

        private void send(Proto.Writer message) throws IOException {
            synchronized (outputLock) {
                if (!isOpen()) throw new IOException("TV connection is closed");
                try {
                    Proto.writeFrame(out, message.build());
                } catch (IOException failure) {
                    close();
                    throw failure;
                }
            }
        }

        @Override public void close() {
            closed = true;
            connection.close();
            synchronized (deliveryLock) { deliveryLock.notifyAll(); }
        }
    }

    static byte[] pairingSecret(PublicKey client, PublicKey server, String sixHex) throws Exception {
        if (sixHex == null || !sixHex.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Enter the six characters shown on your TV");
        }
        if (!(client instanceof RSAPublicKey) || !(server instanceof RSAPublicKey)) {
            throw new IOException("TV pairing requires RSA certificates");
        }
        RSAPublicKey local = (RSAPublicKey) client;
        RSAPublicKey peer = (RSAPublicKey) server;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsigned(local.getModulus()));
        digest.update(unsigned(local.getPublicExponent()));
        digest.update(unsigned(peer.getModulus()));
        digest.update(unsigned(peer.getPublicExponent()));
        digest.update((byte) Integer.parseInt(sixHex.substring(2, 4), 16));
        digest.update((byte) Integer.parseInt(sixHex.substring(4, 6), 16));
        byte[] secret = digest.digest();
        if ((secret[0] & 0xff) != Integer.parseInt(sixHex.substring(0, 2), 16)) {
            Arrays.fill(secret, (byte) 0);
            throw new IllegalArgumentException("Code does not match this TV; check all six characters");
        }
        return secret;
    }

    static byte[] unsigned(BigInteger value) {
        if (value.signum() <= 0) throw new IllegalArgumentException("RSA values must be positive");
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static Connection connectSocket(String host, int port, byte[] pin, Identity identity,
            long deadline)
            throws Exception {
        if (host == null || host.length() == 0 || host.length() > 253 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid TV address");
        }
        if (!isLiteralAddress(host)) {
            throw new IllegalArgumentException("Enter the TV's IP address, not a hostname");
        }
        byte[] expected = pin == null ? null : pin.clone();
        SSLContext context = identity.context(expected);
        Socket transport = new Socket();
        Connection connection = null;
        try {
            // Closing a plain socket with linger disabled aborts blocked I/O without a TLS write.
            transport.setSoLinger(false, 0);
            transport.setTcpNoDelay(true);
            transport.connect(new InetSocketAddress(host, port), Math.min(CONNECT_TIMEOUT_MS, remaining(deadline)));
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(transport, host, port, true);
            connection = new Connection(transport, socket);
            socket.setUseClientMode(true);
            // TLS 1.2 avoids platform-specific TLS 1.3 client-certificate interoperability issues.
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setSoTimeout(remaining(deadline));
            socket.startHandshake();
            // Defense in depth: verify the pin even if an Identity implementation is too permissive.
            if (expected != null && !MessageDigest.isEqual(expected,
                    socket.getSession().getPeerCertificates()[0].getEncoded())) {
                throw new IOException("TV identity changed; pair again from the app");
            }
            return connection;
        } catch (Exception failure) {
            if (connection != null) connection.close();
            else closeTransport(transport);
            throw failure;
        }
    }

    private static final class Connection implements Closeable {
        final Socket transport;
        final SSLSocket socket;
        final AtomicBoolean closed = new AtomicBoolean();

        Connection(Socket transport, SSLSocket socket) {
            this.transport = transport;
            this.socket = socket;
        }

        boolean isOpen() {
            return !closed.get() && !transport.isClosed() && !socket.isClosed();
        }

        /** Safe from the UI thread: interrupt TCP first; TLS cleanup never runs on the caller. */
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeTransport(transport);
            TLS_CLEANUP.execute(new Runnable() {
                @Override public void run() {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            });
        }
    }

    private static void closeTransport(Socket transport) {
        try { transport.close(); } catch (IOException ignored) { }
    }

    private static boolean isLiteralAddress(String host) {
        if (host.indexOf(':') >= 0) {
            return host.matches("[0-9a-fA-F:.]+(%[a-zA-Z0-9_.-]+)?");
        }
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String octet : host.split("\\.")) {
            if (Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static long deadline(int milliseconds) {
        return System.nanoTime() + milliseconds * 1000000L;
    }

    private static int remaining(long deadline) throws IOException {
        long left = (deadline - System.nanoTime()) / 1000000L;
        if (left <= 0) throw new java.net.SocketTimeoutException("TV connection timed out");
        return (int) Math.min(left, Integer.MAX_VALUE);
    }

    private static byte[] readFrame(final SSLSocket socket, InputStream in, final long deadline)
            throws IOException {
        return Proto.readFrame(new FilterInputStream(in) {
            @Override public int read() throws IOException {
                socket.setSoTimeout(remaining(deadline));
                return in.read();
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                socket.setSoTimeout(remaining(deadline));
                return in.read(bytes, offset, length);
            }
        });
    }
}
