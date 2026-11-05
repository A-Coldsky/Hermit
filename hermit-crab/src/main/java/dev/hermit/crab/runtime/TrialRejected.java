package dev.hermit.crab.runtime;

import dev.hermit.common.error.ErrorCode;

/** A console or CLI trial that stopped before {@link dev.hermit.crab.engine.RuleEngine#execute}. */
public final class TrialRejected extends RuntimeException {
    private final int status;
    private final ErrorCode code;

    public TrialRejected(int status, ErrorCode code, String message) {
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
