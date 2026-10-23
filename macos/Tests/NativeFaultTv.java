package uy.nico.bareremote;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Independent TLS wire-fault fixture. It can bind only host loopback. */
public final class NativeFaultTv {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("NativeFaultTv test.p12 RAW_PORT REMOTE_PORT");
        SSLContext context = context(args[0]);
        serve(context, Integer.parseInt(args[1]), false);
        serve(context, Integer.parseInt(args[2]), true);
        System.out.println("NATIVE_FAULT_TV_READY");
        System.out.flush();
        new CountDownLatch(1).await();
    }

    private static void serve(SSLContext context, int port, final boolean remote) throws Exception {
        final SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        listener.setNeedClientAuth(true);
        listener.setEnabledProtocols(new String[] {"TLSv1.2"});
        Thread accept = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    final SSLSocket socket = (SSLSocket) listener.accept();
                    Thread worker = new Thread(() -> {
                        try (SSLSocket owned = socket) {
                            owned.setSoTimeout(5000);
                            owned.startHandshake();
                            if (remote) remote(owned);
                            else fault(owned);
                        } catch (Exception expectedFault) {
                            // Intentional truncation/close tests must not expose credentials.
                        }
                    });
                    worker.setDaemon(true);
                    worker.start();
                } catch (Exception closed) { return; }
            }
        });
        accept.setDaemon(true);
        accept.start();
    }

    private static void fault(SSLSocket socket) throws Exception {
        int mode = (int) Proto.parse(Proto.readFrame(socket.getInputStream())).integer(1, 0);
        OutputStream out = socket.getOutputStream();
        switch (mode) {
            case 1: // Split a two-byte length prefix and body; coalesce the next frame.
                out.write(0xac); out.flush(); Thread.sleep(10);
                out.write(0x02); out.flush();
                byte[] body = new byte[300]; Arrays.fill(body, (byte) 42);
                for (int offset = 0; offset < body.length; offset += 10) {
                    out.write(body, offset, 10); out.flush(); Thread.sleep(1);
                }
                out.write(new byte[] {2, 8, 1}); out.flush();
                break;
            case 2: // A valid prefix and partial body must share one read deadline.
                out.write(new byte[] {4, 8}); out.flush(); Thread.sleep(5000);
                break;
            case 3: // Leave a read blocked until the client explicitly cancels it.
                Thread.sleep(5000);
                break;
            case 4: out.write(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff, 2}); out.flush(); break;
            case 5: out.write(new byte[] {(byte) 0x81, (byte) 0x80, 4}); out.flush(); break;
            case 6: out.write(0); out.flush(); break;
            default: throw new IllegalArgumentException("Unknown local fault mode");
        }
    }

    private static void remote(SSLSocket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        Proto.writeFrame(out, new Proto.Writer().message(1, new Proto.Writer().number(1, 3)).build());
        Proto.parse(Proto.readFrame(in)).message(1);
        Proto.writeFrame(out, new Proto.Writer().message(2, new Proto.Writer().number(1, 3)).build());
        Proto.parse(Proto.readFrame(in)).message(2);
        Proto.writeFrame(out, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)).build());
        // Return long enough after RemoteStart for the client's readiness wait to complete.
        Thread.sleep(450);
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
