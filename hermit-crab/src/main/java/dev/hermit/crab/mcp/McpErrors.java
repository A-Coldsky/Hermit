package dev.hermit.crab.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;

/**
 * Stable MCP error codes. JSON-RPC {@code error.code} stays numeric;
 * {@code error.data.code} and tool {@code structuredContent.error.code} use these strings.
 */
final class McpErrors {
    /** Server-error range. Auth failures are HTTP 401 plus this JSON-RPC code. */
    static final int AUTH_RPC = -32001;

    static final String AUTH_MISSING = "AUTH_MISSING";
    static final String AUTH_INVALID = "AUTH_INVALID";
    static final String SESSION_REQUIRED = "SESSION_REQUIRED";
    static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    static final String SESSION_MISMATCH = "SESSION_MISMATCH";
    static final String PARSE_ERROR = "PARSE_ERROR";
    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String INVALID_PARAMS = "INVALID_PARAMS";
    static final String METHOD_NOT_FOUND = "METHOD_NOT_FOUND";
    static final String REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String INTERNAL = "INTERNAL";

    private McpErrors() {
    }

    record Failure(int status, int rpcCode, String code, String message, String hint) {
    }

    static Failure authMissing() {
        return new Failure(
                401,
                AUTH_RPC,
                AUTH_MISSING,
                "缺少 API Key。请在请求头 Authorization: Bearer <API Key> 或 X-Hermit-Key 中提供。",
                "在请求头 Authorization: Bearer <API Key> 或 X-Hermit-Key 中提供由 Crab 签发的 API Key。");
    }

    static Failure authInvalid() {
        return new Failure(
                401,
                AUTH_RPC,
                AUTH_INVALID,
                "API Key 无效或已吊销。请在 Crab 本机控制台的 API Key 页面，或用 CLI 命令 apikey create 重新签发。",
                "到 Crab 本机控制台 /console/apikeys，或执行 CLI apikey create 重新签发。API Key 由 Crab 签发，不是 Reef。");
    }

    static Failure sessionRequired() {
        return new Failure(
                400,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                SESSION_REQUIRED,
                "会话尚未 initialize。请先调用 initialize，并在后续请求携带响应头 Mcp-Session-Id。",
                "先调用 initialize。");
    }

    static Failure sessionNotFound() {
        return new Failure(
                404,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                SESSION_NOT_FOUND,
                "会话不存在或已过期。请重新调用 initialize。",
                "重新调用 initialize。");
    }

    static Failure sessionMismatch() {
        return new Failure(
                401,
                AUTH_RPC,
                SESSION_MISMATCH,
                "当前 API Key 与会话不匹配。请使用创建该会话的 Key，或重新调用 initialize。",
                "使用创建该会话的 API Key，或重新调用 initialize。");
    }

    static Failure parseError() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.PARSE_ERROR,
                PARSE_ERROR,
                "请求体不是合法 JSON。请发送 JSON-RPC 2.0 对象。",
                "发送 Content-Type 为 application/json 的 JSON-RPC 2.0 对象。");
    }

    static Failure invalidRequest() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                INVALID_REQUEST,
                "JSON-RPC 请求不合法。请使用 jsonrpc 为 2.0 且带 method 的请求。",
                "补上 jsonrpc 与 method；调用再带上 id。");
    }

    static Failure invalidParams() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.INVALID_PARAMS,
                INVALID_PARAMS,
                "JSON-RPC 参数不合法。请按该方法的参数类型重试。",
                "按该方法的参数结构重试，不要把 params 写成数组或错误类型。");
    }

    static Failure methodNotFound() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                METHOD_NOT_FOUND,
                "方法不存在。请改用 initialize、ping、tools/list、tools/call、resources/list 或 resources/read。",
                "改用 initialize、ping、tools/list、tools/call、resources/list 或 resources/read。");
    }

    static Failure requestTooLarge() {
        return new Failure(
                400,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                REQUEST_TOO_LARGE,
                "请求体过大。请缩小请求体后重试。",
                "缩小请求体，使其不超过网关上限。");
    }

    static Failure resourceNotFound() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.RESOURCE_NOT_FOUND,
                NOT_FOUND,
                "资源不存在。请用 resources/list 查看当前 Key 可见的指南。",
                "先调用 resources/list，再读取返回的 URI。");
    }

    static Failure internalError() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.INTERNAL_ERROR,
                INTERNAL,
                "内部错误。请稍后重试。",
                "稍后重试。若持续出现，查看 Crab 日志。");
    }

    /** {@code outputSchema} arrived in the 2025-06-18 revision and stays in 2025-11-25. */
    static boolean outputSchemaSupported(String protocol) {
        return ProtocolVersions.MCP_2025_06_18.equals(protocol)
                || ProtocolVersions.MCP_2025_11_25.equals(protocol);
    }

    static String toolHint(String code, String message) {
        if ("FORBIDDEN".equals(code)) {
            if (McpCalls.NO_CONFIRM.equals(message)) {
                return "客户端需在 initialize 中声明 elicitation，并保持 GET /mcp 的 SSE 连接后再执行写操作。";
            }
            if (McpCalls.REJECTED.equals(message)) {
                return "确认被拒绝，本次未执行。如需执行，请在确认时接受。";
            }
            return "当前 API Key 无权调用该能力。请在 Crab 控制台或 CLI 调整范围后重试。";
        }
        return switch (code == null ? "" : code) {
            case "INVALID_INPUT" -> message != null && message.contains("HTTP 400")
                    ? "上游没有接受这次输入。请按该系统的要求修改参数后重试。"
                    : "按工具 inputSchema 修正参数后重试。缺必填、类型错误或超出 minimum/maximum 都会被拒绝，不会改成合法值。";
            case "NOT_FOUND" -> "确认能力名，或用 tools/list、search_capabilities 查看当前 Key 可见的能力。";
            case "CONCH_DISABLED" -> "该规则包已在 Reef 停用。同步完成后不再提供调用；请管理员重新启用后再同步。";
            case "ACCOUNT_NOT_FOUND" -> "在 Crab 控制台或 CLI 为该规则包创建账号，或改用已有账号名。";
            case "NOT_CONFIGURED" -> "在 Crab 控制台补齐该规则包的配置和凭证。";
            case "AUTH_FAILED" -> "这是上游登录失败，不是 API Key 错误。请检查该账号的凭证，或在控制台执行 test-login。";
            case "SESSION_EXPIRED" -> "上游会话已失效。请检查凭证后重试。";
            case "TIMEOUT" -> "调用超时。请稍后重试。";
            case "UPSTREAM_ERROR" -> "上游返回错误。请稍后重试。";
            case "RULE_MISMATCH" -> "规则与上游响应不一致。请检查已安装的规则包版本。";
            case "INTERNAL" -> "内部错误。请稍后重试。";
            default -> "查看 error.code 后重试。";
        };
    }

    /** One parameter name when the message names exactly one field. */
    static String fieldOf(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String fieldPrefix = "字段 ";
        if (message.startsWith(fieldPrefix)) {
            int end = message.indexOf(' ', fieldPrefix.length());
            if (end > fieldPrefix.length()) {
                return message.substring(fieldPrefix.length(), end);
            }
        }
        String invalidPrefix = "输入无效: ";
        if (message.startsWith(invalidPrefix)) {
            String rest = message.substring(invalidPrefix.length()).trim();
            if (rest.isEmpty() || "输入".equals(rest) || rest.indexOf(',') >= 0 || rest.indexOf(' ') >= 0) {
                return null;
            }
            return rest;
        }
        return switch (message) {
            case "query is required" -> "query";
            case "invalid conch" -> "conch";
            case "name is required" -> "name";
            case "arguments must be an object" -> "arguments";
            case "invalid account name" -> "account";
            default -> null;
        };
    }
}
