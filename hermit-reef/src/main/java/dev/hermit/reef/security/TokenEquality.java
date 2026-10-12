package dev.hermit.reef.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TokenEquality {

    private TokenEquality() {
    }

    /**
     * 先把两边都哈希成定长再比较，避免比较时间跟着令牌长度走。
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedHash = sha256(expected.getBytes(StandardCharsets.UTF_8));
        byte[] actualHash = sha256((actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expectedHash, actualHash);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
