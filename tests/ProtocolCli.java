package uy.nico.bareremote;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Manual hardware harness. Sends no key unless key/long is explicitly requested. */
public final class ProtocolCli {
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || !(args[0].equals("pair") || args[0].equals("check")
                || args[0].equals("key") || args[0].equals("long"))) {
            System.err.println("Usage: ProtocolCli pair|check|key|long TV_IP client.p12 tv-pin.der [KEYCODE]");
            System.err.println("Set BAREREMOTE_STORE_PASSWORD for the PKCS12 identity.");
            System.exit(2);
        }
        String password = System.getenv("BAREREMOTE_STORE_PASSWORD");
        if (password == null) throw new IllegalArgumentException("BAREREMOTE_STORE_PASSWORD is required");
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(args[2]))) { keys.load(in, password.toCharArray()); }
        final KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, password.toCharArray());
        TvProtocol.Identity identity = new TvProtocol.Identity() {
            @Override public SSLContext context(final byte[] pin) throws Exception {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(factory.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                        throw new CertificateException("Not a server identity");
                    }
                    public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                        if (chain == null || chain.length == 0) throw new CertificateException("Missing TV certificate");
                        if (pin != null && !MessageDigest.isEqual(pin, chain[0].getEncoded())) {
                            throw new CertificateException("TV certificate changed");
                        }
                    }
                }}, null);
                return context;
            }
        };
        Path pinFile = Paths.get(args[3]);
        if (args[0].equals("pair")) {
            try (TvProtocol.Pairing pairing = TvProtocol.Pairing.start(args[1], TvProtocol.PAIRING_PORT,
                    "BareRemote hardware test", identity)) {
                System.out.println("TV is displaying the pairing code. Enter its six characters:");
                String code;
                if (System.console() != null) code = new String(System.console().readPassword());
                else code = new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
                byte[] certificate = pairing.finish(code == null ? null : code.trim());
                Files.write(pinFile, certificate);
                try { Files.setPosixFilePermissions(pinFile, PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { }
                System.out.println("Paired; TV certificate saved. No remote keys sent.");
            }
        } else {
            int key = 0;
            if (!args[0].equals("check")) {
                if (args.length != 5) throw new IllegalArgumentException("Explicit Android keycode required");
                key = Integer.parseInt(args[4]);
            }
            try (TvProtocol.Remote remote = TvProtocol.Remote.connect(args[1], TvProtocol.REMOTE_PORT,
                    Files.readAllBytes(pinFile), identity)) {
                if (args[0].equals("key")) remote.sendKey(key);
                else if (args[0].equals("long")) remote.sendLongKey(key);
                System.out.println(args[0].equals("check") ? "Connected and ready. No remote keys sent."
                        : "Command sent once.");
            }
        }
    }
}
