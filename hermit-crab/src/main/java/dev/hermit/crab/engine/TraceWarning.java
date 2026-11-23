package dev.hermit.crab.engine;

/**
 * 开发追踪里一条请求步骤的警告。{@code code} 进前端类型；{@code message} 是给用户看的中文，入库前脱敏。
 */
record TraceWarning(String code, String message) {
    static final String CROSS_ORIGIN = "cross_origin";
    static final String SECURE_COOKIE = "secure_cookie";
    static final String COOKIE_REJECTED = "cookie_rejected";
    static final String BODY_ENDED_BY_IDLE = "body_ended_by_idle";
    /** {@code follow_redirects: true} 时 Location 无法按 URI 解析。message 不带 Location。 */
    static final String LOCATION_UNRESOLVED = "location_unresolved";
    /** query 或 form 的值是对象或数组，已按 JSON 写入。message 只带字段名。 */
    static final String NOT_TEXT = "not_text";
    /**
     * 登录变量不是字符串，没有写入登录快照。message 只带变量名，不带值。
     * 登录仍然成功，正式调用也不报错。
     */
    static final String SNAPSHOT_SKIPPED = "snapshot_skipped";
    /** 带着身份里已有的 Cookie 登录没成功，清空后从头登录了一次。 */
    static final String SHARED_SESSION_CLEARED = "shared_session_cleared";
}
