package dev.hermit.crab.secure;

/**
 * AES-GCM rejected a credential blob. The message is fixed and the cause is omitted so
 * plaintext, keys, and ciphertext bytes cannot ride along in the failure.
 */
public final class CredentialDecryptException extends RuntimeException {
    public CredentialDecryptException() {
        super("credential decryption failed");
    }
}
