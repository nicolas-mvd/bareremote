package uy.nico.bareremote;

import java.io.InputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Loopback-only fake TV for testing an Android emulator's real Keystore/TLS/widget path. */
public final class FakeTv {
    private static final Set<String> pairedClients = Collections.synchronizedSet(new HashSet<String>());
    private static final ExecutorService connections = Executors.newFixedThreadPool(4);
    private static final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private static final SecureRandom random = new SecureRandom();
    private static final AtomicInteger sequence = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("FakeTv test.p12 PAIRING_PORT REMOTE_PORT");
        SSLContext context = context(args[0]);
        serve(context, Integer.parseInt(args[1]), true);
        serve(context, Integer.parseInt(args[2]), false);
        System.out.println("FAKE_TV_READY loopback=127.0.0.1 emulator=10.0.2.2");
        System.out.flush();
        new CountDownLatch(1).await();
    }

    private static void serve(SSLContext context, int port, final boolean pairing) throws Exception {
        final SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        listener.setNeedClientAuth(true);
        listener.setEnabledProtocols(new String[] {"TLSv1.2"});
        Thread accept = new Thread(new Runnable() {
            @Override public void run() {
                while (!listener.isClosed()) {
                    try {
                        final SSLSocket socket = (SSLSocket) listener.accept();
                        final int session = sequence.incrementAndGet();
                        connections.execute(new Runnable() {
                            @Override public void run() {
                                try (SSLSocket owned = socket) {
                                    owned.setSoTimeout(120000);
                                    owned.setTcpNoDelay(true);
                                    owned.startHandshake();
                                    if (pairing) pair(owned, session);
                                    else remote(owned, session);
                                } catch (Exception failure) {
                                    // Test diagnostics never print keys, certificates or the pairing hash.
                                    System.out.println("SESSION_CLOSED id=" + session + " type="
                                            + (pairing ? "pairing" : "remote") + " reason="
                                            + failure.getClass().getSimpleName()
                                            + (failure instanceof javax.net.ssl.SSLException ? " detail=" + failure.getMessage() : "")
                                            + " at_ms=" + elapsedMs());
                                }
                            }
                        });
                    } catch (IOException failure) {
                        System.out.println("LISTENER_STOPPED port=" + listener.getLocalPort());
                        return;
                    }
                }
            }
        }, pairing ? "FakeTV-pairing" : "FakeTV-remote");
        accept.setDaemon(true);
        accept.start();
    }

    private static void pair(SSLSocket socket, int session) throws Exception {
        Proto.Message request = read(socket);
        requirePairing(request, 10);
        byte[] service = request.message(10).bytes(1);
        if (service == null || !new String(service, "UTF-8").equals("atvremote")) {
            throw new IOException("Wrong pairing service");
        }
        pairReply(socket, 11, new Proto.Writer().text(1, "BareRemote Fake TV"));
        requirePairing(read(socket), 20);
        pairReply(socket, 20, new Proto.Writer().message(2,
                new Proto.Writer().number(1, 3).number(2, 6)).number(3, 2));
        requirePairing(read(socket), 30);

        RSAPublicKey client = (RSAPublicKey) socket.getSession().getPeerCertificates()[0].getPublicKey();
        RSAPublicKey server = (RSAPublicKey) socket.getSession().getLocalCertificates()[0].getPublicKey();
        byte[] nonce = new byte[2];
        random.nextBytes(nonce);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(TvProtocol.unsigned(client.getModulus()));
        digest.update(TvProtocol.unsigned(client.getPublicExponent()));
        digest.update(TvProtocol.unsigned(server.getModulus()));
        digest.update(TvProtocol.unsigned(server.getPublicExponent()));
        digest.update(nonce);
        byte[] secret = digest.digest();
        String code = String.format("%02X%02X%02X", secret[0] & 255, nonce[0] & 255, nonce[1] & 255);
        System.out.println("PAIR_CODE id=" + session + " code=" + code + " (local fake TV only)");
        System.out.flush();
        pairReply(socket, 31, new Proto.Writer());

        Proto.Message response = read(socket);
        requirePairing(response, 40);
        byte[] received = response.message(40).bytes(1);
        if (received == null || !MessageDigest.isEqual(secret, received)) {
            write(socket, new Proto.Writer().number(1, 2).number(2, 402));
            throw new IOException("Wrong pairing secret");
        }
        pairedClients.add(clientId(socket));
        pairReply(socket, 41, new Proto.Writer().bytes(1, secret));
        System.out.println("PAIRED id=" + session + " mutualTLS=true");
    }

    private static void remote(final SSLSocket socket, final int session) throws Exception {
        if (!pairedClients.contains(clientId(socket))) {
            System.out.println("REMOTE_REJECTED id=" + session + " reason=unpaired-client");
            return;
        }
        write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 1023)
                .message(2, new Proto.Writer().text(1, "Loopback TV").text(2, "BareRemote tests"))));
        long features = read(socket).message(1).integer(1, 0);
        if ((features & 2) == 0) throw new IOException("Client has no key support");
        write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, features)));
        read(socket).message(2);
        final long inputReadyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
        System.out.println("REMOTE_READY id=" + session + " features=" + features + " mutualTLS=true at_ms=" + elapsedMs());
        socket.setSoTimeout(20000);
        final AtomicInteger pingId = new AtomicInteger();
        final AtomicBoolean live = new AtomicBoolean(true);
        final List<ScheduledFuture<?>> pendingKeys = new ArrayList<ScheduledFuture<?>>();
        ScheduledFuture<?> ping = timers.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try {
                    write(socket, new Proto.Writer().message(8,
                            new Proto.Writer().number(1, pingId.incrementAndGet())));
                } catch (IOException closed) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
        try {
            while (!socket.isClosed()) {
                Proto.Message message = read(socket);
                if (message.has(10)) {
                    Proto.Message key = message.message(10);
                    final long code = key.integer(1, -1);
                    long direction = key.integer(2, 0);
                    final String directionName = direction == 3 ? "SHORT" : direction == 1 ? "START_LONG"
                            : direction == 2 ? "END_LONG" : "UNKNOWN";
                    System.out.println("KEY id=" + session + " code=" + code + " direction=" + directionName
                            + " at_ms=" + elapsedMs());
                    if (System.nanoTime() < inputReadyAt) {
                        System.out.println("KEY_IGNORED_NOT_READY id=" + session + " code=" + code);
                        continue;
                    }
                    // Receiving bytes is not dispatching input. Model a TV queue tied to its session.
                    pendingKeys.add(timers.schedule(new Runnable() {
                        @Override public void run() {
                            if (live.get()) System.out.println("KEY_APPLIED id=" + session
                                    + " code=" + code + " direction=" + directionName + " at_ms=" + elapsedMs());
                        }
                    }, 250, TimeUnit.MILLISECONDS));
                } else if (message.has(90)) {
                    String link = new String(message.message(90).bytes(1), java.nio.charset.StandardCharsets.UTF_8);
                    if (!link.equals("barehome://picture/day") && !link.equals("barehome://picture/night") && !link.equals("barehome://inputs")) {
                        throw new IOException("Unexpected app link");
                    }
                    System.out.println("APP_LINK_REQUEST id=" + session + " uri=" + link + " at_ms=" + elapsedMs());
                } else if (message.has(9)) {
                    long value = message.message(9).integer(1, 0);
                    if (value == 1) System.out.println("PING_OK id=" + session);
                } else {
                    throw new IOException("Unexpected client message");
                }
            }
        } finally {
            live.set(false);
            for (ScheduledFuture<?> pending : pendingKeys) pending.cancel(false);
            ping.cancel(false);
        }
    }

    private static String clientId(SSLSocket socket) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                socket.getSession().getPeerCertificates()[0].getEncoded());
        return java.util.Base64.getEncoder().encodeToString(hash);
    }

    private static long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    private static void requirePairing(Proto.Message message, int expected) throws IOException {
        if (message.integer(1, 0) != 2 || message.integer(2, 0) != 200 || !message.has(expected)) {
            throw new IOException("Unexpected pairing message");
        }
        message.message(expected);
    }

    private static Proto.Message read(SSLSocket socket) throws IOException {
        return Proto.parse(Proto.readFrame(socket.getInputStream()));
    }

    private static void pairReply(SSLSocket socket, int field, Proto.Writer message) throws IOException {
        write(socket, new Proto.Writer().number(1, 2).number(2, 200).message(field, message));
    }

    private static void write(SSLSocket socket, Proto.Writer message) throws IOException {
        synchronized (socket) { Proto.writeFrame(socket.getOutputStream(), message.build()); }
    }

    private static SSLContext context(String path) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        char[] password = "fake-tv-test".toCharArray();
        try (InputStream in = Files.newInputStream(Paths.get(path))) { keys.load(in, password); }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Client certificate required");
            }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Fake TV is a server only");
            }
        }}, null);
        return context;
    }
}
