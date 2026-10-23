package uy.nico.bareremote;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Loopback-only IME peer following the TV service's direction-specific wire
 * contract (ImeFeature/bsi and ClientMessageHandler/bsh, not the community
 * symmetric message names). Its assertions never print received text.
 */
public final class NativeImeTv {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("NativeImeTv test.p12 PORT");
        SSLContext context = context(args[0]);
        SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(Integer.parseInt(args[1]), 8, InetAddress.getByName("127.0.0.1"));
        listener.setNeedClientAuth(true);
        listener.setEnabledProtocols(new String[] {"TLSv1.2"});
        AtomicInteger count = new AtomicInteger();
        System.out.println("NATIVE_IME_TV_READY");
        System.out.flush();
        while (true) {
            final SSLSocket socket = (SSLSocket) listener.accept();
            final int session = count.incrementAndGet();
            Thread worker = new Thread(() -> {
                try (SSLSocket owned = socket) {
                    owned.setSoTimeout(5000);
                    owned.startHandshake();
                    run(owned, session);
                    System.out.println("IME_DONE session=" + session);
                } catch (Exception failure) {
                    System.out.println("IME_FAILED session=" + session + " reason=" + failure.getClass().getSimpleName());
                }
                System.out.flush();
            });
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void run(SSLSocket socket, int session) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        long features = session == 2 ? 3 : 7;
        write(out, new Proto.Writer().message(1, new Proto.Writer().number(1, features)));
        require(Proto.parse(Proto.readFrame(in)).message(1).integer(1, 0) == features);
        write(out, new Proto.Writer().message(2, new Proto.Writer().number(1, features)));
        require(Proto.parse(Proto.readFrame(in)).message(2).integer(1, 0) == features);
        write(out, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
        if (session == 2) {
            // Unsupported IME must still permit ordinary controls.
            key(next(in), 19);
            require(in.read() == -1);
            return;
        }
        if (session == 3) {
            // An invalid field message must fail closed, without a typed frame.
            Thread.sleep(450);
            write(out, new Proto.Writer().message(22, new Proto.Writer().message(2,
                    new Proto.Writer().number(1, 1).bytes(2, new byte[] {(byte) 0xff}))));
            require(in.read() == -1);
            return;
        }
        require(session == 1);
        write(out, app(0)); // Current-app only: no editor and no automatic show.
        write(out, editor(7, 9, "existing\ud83d\ude00", 10, 10));
        write(out, visibility(true));
        write(out, new Proto.Writer().message(8, new Proto.Writer().number(1, 123)));
        text(next(in), 7, 9, "A\ud83d\ude00\u00e9", 10, 10);
        // Content revisions advance without changing the editor identifier.
        write(out, status(10, "existing\ud83d\ude00A\ud83d\ude00\u00e9", 14, 14));
        write(out, new Proto.Writer().message(23, new Proto.Writer().number(2, 11).number(3, 13)));
        write(out, new Proto.Writer().message(8, new Proto.Writer().number(1, 124)));
        text(next(in), 7, 10, "\u03b2", 11, 13);
        write(out, editor(8, 12, "", 0, 0));
        write(out, visibility(true));
        for (int code : new int[] {67, 66, 21, 112, 61, 19, 20, 22}) key(next(in), code);
        write(out, app(0)); // Editor lost: stale text/key requests must never appear.
        write(out, visibility(false));
        key(next(in), 24);
        require(in.read() == -1);
    }

    private static Proto.Message next(InputStream in) throws Exception {
        for (int count = 0; count < 16; count++) {
            Proto.Message message = Proto.parse(Proto.readFrame(in));
            if (message.has(9)) {
                long value = message.message(9).integer(1, 0);
                require(value == 123 || value == 124);
            } else return message;
        }
        throw new IllegalStateException("Too many ping replies");
    }

    private static void text(Proto.Message message, long ime, long field, String expected, int start, int end) throws Exception {
        require(message.fields.size() == 1 && message.has(21));
        Proto.Message batch = message.message(21);
        require(batch.integer(1, 0) == ime && batch.integer(2, 0) == field);
        require(batch.fields.size() == 3);
        Proto.Message edit = batch.message(3);
        // Real RemoteEditInfo is a oneof: field 2 is an insert object. An
        // "insert=1" scalar in field 1 belongs to the inaccurate public schema.
        require(edit.fields.size() == 1 && edit.has(2));
        Proto.Message object = edit.message(2);
        require(object.integer(1, 0) == start && object.integer(2, 0) == end);
        require(expected.equals(new String(object.bytes(3), StandardCharsets.UTF_8)));
    }

    private static void key(Proto.Message message, int code) throws Exception {
        require(message.fields.size() == 1 && message.has(10));
        Proto.Message key = message.message(10);
        require(key.integer(1, 0) == code && key.integer(2, 0) == 3);
    }

    private static Proto.Writer app(int counter) throws Exception {
        return new Proto.Writer().message(20, new Proto.Writer().message(1,
                new Proto.Writer().number(1, counter).text(12, "local.ime.fixture")));
    }

    private static Proto.Writer visibility(boolean visible) throws Exception {
        return new Proto.Writer().message(21, new Proto.Writer().number(1, visible ? 1 : 0).number(2, 0));
    }

    private static Proto.Writer editor(int editor, int revision, String value, int start, int end) throws Exception {
        return new Proto.Writer().message(20, new Proto.Writer().message(1,
                new Proto.Writer().number(1, editor).number(2, 1).text(12, "local.ime.fixture"))
                .message(2, field(revision, value, start, end)));
    }

    private static Proto.Writer status(int revision, String value, int start, int end) throws Exception {
        return new Proto.Writer().message(22, new Proto.Writer().number(1, 1)
                .message(2, field(revision, value, start, end)));
    }

    private static Proto.Writer field(int revision, String value, int start, int end) throws Exception {
        return new Proto.Writer().number(1, revision).text(2, value)
                .number(3, start).number(4, end).text(6, "Search");
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Unexpected IME wire frame");
    }

    private static void write(OutputStream out, Proto.Writer message) throws Exception {
        Proto.writeFrame(out, message.build());
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
                throw new java.security.cert.CertificateException("Local fixture is a server only");
            }
        }}, null);
        return context;
    }
}
