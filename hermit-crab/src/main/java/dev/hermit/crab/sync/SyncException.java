package dev.hermit.crab.sync;

import java.util.Optional;

public final class SyncException extends RuntimeException {

    public SyncException(String message) {
        super(message);
    }

    public SyncException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Set when Reef could not be reached. Empty for manifest and HTTP status failures, which keep
     * their internal reason and do not replace the last successful sync status.
     */
    public Optional<SyncError> clientError() {
        return SyncErrors.clientError(this);
    }
}
