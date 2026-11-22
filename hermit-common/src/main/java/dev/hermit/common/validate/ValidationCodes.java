package dev.hermit.common.validate;

public final class ValidationCodes {

    public static final String CONCH_NAME = "CONCH_NAME";
    public static final String ACTION_NAME = "ACTION_NAME";
    public static final String DRIVER = "DRIVER";
    public static final String MISSING_TITLE = "MISSING_TITLE";
    public static final String MISSING_DESCRIPTION = "MISSING_DESCRIPTION";
    public static final String MISSING_CREDENTIALS = "MISSING_CREDENTIALS";
    public static final String MISSING_OUTPUT = "MISSING_OUTPUT";
    public static final String MISSING_EXAMPLES = "MISSING_EXAMPLES";
    public static final String UNSUPPORTED_STEP = "UNSUPPORTED_STEP";
    public static final String UNDECLARED_CREDENTIAL = "UNDECLARED_CREDENTIAL";
    public static final String UNDECLARED_CONFIG = "UNDECLARED_CONFIG";
    public static final String UNDECLARED_VARIABLE = "UNDECLARED_VARIABLE";
    public static final String UNDECLARED_INPUT = "UNDECLARED_INPUT";
    public static final String DUPLICATE_ACTION = "DUPLICATE_ACTION";
    public static final String LOGIN_VARIABLE_EMPTY = "LOGIN_VARIABLE_EMPTY";
    public static final String CONFIG_SECRET = "CONFIG_SECRET";
    public static final String PLAINTEXT_CREDENTIAL = "PLAINTEXT_CREDENTIAL";
    public static final String CREDENTIAL_IN_OUTPUT = "CREDENTIAL_IN_OUTPUT";
    public static final String LOGIN_IN_OUTPUT = "LOGIN_IN_OUTPUT";
    public static final String LOGIN_DEFAULT = "LOGIN_DEFAULT";
    public static final String AUTH_COOKIES = "AUTH_COOKIES";
    public static final String RESERVED_VARIABLE = "RESERVED_VARIABLE";
    public static final String MIN_CRAB_VERSION = "MIN_CRAB_VERSION";
    public static final String TEMPLATE_SYNTAX = "TEMPLATE_SYNTAX";
    public static final String MISSING_NOTES = "MISSING_NOTES";
    public static final String MISSING_RELATED = "MISSING_RELATED";
    public static final String UNKNOWN_RELATED = "UNKNOWN_RELATED";
    public static final String SCRIPT_STEP = "SCRIPT_STEP";
    public static final String MCP_UPSTREAM = "MCP_UPSTREAM";
    public static final String MCP_SCHEMA_REF = "MCP_SCHEMA_REF";
    public static final String MCP_HEADER = "MCP_HEADER";
    public static final String MCP_DESCRIPTION = "MCP_DESCRIPTION";
    public static final String MCP_NO_ACTIONS = "MCP_NO_ACTIONS";
    /** 请求上的字段引擎不会发出去。警告，不阻止保存。 */
    public static final String IGNORED_REQUEST_FIELD = "IGNORED_REQUEST_FIELD";
    /** query 或 form 的值用了 urlencode，发出去时还会再编码。警告。 */
    public static final String DOUBLE_ENCODE = "DOUBLE_ENCODE";
    /** query 字面值里已经有 %xx。警告。 */
    public static final String PERCENT_ENCODED = "PERCENT_ENCODED";
    /** path 或 url 字面值里有 #。警告。 */
    public static final String FRAGMENT_DROPPED = "FRAGMENT_DROPPED";
    /** query_style 不是 url 或 form。错误，阻止保存。 */
    public static final String QUERY_STYLE = "QUERY_STYLE";
    /** body 不是字符串，或与 form、json 同时出现。错误，阻止保存。 */
    public static final String REQUEST_BODY = "REQUEST_BODY";
    /** body 没有声明 Content-Type。警告，不阻止保存。 */
    public static final String BODY_CONTENT_TYPE = "BODY_CONTENT_TYPE";
    /** 主机名含下划线，或含未转成 punycode 的非 ASCII。错误，阻止保存。 */
    public static final String HOSTNAME = "HOSTNAME";
    /** https 的单标签主机不会发送 SNI。警告，不阻止保存。 */
    public static final String SINGLE_LABEL_HOST = "SINGLE_LABEL_HOST";
    /**
     * 提取规格不是映射、没有选择器、选择器不止一种，或 {@code each} 不是映射。
     * 警告，不阻止保存。已发布的包里可能已经有这种形状，加载时不能因此失败。
     */
    public static final String EXTRACT_SPEC = "EXTRACT_SPEC";
    /** 提取规格里不认识的字段。警告，不阻止保存。 */
    public static final String EXTRACT_FIELD = "EXTRACT_FIELD";
    /** {@code optional} 不是布尔字面量。含模板时不报。警告，不阻止保存。 */
    public static final String EXTRACT_OPTIONAL = "EXTRACT_OPTIONAL";
    /** assert 里不认识的键。警告，不阻止保存。 */
    public static final String ASSERT_KEY = "ASSERT_KEY";
    /** {@code status} 的字面量不是数字。含模板时不报。警告，不阻止保存。 */
    public static final String ASSERT_STATUS = "ASSERT_STATUS";

    /** {@code if} 或 {@code repeat} 写法不对：缺 then / steps / max、未知的键、嵌套过深等。阻止保存。 */
    public static final String FLOW_STEP = "FLOW_STEP";
    /** request 的 {@code retry} 写法不对，或用在了不能重复提交的请求上。阻止保存。 */
    public static final String RETRY = "RETRY";
    public static final String CONCURRENT_READS = "CONCURRENT_READS";

    private ValidationCodes() {
    }
}
