package dev.hermit.crab.secure;

/**
 * The master key could not be loaded. The message names the source and never the key bytes.
 */
public final class MasterKeyException extends RuntimeException {
    public MasterKeyException(String message) {
        super(message);
    }
}
