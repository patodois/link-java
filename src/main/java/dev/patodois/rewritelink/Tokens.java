package dev.patodois.rewritelink;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The authentication token of contract revision 6 (CONTRACT.md section 6.2): generating one, checking that a
 * configured one is usable, and comparing two in constant time.
 */
public final class Tokens {
    /** The longest token, in bytes of UTF-8. */
    public static final int MAX_BYTES = 256;

    private Tokens() {}

    /**
     * A new token: 32 bytes from the operating system's cryptographic generator ({@link SecureRandom}) as 64 lowercase
     * hex digits. A failing generator throws; there is no weaker fallback.
     */
    public static String generate() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        char[] out = new char[64];
        final String digits = "0123456789abcdef";
        for (int i = 0; i < raw.length; i++) {
            out[2 * i] = digits.charAt((raw[i] >> 4) & 0xF);
            out[2 * i + 1] = digits.charAt(raw[i] & 0xF);
        }
        return new String(out);
    }

    /**
     * Compares two tokens, given as their UTF-8 bytes, in time that does not depend on their contents or on where they
     * first differ (the reference method of CONTRACT.md 6.2): the XOR of the lengths is ORed with the XOR of all 256
     * byte pairs, a missing byte counting as 0. Tokens longer than 256 bytes are never equal to anything (the loop
     * still runs). Two empty arrays are equal here; a handshake never gets that far, since a token has 1 to 256 bytes.
     */
    public static boolean equal(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < MAX_BYTES; i++) {
            int x = i < a.length ? a[i] & 0xFF : 0;
            int y = i < b.length ? b[i] & 0xFF : 0;
            diff |= x ^ y;
        }
        boolean tooLong = (a.length > MAX_BYTES) | (b.length > MAX_BYTES);
        return diff == 0 & !tooLong;
    }

    /** {@link #equal(byte[], byte[])} on the UTF-8 bytes of two strings. */
    public static boolean equal(String a, String b) {
        return equal(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether {@code token} has 1 to 256 bytes of UTF-8 (an unpaired surrogate cannot be encoded and does not count). */
    static boolean validLength(String token) {
        if (token == null || token.isEmpty() || hasUnpairedSurrogate(token)) return false;
        return token.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    /**
     * Checks a configured token (not null): 1 to 256 bytes of UTF-8 and no U+0000. The message never contains the token.
     *
     * @throws IllegalArgumentException if it is not usable
     */
    static void requireUsable(String token) {
        if (token.indexOf('\0') >= 0) throw new IllegalArgumentException("token must not contain U+0000");
        if (token.isEmpty()) throw new IllegalArgumentException("token must not be empty");
        if (hasUnpairedSurrogate(token)) throw new IllegalArgumentException("token must be valid Unicode");
        if (token.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("token must have at most " + MAX_BYTES + " bytes in UTF-8");
        }
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
