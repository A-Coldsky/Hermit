package dev.hermit.crab.engine;

/**
 * 开发追踪里一条请求步骤的警告。{@code code} 进前端类型；{@code message} 是给用户看的中文，入库前脱敏。
 */
record TraceWarning(String code, String message) {
    static final String CROSS_ORIGIN = "cross_origin";
    static final String SECURE_COOKIE = "secure_cookie";
    static final String COOKIE_REJECTED = "cookie_rejected";
}
