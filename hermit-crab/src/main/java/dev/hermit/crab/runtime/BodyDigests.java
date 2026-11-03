package dev.hermit.crab.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of the raw request body bytes. Not a canonical JSON form. */
public final class BodyDigests {
    private BodyDigests() {
    }

    public static String sha256(byte[] body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }
}
