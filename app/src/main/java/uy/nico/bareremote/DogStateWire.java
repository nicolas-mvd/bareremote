package uy.nico.bareremote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small authenticated, one-request Dog Mode snapshot format. No arbitrary callback URLs. */
final class DogStateWire {
    static final int TOKEN_BYTES = 32;
    static final int MAX_REPLY_BYTES = 176;

    static String request(String action, InetAddress reply, int port, byte[] token) {
        if (!("state".equals(action) || "start".equals(action) || "stop".equals(action)) || !isPrivateV4(reply) || port < 1024 || port > 65535 || token.length != TOKEN_BYTES)
            throw new IllegalArgumentException("Invalid Dog Mode callback");
        return "barehome://dog/" + action + "?reply="
                + reply.getHostAddress() + "&port=" + port + "&token=" + hex(token);
    }

    static String verify(byte[] bytes, int length, byte[] token) throws GeneralSecurityException {
        if (token.length != TOKEN_BYTES || length < 1 || length > MAX_REPLY_BYTES || length > bytes.length)
            return null;
        for (int i = 0; i < length; i++) if (bytes[i] < 0x20 || bytes[i] > 0x7e) return null;
        String value = new String(bytes, 0, length, StandardCharsets.US_ASCII);
        String[] fields = value.split("\\|", -1);
        if (fields.length != 4 || !"BH2".equals(fields[0]) || !knownState(fields[2])) return null;
        byte[] id = unhex(fields[1]);
        byte[] receivedMac = unhex(fields[3]);
        if (id == null || receivedMac == null) return null;
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(token), id)) return null;
        String prefix = "BH2|" + fields[1] + "|" + fields[2];
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        return MessageDigest.isEqual(mac.doFinal(prefix.getBytes(StandardCharsets.US_ASCII)), receivedMac)
                ? fields[2] : null;
    }

    static boolean isPrivateV4(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first == 10 || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168;
    }

    static boolean knownState(String state) {
        return "off".equals(state) || "starting".equals(state) || "active".equals(state) || "restore_required".equals(state)
                || "unavailable".equals(state) || "error".equals(state) || isStartupFailure(state);
    }

    static boolean isStartupFailure(String state) {
        return "nsnow_access".equals(state) || "nsnow_menu".equals(state) || "nsnow_channel".equals(state)
                || "nsnow_player".equals(state) || "nsnow_debug".equals(state)
                || "nsnow_target".equals(state) || "nsnow_input".equals(state);
    }

    /** These fixed TV replies assert successful rollback and removal of the helper. */
    static String startupFailureMessage(String state) {
        final String detail;
        if ("nsnow_access".equals(state)) detail = "NS NOW’s controls could not be accessed.";
        else if ("nsnow_menu".equals(state)) detail = "Could not open TV EN VIVO in NS NOW.";
        else if ("nsnow_channel".equals(state)) detail = "Could not select CANAL 10 in NS NOW.";
        else if ("nsnow_target".equals(state)) detail = "CANAL 10 could not be identified in NS NOW’s channel list.";
        else if ("nsnow_input".equals(state)) detail = "The TV did not accept the CANAL 10 selection.";
        else if ("nsnow_player".equals(state)) detail = "Could not confirm CANAL 10 in NS NOW.";
        else if ("nsnow_debug".equals(state)) detail = "Turn off USB debugging on the TV.";
        else return null;
        return detail + " Previous TV energy settings were restored and the helper is off. Check status before trying again.";
    }

    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }

    static byte[] unhex(String value) {
        if (value.length() != TOKEN_BYTES * 2) return null;
        byte[] bytes = new byte[TOKEN_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            char high = value.charAt(i * 2), low = value.charAt(i * 2 + 1);
            if (!lowerHex(high) || !lowerHex(low)) return null;
            bytes[i] = (byte) ((Character.digit(high, 16) << 4) | Character.digit(low, 16));
        }
        return bytes;
    }
    private static boolean lowerHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }
    private DogStateWire() {}
}
