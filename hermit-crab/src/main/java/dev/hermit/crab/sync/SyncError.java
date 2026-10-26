package dev.hermit.crab.sync;

import dev.hermit.common.error.ErrorCode;

/** Client-facing sync failure. The message is safe to show; it does not include transport details or tokens. */
public record SyncError(ErrorCode code, String message) {
    public SyncError {
        if (code == null) {
            throw new IllegalArgumentException("code");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message");
        }
    }
}
