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
    /**
     * 同一 Idempotency-Key 在有效期内换了请求体。HTTP 409，与 {@code NOT_CONFIGURED}、
     * {@code CONCH_DISABLED} 共用状态码，靠错误码区分。
     */
    IDEMPOTENCY_CONFLICT,
    INTERNAL
}
