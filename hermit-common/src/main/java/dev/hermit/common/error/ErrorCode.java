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
    /**
     * 本机已关掉该能力（控制台开关或 {@code config.yml} 的 {@code capabilities.disabled}）。
     * HTTP 404。能力不存在仍是 {@code NOT_FOUND}。
     */
    CAPABILITY_DISABLED,
    /**
     * 停用墓碑还在，且注册表里已经没有这份规则包。HTTP 409。
     * 已加载的停用包仍可调用，不使用这个码。
     */
    CONCH_DISABLED,
    /**
     * 同一 Idempotency-Key 在有效期内换了请求体。HTTP 409，与 {@code NOT_CONFIGURED}、
     * {@code CONCH_DISABLED} 共用状态码，靠错误码区分。
     */
    IDEMPOTENCY_CONFLICT,
    /**
     * HTTP 409。用于两处：已安装正式规则包时不能换成另一把 Reef 公钥；
     * 导入本地包时目标已存在、但没有确认覆盖。
     * 与正式包或订阅重名仍用 {@code INVALID_INPUT}，不用这个码。
     */
    CONFLICT,
    INTERNAL
}
