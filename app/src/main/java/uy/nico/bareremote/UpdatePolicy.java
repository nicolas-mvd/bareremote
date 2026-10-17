package uy.nico.bareremote;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Pure bounds and origin policy shared by the metadata and APK paths. */
final class UpdatePolicy {
    static final int MAX_METADATA = 16 * 1024;
    static final long MAX_APK = 8 * 1024 * 1024;
    private UpdatePolicy() { }

    static void validate(int code, String name, String url, String sha, long size) {
        if (code < 1 || name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}"))
            throw new IllegalArgumentException("Invalid update version");
        if (sha == null || !sha.matches("[0-9a-f]{64}") || size < 1 || size > MAX_APK)
            throw new IllegalArgumentException("Invalid update checksum or size");
        URI uri = uri(url);
        if (!https(uri) || !"github.com".equals(uri.getHost()) || uri.getRawQuery() != null
                || !uri.getRawPath().matches("/nicolas-mvd/bareremote/releases/download/[A-Za-z0-9][A-Za-z0-9._+-]{0,95}/BareRemote\\.apk"))
            throw new IllegalArgumentException("Update is not an official release asset");
    }

    static boolean downloadHost(String url) {
        URI uri;
        try { uri = uri(url); } catch (IllegalArgumentException ex) { return false; }
        if (!https(uri)) return false;
        String host = uri.getHost();
        return "github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host);
    }

    private static URI uri(String text) {
        if (text == null || text.length() > 8192) throw new IllegalArgumentException("Invalid update URL");
        try { return new URI(text); }
        catch (URISyntaxException ex) { throw new IllegalArgumentException("Invalid update URL", ex); }
    }

    private static boolean https(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getRawFragment() == null;
    }

    static boolean packageMatches(String installedPackage, String candidatePackage, long installedCode,
            long candidateCode, int expectedCode, byte[][] installedSigners, byte[][] candidateSigners) {
        return installedPackage != null && installedPackage.equals(candidatePackage)
                && candidateCode > installedCode && candidateCode == expectedCode
                && sameSigners(installedSigners, candidateSigners);
    }

    static final class Metadata {
        final int code;
        final String name, url, sha;
        final long size;
        Metadata(int code, String name, String url, String sha, long size) {
            validate(code, name, url, sha, size);
            this.code = code; this.name = name; this.url = url; this.sha = sha; this.size = size;
        }
    }

    /** Deliberately small strict JSON reader: exactly five keys, strings and integer values. */
    static Metadata parse(String json) throws java.io.IOException {
        Parser parser = new Parser(json);
        parser.expect('{');
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        do {
            String key = parser.string();
            if (!key.equals("versionCode") && !key.equals("versionName") && !key.equals("apkUrl")
                    && !key.equals("sha256") && !key.equals("size")) throw new java.io.IOException("Unknown update field");
            parser.expect(':');
            Object value = key.equals("versionCode") || key.equals("size") ? parser.integer() : parser.string();
            if (fields.put(key, value) != null) throw new java.io.IOException("Duplicate update field");
            if (!parser.take(',')) break;
        } while (true);
        parser.expect('}');
        if (!parser.done() || fields.size() != 5) throw new java.io.IOException("Incomplete update metadata");
        long code = (Long) fields.get("versionCode");
        if (code > Integer.MAX_VALUE) throw new java.io.IOException("Invalid update version");
        try {
            return new Metadata((int) code, (String) fields.get("versionName"), (String) fields.get("apkUrl"),
                    (String) fields.get("sha256"), (Long) fields.get("size"));
        } catch (IllegalArgumentException ex) { throw new java.io.IOException("Invalid update metadata", ex); }
    }

    private static final class Parser {
        final String text;
        int at;
        Parser(String text) throws java.io.IOException {
            if (text == null || text.length() > MAX_METADATA) throw new java.io.IOException("Metadata too large");
            this.text = text;
        }
        void space() {
            while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t'
                    || text.charAt(at) == '\n' || text.charAt(at) == '\r')) at++;
        }
        boolean take(char value) { space(); if (at < text.length() && text.charAt(at) == value) { at++; return true; } return false; }
        void expect(char value) throws java.io.IOException { if (!take(value)) throw new java.io.IOException("Malformed update metadata"); }
        boolean done() { space(); return at == text.length(); }
        Long integer() throws java.io.IOException {
            space(); int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (start == at || (at - start > 1 && text.charAt(start) == '0')) throw new java.io.IOException("Invalid update number");
            try { return Long.parseLong(text.substring(start, at)); }
            catch (NumberFormatException ex) { throw new java.io.IOException("Invalid update number", ex); }
        }
        String string() throws java.io.IOException {
            expect('"'); StringBuilder value = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return value.toString();
                if (c < 32) throw new java.io.IOException("Invalid JSON string");
                if (c == '\\') {
                    if (at == text.length()) throw new java.io.IOException("Invalid JSON escape");
                    c = text.charAt(at++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (at + 4 > text.length() || !text.substring(at, at + 4).matches("[0-9a-fA-F]{4}"))
                                throw new java.io.IOException("Invalid JSON escape");
                            try { c = (char) Integer.parseInt(text.substring(at, at + 4), 16); }
                            catch (NumberFormatException ex) { throw new java.io.IOException("Invalid JSON escape", ex); }
                            at += 4; break;
                        default: throw new java.io.IOException("Invalid JSON escape");
                    }
                }
                value.append(c);
            }
            throw new java.io.IOException("Unclosed JSON string");
        }
    }

    static boolean sameSigners(byte[][] first, byte[][] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        Set<String> a = new HashSet<>(), b = new HashSet<>();
        for (byte[] cert : first) { if (cert == null || cert.length == 0) return false; a.add(hex(cert)); }
        for (byte[] cert : second) { if (cert == null || cert.length == 0) return false; b.add(hex(cert)); }
        return a.size() == first.length && b.size() == second.length && a.equals(b);
    }

    static boolean digestMatches(String expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                hex(actual).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
}
