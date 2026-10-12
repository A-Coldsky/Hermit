package dev.hermit.crab.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

import dev.hermit.common.pkg.Ed25519Keys;

/**
 * {@code reef.public_key} is either PEM text or a filesystem path. Relative paths are resolved
 * against the Crab home (the directory that contains {@code config.yml}).
 */
public final class ReefPublicKeys {
    private static final int MAX_FILE_BYTES = 64 * 1024;

    private ReefPublicKeys() {
    }

    public static PublicKey read(String configured, Path home) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String text = configured.strip();
        if (text.contains("-----BEGIN")) {
            return parse(text);
        }
        Path path = Path.of(text);
        if (!path.isAbsolute()) {
            path = home.resolve(text).normalize();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("reef.public_key file cannot be read");
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new IllegalStateException("reef.public_key file cannot be read");
            }
            return parse(Files.readString(path));
        } catch (IOException ex) {
            throw new IllegalStateException("reef.public_key file cannot be read");
        }
    }

    private static PublicKey parse(String pem) {
        try {
            return Ed25519Keys.readPublicPem(pem);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("reef.public_key is not a usable Ed25519 public key");
        }
    }
}
