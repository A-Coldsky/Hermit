package dev.hermit.reef.web;

/**
 * Reef HTTP 接口自己的错误码。Crab 能力调用用 hermit-common 的 ErrorCode，两套不合并。
 */
public enum ReefErrorCode {
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    INVALID_INPUT("invalid input"),
    NOT_FOUND("not found"),
    CONFLICT("conflict"),
    INTERNAL("internal error");

    private final String message;

    ReefErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
