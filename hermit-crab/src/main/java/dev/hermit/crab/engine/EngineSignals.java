package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hermit.common.error.ErrorCode;

final class RuleProblem extends RuntimeException {

    final ErrorCode code;
    final String summary;
    final String method;
    final String path;
    final Integer status;
    /** 这次已经组好的线上请求。校验失败、还没组请求时为 null。 */
    final Map<String, Object> wire;
    final Map<String, Object> transport;
    /** 已经解码的响应头和正文。5xx 和重定向过多时留给追踪；其它失败为 null。 */
    final Map<String, String> responseHeaders;
    final String responseBody;
    final List<TraceWarning> warnings;
    /** 脚本抛出时的位置和说明。其它失败为 null。 */
    final ScriptFault script;
    /** 明确的重试原因分类（"connect" 或 "timeout"），未指定时按状态码/摘要/传输字段判定。 */
    final String retryReason;

    RuleProblem(ErrorCode code, String summary, String method, String path, Integer status) {
        this(code, summary, method, path, status, null, null);
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire
    ) {
        this(code, summary, method, path, status, wire, null);
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire,
            Map<String, Object> transport
    ) {
        this(code, summary, method, path, status, wire, transport, null, null, List.of());
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire,
            Map<String, Object> transport,
            Map<String, String> responseHeaders,
            String responseBody,
            List<TraceWarning> warnings
    ) {
        this(code, summary, method, path, status, wire, transport, responseHeaders, responseBody, warnings, null, null);
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire,
            Map<String, Object> transport,
            Map<String, String> responseHeaders,
            String responseBody,
            List<TraceWarning> warnings,
            ScriptFault script
    ) {
        this(code, summary, method, path, status, wire, transport, responseHeaders, responseBody, warnings, script, null);
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire,
            Map<String, Object> transport,
            Map<String, String> responseHeaders,
            String responseBody,
            List<TraceWarning> warnings,
            ScriptFault script,
            String retryReason
    ) {
        super(summary);
        this.code = code;
        this.summary = summary;
        this.method = method;
        this.path = path;
        this.status = status;
        this.wire = wire;
        this.transport = transport == null || transport.isEmpty() ? null : Map.copyOf(transport);
        this.responseHeaders = copyHeaders(responseHeaders);
        this.responseBody = responseBody;
        this.warnings = warnings == null || warnings.isEmpty() ? List.of() : List.copyOf(warnings);
        this.script = script;
        this.retryReason = retryReason;
    }

    RuleProblem withWire(Map<String, Object> captured) {
        if (wire != null || captured == null) {
            return this;
        }
        return new RuleProblem(
                code, summary, method, path, status, captured, transport, responseHeaders, responseBody, warnings,
                script, retryReason);
    }

    RuleProblem withExchange(Map<String, String> headers, String body, List<TraceWarning> notices) {
        return new RuleProblem(
                code, summary, method, path, status, wire, transport, headers, body, notices, script, retryReason);
    }

    RuleProblem withScript(ScriptFault fault) {
        return new RuleProblem(
                code, summary, method, path, status, wire, transport, responseHeaders, responseBody, warnings, fault,
                retryReason);
    }

    RuleProblem withRetryReason(String reason) {
        return new RuleProblem(
                code, summary, method, path, status, wire, transport, responseHeaders, responseBody, warnings, script,
                reason);
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy.isEmpty() ? null : Map.copyOf(copy);
    }
}

final class StepFailure extends RuntimeException {

    final ErrorCode code;
    final String summary;
    final int index;
    final String type;
    final List<StepLog> logs;

    StepFailure(ErrorCode code, String summary, int index, String type, List<StepLog> logs) {
        super(summary);
        this.code = code;
        this.summary = summary;
        this.index = index;
        this.type = type;
        this.logs = logs == null ? List.of() : List.copyOf(logs);
    }
}

final class SessionLost extends RuntimeException {
}

final class DeadlineExceeded extends RuntimeException {

    final int index;
    final String type;
    final List<StepLog> logs;
    /** 能力 deadline 未到时的内部超时分类（"connect" 或 "timeout"）；真正到达能力 deadline 时为 null。 */
    final String retryReason;

    DeadlineExceeded(int index, String type, List<StepLog> logs) {
        this(index, type, logs, null);
    }

    DeadlineExceeded(int index, String type, List<StepLog> logs, String retryReason) {
        this.index = index;
        this.type = type;
        this.logs = logs == null ? List.of() : List.copyOf(logs);
        this.retryReason = retryReason;
    }
}
