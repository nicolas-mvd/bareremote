package uy.nico.bareremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Real loopback TLS regression: trust is replaced by subject, not by public key. */
public final class MultiClientTest {
    private static final char[] PASSWORD = "test-only".toCharArray();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("server oldA oldB uniqueA uniqueB");
        Client oldA = new Client(args[1]);
        Client oldB = new Client(args[2]);
        Client phoneA = new Client(args[3]);
        Client phoneB = new Client(args[4]);
        check(oldA.subject().equals(oldB.subject()), "legacy test phones share a subject");
        check(!MessageDigest.isEqual(oldA.certificate.getPublicKey().getEncoded(),
                oldB.certificate.getPublicKey().getEncoded()), "legacy keys are nevertheless distinct");
        check(!phoneA.subject().equals(phoneB.subject()), "new phone subjects are distinct");
        check(!MessageDigest.isEqual(phoneA.certificate.getPublicKey().getEncoded(),
                phoneB.certificate.getPublicKey().getEncoded()), "new phone keys are distinct");

        try (Host tv = new Host(args[0])) {
            tv.pair(oldA);
            check(tv.trust.size() == 1 && tv.trust.accepts(oldA.certificate), "first legacy phone trusted");
            try (TvProtocol.Remote connectedA = tv.connect(oldA)) {
                tv.pair(oldB);
                check(tv.trust.size() == 1, "same-subject pairing replaces instead of adding trust");
                check(!tv.trust.accepts(oldA.certificate) && tv.trust.accepts(oldB.certificate),
                        "second legacy pairing removes only the first phone's trust");
                check(connectedA.isOpen(), "already established TLS is not forcibly evicted by pairing");
            }
            expectConnectionFailure(tv, oldA, tv.pin, "first legacy phone fails its next TLS handshake");
            try (TvProtocol.Remote connectedB = tv.connect(oldB)) {
                connectedB.sendKey(21);
                tv.expectKeys(oldB, 21);
            }

            tv.pair(phoneA);
            check(tv.trust.size() == 2, "unique subject coexists with a legacy phone");
            try (TvProtocol.Remote a = tv.connect(phoneA)) {
                tv.pair(phoneB);
                check(tv.trust.size() == 3, "second unique subject adds a separate trust entry");
                check(tv.trust.accepts(phoneA.certificate) && tv.trust.accepts(phoneB.certificate),
                        "pairing phone B preserves phone A trust");
                try (TvProtocol.Remote b = tv.connect(phoneB)) {
                    check(a.isOpen() && b.isOpen(), "two independently authenticated sessions remain open");
                    a.sendKey(19);
                    b.sendKey(20);
                    a.sendKey(22);
                    b.sendKey(21);
                    tv.expectKeys(phoneA, 19, 22);
                    tv.expectKeys(phoneB, 20, 21);
                    tv.expectPings(phoneA);
                    tv.expectPings(phoneB);
                    check(a.isOpen() && b.isOpen(), "both clients answer their own pings while controlling TV");
                    expectConnectionFailure(tv, phoneA, new byte[]{1, 2, 3},
                            "multiple clients do not relax server certificate pinning");
                    a.close();
                    try (TvProtocol.Remote reconnectedA = tv.connect(phoneA)) {
                        check(b.isOpen(), "reconnecting phone A does not close phone B");
                        reconnectedA.sendKey(23);
                        b.sendKey(24);
                        tv.expectKeys(phoneA, 23);
                        tv.expectKeys(phoneB, 24);
                        check(reconnectedA.isOpen() && b.isOpen(), "both usable after first phone reconnects");
                    }
                }
            }
        }
        System.out.println("Multi-client: " + assertions + " assertions passed");
    }

    private static void expectConnectionFailure(Host tv, Client client, byte[] pin, String message)
            throws Exception {
        boolean rejected = false;
        try (TvProtocol.Remote unexpected = TvProtocol.Remote.connect("127.0.0.1", tv.remote.getLocalPort(), pin, client)) {
            // Closing in the success case also ensures a failed assertion never leaks a connection.
        } catch (IOException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static KeyStore store(String path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(path))) { store.load(in, PASSWORD); }
        return store;
    }

    private static KeyManagerFactory keys(KeyStore store) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        return keys;
    }

    private static final class Client implements TvProtocol.Identity {
        final KeyStore store;
        final X509Certificate certificate;
        Client(String path) throws Exception {
            store = store(path);
            certificate = (X509Certificate) store.getCertificate("test");
        }
        String subject() { return certificate.getSubjectX500Principal().getName(); }
        @Override public SSLContext context(final byte[] pin) throws Exception {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys(store).getKeyManagers(), new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    throw new CertificateException("Client only");
                }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    if (chain == null || chain.length == 0
                            || (pin != null && !MessageDigest.isEqual(pin, chain[0].getEncoded())))
                        throw new CertificateException("Wrong server certificate");
                }
            }}, null);
            return context;
        }
    }

    private static final class Host implements Closeable {
        final FakeTv.TrustedClients trust = new FakeTv.TrustedClients();
        final SSLServerSocket pairing;
        final SSLServerSocket remote;
        final byte[] pin;
        final ExecutorService workers = Executors.newFixedThreadPool(5);
        final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        final List<SSLSocket> sockets = Collections.synchronizedList(new ArrayList<SSLSocket>());
        final BlockingQueue<String> codes = new LinkedBlockingQueue<String>();
        final Map<String, BlockingQueue<Integer>> keys = new ConcurrentHashMap<String, BlockingQueue<Integer>>();
        final Map<String, AtomicInteger> pings = new ConcurrentHashMap<String, AtomicInteger>();

        Host(String path) throws Exception {
            KeyStore serverKeys = store(path);
            pin = serverKeys.getCertificate("test").getEncoded();
            pairing = listen(serverKeys, true);
            remote = listen(serverKeys, false);
        }

        SSLServerSocket listen(KeyStore serverKeys, final boolean isPairing) throws Exception {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(MultiClientTest.keys(serverKeys).getKeyManagers(), new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    if (chain == null || chain.length == 0 || (!isPairing && !trust.accepts(chain[0])))
                        throw new CertificateException("Not paired");
                }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    throw new CertificateException("Server only");
                }
            }}, null);
            final SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            listener.setNeedClientAuth(true);
            listener.setEnabledProtocols(new String[]{"TLSv1.2"});
            Thread accept = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        final SSLSocket socket = (SSLSocket) listener.accept();
                        sockets.add(socket);
                        workers.execute(() -> {
                            try (SSLSocket owned = socket) {
                                owned.setSoTimeout(4000);
                                owned.setTcpNoDelay(true);
                                owned.startHandshake();
                                if (isPairing) acceptPairing(owned); else acceptRemote(owned);
                            } catch (Exception expectedOnDisconnect) {
                                // Client-side bounded assertions verify the complete exchange.
                            } finally { sockets.remove(socket); }
                        });
                    } catch (IOException closed) { return; }
                }
            }, "MultiClient-test-listener");
            accept.setDaemon(true);
            accept.start();
            return listener;
        }

        void pair(Client client) throws Exception {
            try (TvProtocol.Pairing pairing = TvProtocol.Pairing.start("127.0.0.1",
                    this.pairing.getLocalPort(), "Same friendly remote name", client)) {
                String code = codes.poll(2, TimeUnit.SECONDS);
                check(code != null, "local TV displayed a bounded pairing challenge");
                check(MessageDigest.isEqual(pin, pairing.finish(code)), "pairing returns the exact TV pin");
            }
        }

        TvProtocol.Remote connect(Client client) throws Exception {
            return TvProtocol.Remote.connect("127.0.0.1", remote.getLocalPort(), pin, client);
        }

        void acceptPairing(SSLSocket socket) throws Exception {
            read(socket).message(10);
            pairReply(socket, 11);
            read(socket).message(20);
            pairReply(socket, 20);
            read(socket).message(30);
            X509Certificate client = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            RSAPublicKey a = (RSAPublicKey) client.getPublicKey();
            RSAPublicKey b = (RSAPublicKey) socket.getSession().getLocalCertificates()[0].getPublicKey();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(TvProtocol.unsigned(a.getModulus()));
            digest.update(TvProtocol.unsigned(a.getPublicExponent()));
            digest.update(TvProtocol.unsigned(b.getModulus()));
            digest.update(TvProtocol.unsigned(b.getPublicExponent()));
            digest.update(new byte[]{0x12, 0x34});
            byte[] expected = digest.digest();
            codes.add(String.format("%02x1234", expected[0] & 255));
            pairReply(socket, 31);
            if (!MessageDigest.isEqual(expected, read(socket).message(40).bytes(1)))
                throw new IOException("Invalid challenge response");
            trust.store(client);
            pairReply(socket, 41);
        }

        void acceptRemote(final SSLSocket socket) throws Exception {
            X509Certificate certificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            final String subject = certificate.getSubjectX500Principal().getName();
            keys.putIfAbsent(subject, new LinkedBlockingQueue<Integer>());
            pings.putIfAbsent(subject, new AtomicInteger());
            write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 611)));
            if (read(socket).message(1).integer(1, 0) != 611) throw new IOException("Wrong features");
            write(socket, new Proto.Writer().message(2, new Proto.Writer()));
            if (read(socket).message(2).integer(1, 0) != 611) throw new IOException("Wrong active features");
            write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
            ScheduledFuture<?> ping = timers.scheduleAtFixedRate(() -> {
                try { write(socket, new Proto.Writer().message(8, new Proto.Writer().number(1, 7))); }
                catch (IOException closed) { try { socket.close(); } catch (IOException ignored) { } }
            }, 100, 100, TimeUnit.MILLISECONDS);
            try {
                while (!socket.isClosed()) {
                    Proto.Message message = read(socket);
                    if (message.has(9)) {
                        if (message.message(9).integer(1, -1) != 7) throw new IOException("Wrong ping");
                        pings.get(subject).incrementAndGet();
                    } else if (message.has(10)) {
                        Proto.Message key = message.message(10);
                        if (key.integer(2, 0) != 3) throw new IOException("Expected short key");
                        keys.get(subject).add((int) key.integer(1, -1));
                    } else throw new IOException("Unexpected message");
                }
            } finally { ping.cancel(false); }
        }

        void expectKeys(Client client, int... expected) throws Exception {
            BlockingQueue<Integer> received = keys.get(client.subject());
            for (int key : expected) {
                Integer actual = received.poll(2, TimeUnit.SECONDS);
                check(actual != null && actual == key, "each phone's keys arrive once and in order");
            }
            check(received.poll(80, TimeUnit.MILLISECONDS) == null, "no duplicate key delivery");
        }

        void expectPings(Client client) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pings.get(client.subject()).get() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
            check(pings.get(client.subject()).get() >= 2, "independent connection ping responses");
        }

        @Override public void close() throws IOException {
            pairing.close();
            remote.close();
            synchronized (sockets) {
                for (SSLSocket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            }
            timers.shutdownNow();
            workers.shutdownNow();
        }
    }

    private static Proto.Message read(SSLSocket socket) throws IOException {
        return Proto.parse(Proto.readFrame(socket.getInputStream()));
    }
    private static void write(SSLSocket socket, Proto.Writer body) throws IOException {
        synchronized (socket) { Proto.writeFrame(socket.getOutputStream(), body.build()); }
    }
    private static void pairReply(SSLSocket socket, int field) throws IOException {
        write(socket, new Proto.Writer().number(1, 2).number(2, 200).message(field, new Proto.Writer()));
    }
}
