package dev.hermit.crab.sync;

import dev.hermit.common.error.ErrorCode;

/**
 * Subscribe or unsubscribe was rejected. {@link #status()} is the HTTP status; it is not always
 * {@code StatusCodes.of(code())} because a local-name clash is 409 with {@link ErrorCode#INVALID_INPUT}.
 */
public final class SubscriptionException extends RuntimeException {
    public static final String LOCAL_CONFLICT = "与本机本地规则包同名";
    public static final String NOT_IN_CATALOG = "Reef 上当前不能订阅这个规则包。";
    public static final String NOT_SUBSCRIBED = "还没有订阅这个规则包。";
    public static final String NAME_INVALID = "规则包名称无效。";
    public static final String NOT_CONFIGURED = "尚未连接 Reef，无法同步。";

    private final int status;
    private final ErrorCode code;

    public SubscriptionException(int status, ErrorCode code, String message) {
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
