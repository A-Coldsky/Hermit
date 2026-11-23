package dev.hermit.crab.gateway;

import java.util.Map;

/**
 * 网关和控制台报错里给人看的那句话。下层（存储、校验）仍抛英文短句，写响应前在这里换成中文，
 * 不认识的原样返回。不改错误码，前端和调用方按错误码判断，不靠这句话。
 */
public final class UserMessages {
    /** 与 MCP 的 AUTH_MISSING / AUTH_INVALID 同一句。 */
    public static final String MISSING_KEY =
            "缺少 API Key。请在请求头 Authorization: Bearer <API Key> 或 X-Hermit-Key 中提供。";
    public static final String INVALID_KEY =
            "API Key 无效或已吊销。请在 Crab 本机控制台的 API Key 页面，或用 CLI 命令 apikey create 重新签发。";

    private static final Map<String, String> EXACT = Map.ofEntries(
            Map.entry("name is required", "缺少 name。"),
            Map.entry("arguments must be an object", "arguments 必须是对象。"),
            Map.entry("query must be a string", "query 必须是字符串。"),
            Map.entry("invalid conch", "conch 不合法。"),
            Map.entry("not found", "不存在"),
            Map.entry("conch not found", "规则包不存在"),
            Map.entry("capability not found", "能力不存在"),
            Map.entry("group not found", "分组不存在"),
            Map.entry("api key not found", "API Key 不存在"),
            Map.entry("account not found", "没有这个账号。"),
            Map.entry("account already exists", "已经有同名账号。"),
            Map.entry("api key is already revoked", "这把 API Key 已经吊销。"),
            Map.entry("invalid api key", INVALID_KEY),
            Map.entry("missing api key", MISSING_KEY),
            Map.entry("console access denied", "控制台只接受本机访问。"),
            Map.entry("request body too large", "请求体太大，上限 1 MB。"),
            Map.entry("invalid json", "请求体不是合法的 JSON。"),
            Map.entry("request body must be a json object", "请求体必须是 JSON 对象。"),
            Map.entry("invalid Idempotency-Key", "Idempotency-Key 格式不对：1 到 128 个可见 ASCII 字符。"),
            Map.entry("internal error", "内部错误"),
            Map.entry("invalid input", "输入无效"),
            Map.entry("unknown field", "有未知字段。"),
            Map.entry("unknown config field", "没有这个配置项。"),
            Map.entry("unknown credential field", "没有这个凭证字段。"),
            Map.entry("conch declares no credentials", "这个规则包没有声明凭证。"),
            Map.entry("fields are required", "至少要填一个字段。"),
            Map.entry("credential fields are required", "至少要填一个凭证字段。"),
            Map.entry("values must be strings", "值必须是字符串。"),
            Map.entry("expected a list of strings", "这里要填字符串列表。"),
            Map.entry("input must be an object", "input 必须是对象。"),
            Map.entry("invalid report settings", "上报设置不合法。"),
            Map.entry("file or yaml body is required", "需要上传 YAML 文件，或把 YAML 放在请求体里。"),
            Map.entry("api key name is required", "需要填写 Key 的名称。"));

    private UserMessages() {
    }

    public static String zh(String message) {
        if (message == null) {
            return null;
        }
        String exact = EXACT.get(message);
        if (exact != null) {
            return exact;
        }
        if (message.startsWith("unknown field: ")) {
            return "未知字段：" + message.substring("unknown field: ".length()) + "。";
        }
        if (message.startsWith("invalid value for field ")) {
            return "字段 " + message.substring("invalid value for field ".length()) + " 的值不合法。";
        }
        if (message.startsWith("invalid url for field ")) {
            return "字段 " + message.substring("invalid url for field ".length()) + " 不是合法的 http(s) 地址。";
        }
        if (message.endsWith(" is required") && message.indexOf(' ') == message.length() - " is required".length()) {
            return "缺少 " + message.substring(0, message.length() - " is required".length()) + "。";
        }
        return message;
    }

    /**
     * 控制台和 CLI 共用的一句说明。有换行时只留第一行；超过 200 字时截到 200 并加省略号。
     * 第一行像堆栈（去掉行首空白后以 {@code at } 开头，或含 {@code Exception}）时返回 {@code invalid}，不印堆栈。
     */
    public static String oneLine(String message, String invalid) {
        if (message == null) {
            return invalid;
        }
        int breakAt = lineBreak(message);
        String line = breakAt < 0 ? message : message.substring(0, breakAt);
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line = line.substring(0, line.length() - 1);
        }
        if (looksLikeStack(line) || (breakAt >= 0 && line.isEmpty())) {
            return invalid;
        }
        if (line.length() > 200) {
            return line.substring(0, 200) + "…";
        }
        return line;
    }

    private static int lineBreak(String message) {
        int newline = message.indexOf('\n');
        int carriage = message.indexOf('\r');
        if (newline < 0) {
            return carriage;
        }
        if (carriage < 0) {
            return newline;
        }
        return Math.min(newline, carriage);
    }

    private static boolean looksLikeStack(String line) {
        return line.stripLeading().startsWith("at ") || line.contains("Exception");
    }
}
