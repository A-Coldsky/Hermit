package dev.hermit.crab.runtime;

import dev.hermit.common.error.ErrorCode;

/** A console or CLI trial that stopped before {@link dev.hermit.crab.engine.RuleEngine#execute}. */
public final class TrialRejected extends RuntimeException {
    private final int status;
    private final ErrorCode code;
    private final boolean requestOnly;

    public TrialRejected(int status, ErrorCode code, String message) {
        this(status, code, message, false);
    }

    public TrialRejected(int status, ErrorCode code, String message, boolean requestOnly) {
        super(message == null ? "" : message);
        this.status = status;
        this.code = code == null ? ErrorCode.INTERNAL : code;
        this.requestOnly = requestOnly;
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    /** No trace was stored. Callers must return {@code requestId}, not {@code traceId}. */
    public boolean requestOnly() {
        return requestOnly;
    }
}
