package dev.hermit.crab.recorder;

import dev.hermit.common.error.ErrorCode;

/**
 * Expected error from the developer-mode browser recorder or HAR importer.
 * Maps cleanly to HTTP status + {@link ErrorCode} in the console API.
 */
public final class RecorderException extends RuntimeException {
    private final int status;
    private final ErrorCode code;

    public RecorderException(int status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code == null ? ErrorCode.INTERNAL : code;
    }

    public RecorderException(int status, ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code == null ? ErrorCode.INTERNAL : code;
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
