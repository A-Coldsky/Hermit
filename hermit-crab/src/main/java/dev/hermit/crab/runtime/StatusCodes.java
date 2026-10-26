package dev.hermit.crab.runtime;

import dev.hermit.common.error.ErrorCode;

public final class StatusCodes {
    private StatusCodes() {
    }

    public static int of(ErrorCode code) {
        return switch (code) {
            case FORBIDDEN -> 403;
            case NOT_FOUND, ACCOUNT_NOT_FOUND -> 404;
            case NOT_CONFIGURED, CONCH_DISABLED -> 409;
            case INVALID_INPUT -> 400;
            case TIMEOUT -> 504;
            case UPSTREAM_ERROR -> 502;
            case AUTH_FAILED, SESSION_EXPIRED, RULE_MISMATCH -> 422;
            case INTERNAL -> 500;
        };
    }
}
