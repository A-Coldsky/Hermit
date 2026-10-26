package dev.hermit.crab.secure;

/**
 * AES-GCM rejected a credential blob. The message is fixed and the cause is omitted so
 * plaintext, keys, and ciphertext bytes cannot ride along in the failure.
 */
public final class CredentialDecryptException extends RuntimeException {
    /** Shown to operators when stored credentials cannot be opened with the current master key. */
    public static final String MESSAGE = "凭证无法解密，需重新配置";

    public CredentialDecryptException() {
        super(MESSAGE);
    }
}
