package dev.hermit.crab.mcp;

import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.Invoker;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;

/**
 * Stable MCP error codes. JSON-RPC {@code error.code} stays numeric;
 * {@code error.data.code} and tool {@code structuredContent.error.code} use these strings.
 */
final class McpErrors {
    /** Server-error range. Auth failures are HTTP 401 plus this JSON-RPC code. */
    static final int AUTH_RPC = -32001;
    /** Disallowed Origin. HTTP 403 plus this JSON-RPC code. */
    static final int ORIGIN_RPC = -32003;

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
    static final String ORIGIN_REJECTED = "ORIGIN_REJECTED";
    static final String UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL";
    /** 工具名不存在，或当前 Key 的目录里看不到。 */
    static final String UNKNOWN_TOOL = "没有这个工具，或当前 API Key 看不到它。";

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

    static Failure devModeClosed() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                "NOT_CONFIGURED",
                AuthoringCalls.DEV_MODE_CLOSED,
                "到 Crab 控制台概览打开开发者模式后再读取这份指南。");
    }

    static Failure authoringRemote() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                "FORBIDDEN",
                AuthoringCalls.LOCAL_ONLY,
                "从本机连接 Crab 后再读取这份指南。不带 Origin 也不能从外部地址读取。");
    }

    static Failure resourceNotFound() {
        return new Failure(
                200,
                McpSchema.ErrorCodes.RESOURCE_NOT_FOUND,
                NOT_FOUND,
                "资源不存在。请用 resources/list 查看当前 Key 可见的指南。",
                "先调用 resources/list，再读取返回的 URI。");
    }

    static Failure originRejected() {
        return new Failure(
                403,
                ORIGIN_RPC,
                ORIGIN_REJECTED,
                "Origin 不在允许范围内。",
                "从本机控制台源站访问，或把该源站写入 mcp.allowed_origins。不带 Origin 的本机客户端可以直接访问。");
    }

    static Failure unsupportedProtocolHeader() {
        return new Failure(
                400,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                UNSUPPORTED_PROTOCOL,
                "MCP-Protocol-Version 不是支持的协议版本。",
                "去掉该头以沿用 initialize 协商的版本，或改成 2024-11-05、2025-03-26、2025-06-18、2025-11-25。");
    }

    /** Used only when {@link McpTransportPolicy#NEGOTIATE_UNKNOWN_PROTOCOL} is false. */
    static Failure unsupportedProtocolVersion() {
        return new Failure(
                400,
                McpSchema.ErrorCodes.INVALID_REQUEST,
                UNSUPPORTED_PROTOCOL,
                "protocolVersion 不是支持的协议版本。",
                "改成 2024-11-05、2025-03-26、2025-06-18、2025-11-25 之一。");
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
        return toolHint(code, message, false);
    }

    /**
     * 直接模式没有 {@code search_capabilities}。目录模式才建议用它和 {@code tools/list} 查找能力。
     */
    static String toolHint(String code, String message, boolean directory) {
        if (AuthoringCalls.DEV_MODE_CLOSED.equals(message)) {
            return "到 Crab 控制台概览打开开发者模式后再试。";
        }
        if (AuthoringCalls.LOCAL_ONLY.equals(message)) {
            return "从本机连接 Crab 后再调用。不带 Origin 也不能从外部地址调用这些工具。";
        }
        if (AuthoringCalls.READ_ONLY_SAVE.equals(message)
                || (message != null && message.startsWith(AuthoringCalls.READ_ONLY_TRIAL_PREFIX))) {
            return "换一把没有勾选「只允许调用只读能力」的 Key。只读 Key 仍可以校验，也可以试跑 read_only 为 true 的能力。";
        }
        if (McpCalls.TRACE_DENIED.equals(message)) {
            return "点名该本地规则包，或用试跑的那把全部权限 Key 读取自己的试跑追踪。正式包的追踪不能读。";
        }
        if (McpCalls.TRACE_OUT_OF_SCOPE.equals(message)) {
            return "到 Crab 控制台「调用记录」按这个编号查看步骤和错误。只有开发者模式下的本地规则包调用才有开发追踪。";
        }
        if (Invoker.RULE_DISABLED_CALL.equals(message)) {
            return "这版规则没有通过当前校验。到 Crab 控制台的规则包详情里更新、退回或改好后重新导入，再调用。";
        }
        if (message != null && message.contains("编号不存在")) {
            return "核对追踪编号。这条编号没有对应的追踪。";
        }
        if (message != null && message.contains("试跑被拒")) {
            return "这次试跑没有执行，没有留下追踪。";
        }
        if (message != null && message.contains("追踪已删")) {
            return "关掉开发者模式时会删除全部追踪。重新打开后再试跑才会有新的追踪。";
        }
        if (McpCalls.CONFIRM_TIMEOUT.equals(message)) {
            return "请重新发起，并在客户端确认。";
        }
        if (message != null && message.contains("未定义的变量")) {
            return "请在规则中先声明或提取这个变量。";
        }
        if (CrabRuntime.TRIAL_ACCOUNT_INVALID.equals(message)) {
            return "账号名只能是字母、数字、下划线或连字符，最长 64 个字符。不要把口令放在 account 里。";
        }
        if (message != null && message.contains("不要换包名重新保存")) {
            return AuthoringCalls.SAME_NAME;
        }
        if (AuthoringCalls.OVERWRITE_DECLINED.equals(message)) {
            return "本地规则包没有改动。要覆盖时，在客户端确认。";
        }
        if (AuthoringCalls.OFFICIAL.equals(message)) {
            return "正式包不能在这里保存或试跑。先在本地规则包上开发，再导出上传。";
        }
        if (AuthoringCalls.CONFIRM_PARAM.equals(message)) {
            return "去掉 confirm。覆盖已有本地规则包时，在客户端确认。";
        }
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
            case "NOT_FOUND" -> directory
                    ? "确认能力名，或用 tools/list、search_capabilities 查看当前 Key 可见的能力。"
                    : "确认能力名，或用 tools/list 查看当前 Key 可见的能力。";
            case "CAPABILITY_DISABLED" -> "在 Crab 控制台重新启用该能力，或从 config.yml 的 capabilities.disabled 里去掉后再调用。";
            case "CONCH_DISABLED" -> message != null && message.startsWith("该规则包已在本机停用")
                    ? "在 Crab 控制台的规则包详情里重新启用该规则包后再调用。"
                    : "该规则包已在 Reef 停用，且本机没有可调用的已安装版本。管理员重新启用后同步即可装回；已安装的版本仍可调用。";
            case "ACCOUNT_NOT_FOUND" -> "在 Crab 控制台或 CLI 为该规则包创建账号，或改用已有账号名。";
            case "NOT_CONFIGURED" -> "在 Crab 控制台补齐该规则包的配置和凭证。";
            case "AUTH_FAILED" -> "这是上游登录失败，不是 API Key 错误。请检查该账号的凭证，或在控制台执行测试登录。";
            case "SESSION_EXPIRED" -> "上游会话已失效。请检查凭证后重试。";
            case "TIMEOUT" -> "调用超时。请稍后重试。";
            case "UPSTREAM_ERROR" -> message != null && message.startsWith("上游状态码 4")
                    // 4xx 不是暂时故障，重试结果一样，要改请求。
                    ? "上游拒绝了这个请求（" + message.substring("上游状态码 ".length()).trim()
                            + "），重试结果一样。核对规则里的请求路径、方法和参数是否与 HAR 一致。"
                    : "上游返回错误。请稍后重试。";
            case "RULE_MISMATCH" -> "规则与上游响应不一致。请检查已安装的规则包版本。";
            case "INTERNAL" -> "内部错误。请稍后重试。";
            case "IDEMPOTENCY_CONFLICT" -> "这个 Idempotency-Key 已经用于另一个请求体。请换一个新键，或原样重发上一次的请求体。";
            case "LOGIN_INPUT_REQUIRED" -> "到 Crab 控制台的账号页输入验证码后再试。不要把验证码发给 AI。";
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
            case "账号名不合法。", "invalid account name" -> "account";
            default -> null;
        };
    }
}
