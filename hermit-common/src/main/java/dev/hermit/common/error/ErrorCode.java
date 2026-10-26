package dev.hermit.common.error;

public enum ErrorCode {
    INVALID_INPUT,
    AUTH_FAILED,
    SESSION_EXPIRED,
    UPSTREAM_ERROR,
    RULE_MISMATCH,
    TIMEOUT,
    NOT_CONFIGURED,
    ACCOUNT_NOT_FOUND,
    FORBIDDEN,
    NOT_FOUND,
    /** Reef 已停用该 Conch。Crab 同步后不再执行它。 */
    CONCH_DISABLED,
    INTERNAL
}
