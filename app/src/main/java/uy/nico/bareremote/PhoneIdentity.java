package uy.nico.bareremote;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Each phone has its own non-exportable identity. Pairing establishes the TV pin. */
final class PhoneIdentity implements TvProtocol.Identity {
    private static final String ALIAS = "bareremote.client.v2";
    private PrivateKey key;
    private X509Certificate certificate;

    private synchronized void load() throws Exception {
        if (key != null) return;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    // Conscrypt applies TLS RSA-PSS padding itself, then asks
                    // Keystore to perform the raw private-key signing operation.
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setCertificateSubject(new X500Principal("CN=BareRemote"))
                    .setCertificateSerialNumber(new BigInteger(128, new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(1577836800000L))
                    .setCertificateNotAfter(new Date(2524608000000L))
                    .setUserAuthenticationRequired(false).build());
            generator.generateKeyPair();
        }
        key = (PrivateKey) store.getKey(ALIAS, null);
        certificate = (X509Certificate) store.getCertificate(ALIAS);
        if (key == null || certificate == null) throw new CertificateException("Phone identity unavailable");
    }

    @Override public SSLContext context(byte[] pinnedServerCertificate) throws Exception {
        load();
        final byte[] pin = pinnedServerCertificate == null ? null : pinnedServerCertificate.clone();
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String type, Principal[] issuers) {
                return type != null && type.startsWith("RSA") ? new String[] {ALIAS} : null;
            }
            @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
                if (types != null) for (String type : types) if (type != null && type.startsWith("RSA")) return ALIAS;
                return null;
            }
            @Override public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine engine) {
                return chooseClientAlias(types, issuers, null);
            }
            @Override public String[] getServerAliases(String type, Principal[] issuers) { return null; }
            @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return null; }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? new X509Certificate[] {certificate} : null;
            }
            @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
        };
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("Client certificates are not accepted here");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("TV certificate missing");
                if (pin != null && !Arrays.equals(pin, chain[0].getEncoded())) {
                    throw new CertificateException("TV identity changed. Pair again in Setup.");
                }
                // During pairing only, the displayed code authenticates both certificates.
                // Normal remote connections always provide the persisted server pin.
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {keys}, new TrustManager[] {trust}, new SecureRandom());
        return context;
    }
}
