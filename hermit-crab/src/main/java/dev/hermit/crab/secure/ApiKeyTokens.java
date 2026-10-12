package dev.hermit.crab.secure;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Issues {@code hk_} + Base62(32 random bytes). Persistence stores only the SHA-256 hex and
 * an 8-character prefix. Comparison uses {@link MessageDigest#isEqual}; {@code String.equals}
 * would return early on the first differing nibble.
 */
public final class ApiKeyTokens {
    public static final int PREFIX_LENGTH = 8;
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DUMMY_HASH = new byte[32];

    private ApiKeyTokens() {
    }

    public record Issued(String plaintext, String prefix, String hashHex) {
        @Override
        public String toString() {
            return "Issued[prefix=" + prefix + "]";
        }
    }

    public static Issued issue() {
        byte[] raw = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(raw);
        String body = Base62.encode(raw);
        Arrays.fill(raw, (byte) 0);
        String plaintext = "hk_" + body;
        if (plaintext.length() < PREFIX_LENGTH) {
            throw new IllegalStateException("api key encoding is shorter than the prefix");
        }
        return new Issued(plaintext, plaintext.substring(0, PREFIX_LENGTH), hashHex(plaintext));
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public static String hashHex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    public static boolean constantTimeEquals(byte[] presentedHash, String storedHex) {
        byte[] stored = decodeHash(storedHex);
        if (stored == null) {
            MessageDigest.isEqual(presentedHash, DUMMY_HASH);
            return false;
        }
        return MessageDigest.isEqual(presentedHash, stored);
    }

    private static byte[] decodeHash(String storedHex) {
        if (storedHex == null || storedHex.length() != 64) {
            return null;
        }
        try {
            byte[] decoded = HexFormat.of().parseHex(storedHex);
            return decoded.length == 32 ? decoded : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
