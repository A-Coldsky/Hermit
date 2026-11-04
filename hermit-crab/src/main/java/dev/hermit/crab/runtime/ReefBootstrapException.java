package dev.hermit.crab.runtime;

import dev.hermit.common.error.ErrorCode;

/** Bootstrap import failed. The message is safe to return to the console and the CLI. */
public final class ReefBootstrapException extends RuntimeException {
    private final int status;
    private final ErrorCode code;

    public ReefBootstrapException(int status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
