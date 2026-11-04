package dev.hermit.reef.web;

/**
 * Reef HTTP 接口自己的错误码。Crab 能力调用用 hermit-common 的 ErrorCode，两套不合并。
 * {@link #message()} 是该码的默认短句。校验失败、坏请求体、方法不对用下面的常量，错误码仍是 {@link #INVALID_INPUT}。
 */
public enum ReefErrorCode {
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    INVALID_INPUT("输入无效"),
    NOT_FOUND("not found"),
    CONFLICT("conflict"),
    INTERNAL("internal error");

    /** Bean 校验未通过。响应不带字段值。 */
    public static final String VALIDATION_FAILED = "请求校验未通过";

    /** 请求体读不成目标类型。响应不回显正文。 */
    public static final String UNREADABLE_BODY = "请求体无法解析";

    /** 该方法没有对应接口。错误码仍是 {@link #INVALID_INPUT}。 */
    public static final String METHOD_NOT_ALLOWED = "不支持该请求方法";

    private final String message;

    ReefErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
