package dev.hermit.reef.conch;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

final class ConchHashes {

    private ConchHashes() {
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
