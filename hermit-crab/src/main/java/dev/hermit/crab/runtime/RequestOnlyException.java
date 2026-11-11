package dev.hermit.crab.runtime;

import dev.hermit.common.error.ErrorCode;

/** A console rejection that never stored a trace. The response uses {@code requestId}. */
public final class RequestOnlyException extends RuntimeException {
    private final int status;
    private final ErrorCode code;

    public RequestOnlyException(int status, ErrorCode code, String message) {
        super(message == null ? "" : message);
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
