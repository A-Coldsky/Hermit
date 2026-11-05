package dev.hermit.crab.mcp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.DevTraceDraft;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallChannel;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.DevTraceStore;
import dev.hermit.crab.store.Origin;
import io.modelcontextprotocol.spec.McpSchema;

final class McpCalls {
    static final String NO_CONFIRM = "客户端不支持确认，拒绝执行写操作";
    static final String REJECTED = "用户拒绝执行";

    private final CrabRuntime runtime;
    private final McpCatalog catalog;

    McpCalls(CrabRuntime runtime, McpCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    /** 调用记录、响应头和失败体共用的这一次编号。目录模式决定未知工具的 hint。 */
    private record Attempt(boolean directory, String traceId) {
    }

    McpSchema.CallToolResult call(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            JsonNode params
    ) {
        return call(principal, session, headerAccount, params, UUID.randomUUID().toString());
    }

    McpSchema.CallToolResult call(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            JsonNode params,
            String traceId
    ) {
        Attempt attempt = new Attempt(catalog.directory(principal), traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString()
                : traceId);
        McpSchema.CallToolRequest request;
        try {
            request = McpJson.convert(params, McpSchema.CallToolRequest.class);
        } catch (IllegalArgumentException ex) {
            throw new McpProtocolException();
        }
        String name = request.name() == null ? "" : request.name();
        if (name.isBlank()) {
            return fail(attempt, "INVALID_INPUT", "name is required");
        }
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        if (McpCatalog.DEBUG_TRACE.equals(name)) {
            if (!runtime.debug().get().enabled()) {
                return missingTool(attempt, name);
            }
            return readTrace(principal, arguments, attempt);
        }
        if (attempt.directory()) {
            return directory(principal, session, headerAccount, name, arguments, attempt);
        }
        Optional<McpCatalog.CapabilityRef> found = catalog.find(principal, name);
        if (found.isEmpty()) {
            return missingTool(attempt, name);
        }
        return invokeDirect(principal, headerAccount, found.get(), arguments, attempt);
    }

    private McpSchema.CallToolResult directory(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            String name,
            Map<String, Object> arguments,
            Attempt attempt
    ) {
        return switch (name) {
            case McpCatalog.LIST_SYSTEMS -> ok(catalog.listSystems(principal));
            case McpCatalog.SEARCH -> search(principal, arguments, attempt);
            case McpCatalog.DESCRIBE -> describe(principal, arguments, attempt);
            case McpCatalog.CALL -> callCapability(principal, session, headerAccount, arguments, attempt);
            default -> fail(attempt, "NOT_FOUND", "not found");
        };
    }

    private McpSchema.CallToolResult search(ApiKeyPrincipal principal, Map<String, Object> arguments, Attempt attempt) {
        Object query = arguments.get("query");
        if (!(query instanceof String text) || text.isBlank()) {
            if (query != null && !(query instanceof String)) {
                return fail(attempt, "INVALID_INPUT", "query must be a string", "query");
            }
            return fail(attempt, "INVALID_INPUT", "query is required");
        }
        Object conch = arguments.get("conch");
        if (conch != null && !(conch instanceof String)) {
            return fail(attempt, "INVALID_INPUT", "invalid conch");
        }
        return ok(catalog.search(principal, text, conch instanceof String name ? name : null));
    }

    private McpSchema.CallToolResult describe(ApiKeyPrincipal principal, Map<String, Object> arguments, Attempt attempt) {
        Object name = arguments.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return fail(attempt, "INVALID_INPUT", "name is required");
        }
        return catalog.describe(principal, text)
                .map(McpCalls::ok)
                .orElseGet(() -> fail(attempt, "NOT_FOUND", "not found"));
    }

    private McpSchema.CallToolResult callCapability(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            Map<String, Object> arguments,
            Attempt attempt
    ) {
        Object name = arguments.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return fail(attempt, "INVALID_INPUT", "name is required");
        }
        Optional<McpCatalog.CapabilityRef> found = catalog.find(principal, text);
        if (found.isEmpty()) {
            return missingTool(attempt, text);
        }
        Object rawArguments = arguments.get("arguments");
        Map<String, Object> input = copyObject(rawArguments);
        if (rawArguments != null && input == null) {
            return fail(attempt, "INVALID_INPUT", "arguments must be an object");
        }
        if (input == null) {
            input = Map.of();
        }
        Optional<String> account = account(headerAccount, arguments.get("account"));
        if (account == null) {
            return fail(attempt, "INVALID_INPUT", "invalid account name", "account");
        }
        if (!CapabilityHttp.readOnly(found.get().action())) {
            McpSchema.CallToolResult confirmed = confirm(session, found.get().action(), input, account, attempt);
            if (confirmed != null) {
                return confirmed;
            }
        }
        return invoke(principal, found.get(), input, account, attempt);
    }

    private McpSchema.CallToolResult invokeDirect(
            ApiKeyPrincipal principal,
            Optional<String> headerAccount,
            McpCatalog.CapabilityRef ref,
            Map<String, Object> arguments,
            Attempt attempt
    ) {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>(arguments);
        // 账号选择不是能力入参。additionalProperties: false 校验前先拿掉，避免被当成未声明字段。
        String selector = ref.action().input().containsKey("account") ? "_account" : "account";
        Object rawAccount = input.remove(selector);
        Optional<String> account = account(headerAccount, rawAccount);
        if (account == null) {
            return fail(attempt, "INVALID_INPUT", "invalid account name", selector);
        }
        return invoke(principal, ref, input, account, attempt);
    }

    private McpSchema.CallToolResult confirm(
            McpSession session,
            Action action,
            Map<String, Object> input,
            Optional<String> account,
            Attempt attempt
    ) {
        if (session == null || !session.supportsElicitation() || !session.streaming()) {
            return fail(attempt, "FORBIDDEN", NO_CONFIRM);
        }
        StringBuilder message = new StringBuilder("确认执行 ");
        message.append(action.title() == null || action.title().isBlank() ? action.name() : action.title());
        message.append("？");
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            message.append('\n').append(entry.getKey()).append('=').append(ApiJson.write(entry.getValue()));
        }
        if (account.isPresent()) {
            message.append("\naccount=").append(ApiJson.write(account.get()));
        }
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("confirm", Map.of("type", "boolean", "description", "确认执行")));
        JsonNode answer;
        try {
            answer = session.elicit(McpSchema.ElicitFormRequest.builder(message.toString(), schema).build());
        } catch (TimeoutException ex) {
            return fail(attempt, "INTERNAL", "确认超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fail(attempt, "INTERNAL", "internal error");
        }
        JsonNode confirm = answer == null ? null : answer.path("content").path("confirm");
        boolean accepted = answer != null
                && "accept".equals(answer.path("action").asText(""))
                && confirm.isBoolean()
                && confirm.booleanValue();
        if (!accepted) {
            return fail(attempt, "FORBIDDEN", REJECTED);
        }
        return null;
    }

    /**
     * A tool name is {@code conch__action}. A tombstoned conch that is not loaded is
     * {@code CONCH_DISABLED}. A loaded conch, including one Reef has stopped distributing,
     * is called through {@code Invoker} and is not handled here.
     */
    private McpSchema.CallToolResult missingTool(Attempt attempt, String toolName) {
        String disabled = longestConchPrefix(toolName, runtime.disabledConches().names());
        if (disabled != null && runtime.registry().find(disabled).isEmpty()) {
            String live = longestConchPrefix(toolName, runtime.registry().snapshot().keySet());
            if (live == null || live.length() <= disabled.length()) {
                return fail(attempt, "CONCH_DISABLED", DisabledConches.CALL_MESSAGE);
            }
        }
        return fail(attempt, "NOT_FOUND", "not found");
    }

    private McpSchema.CallToolResult readTrace(
            ApiKeyPrincipal principal,
            Map<String, Object> arguments,
            Attempt attempt
    ) {
        if (arguments.size() != 1 || !arguments.containsKey("traceId")) {
            return fail(attempt, "INVALID_INPUT", arguments.containsKey("traceId")
                    ? "unknown field"
                    : "traceId is required");
        }
        Object raw = arguments.get("traceId");
        if (!(raw instanceof String traceId) || traceId.isBlank()) {
            return fail(attempt, "INVALID_INPUT", "traceId is required");
        }
        DevTraceStore.Trace trace = runtime.devTraces().find(traceId).orElse(null);
        if (trace == null || !visible(principal, trace)) {
            return fail(attempt, "NOT_FOUND", "not found");
        }
        Object devTrace;
        try {
            devTrace = ApiJson.objectMap(trace.body().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            return fail(attempt, "NOT_FOUND", "not found");
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", trace.traceId());
        body.put("conch", trace.conch());
        body.put("account", trace.account());
        body.put("action", trace.action());
        body.put("note", DevTraceDraft.UNTRUSTED_NOTE);
        body.put("untrusted", true);
        body.put("createdAt", trace.createdAt() == null ? null : trace.createdAt().toString());
        body.put("expiresAt", trace.expiresAt() == null ? null : trace.expiresAt().toString());
        body.put("devTrace", devTrace);
        return ok(body);
    }

    /**
     * Local traces only. A call recorded under another API key is hidden.
     * Console trials have no key, so any key that names this local conch may read them.
     * {@code all: true} does not name local conches and cannot read them.
     */
    private boolean visible(ApiKeyPrincipal principal, DevTraceStore.Trace trace) {
        if (trace.origin() != Origin.LOCAL || principal == null || principal.scope() == null) {
            return false;
        }
        if (!principal.scope().conches().contains(trace.conch())) {
            return false;
        }
        CallLogStore.CallDetail detail = runtime.callLogs().findByTraceId(trace.traceId()).orElse(null);
        if (detail != null && detail.call().apiKeyId() != null
                && !detail.call().apiKeyId().equals(principal.id())) {
            return false;
        }
        if (DevTraceStore.MCP_REFRESH_ACTION.equals(trace.action())) {
            return true;
        }
        ConchDoc doc = runtime.registry().find(trace.conch()).orElse(null);
        Action action = doc == null ? null : doc.actions().get(trace.action());
        if (action == null) {
            return !principal.scope().readOnlyOnly();
        }
        return principal.permits(trace.conch(), trace.action(), CapabilityHttp.readOnly(action));
    }

    private static String longestConchPrefix(String toolName, Set<String> conches) {
        if (toolName == null || conches == null) {
            return null;
        }
        String best = null;
        for (String conch : conches) {
            if (conch == null || conch.isEmpty()) {
                continue;
            }
            if (toolName.startsWith(conch + "__") && (best == null || conch.length() > best.length())) {
                best = conch;
            }
        }
        return best;
    }

    private McpSchema.CallToolResult invoke(
            ApiKeyPrincipal principal,
            McpCatalog.CapabilityRef ref,
            Map<String, Object> input,
            Optional<String> account,
            Attempt attempt
    ) {
        InvokeOutcome outcome;
        try {
            // MCP 不传 Idempotency-Key。同键回放只在 HTTP POST。
            outcome = runtime.invoker().invoke(
                    principal,
                    ref.conch().conch(),
                    ref.action().name(),
                    input,
                    account,
                    Optional.empty(),
                    null,
                    CallChannel.MCP,
                    attempt.traceId());
        } catch (RuntimeException ex) {
            return fail(attempt, "INTERNAL", "internal error");
        }
        return fromOutcome(outcome, attempt);
    }

    private static McpSchema.CallToolResult fromOutcome(InvokeOutcome outcome, Attempt attempt) {
        JsonNode envelope;
        try {
            envelope = ApiJson.tree(outcome.body());
        } catch (IllegalArgumentException ex) {
            return toolError("INTERNAL", "internal error", null, attempt.directory(), attempt.traceId());
        }
        if (envelope.path("success").asBoolean(false)) {
            return ok(structured(envelope.get("data")));
        }
        String code = envelope.path("error").path("code").asText("INTERNAL");
        String message = envelope.path("error").path("message").asText("");
        String logged = envelope.path("traceId").asText("");
        String traceId = logged.isBlank() ? attempt.traceId() : logged;
        return toolError(code, message, null, attempt.directory(), traceId);
    }

    private static Object structured(JsonNode data) {
        if (data == null || data.isNull()) {
            return Map.of();
        }
        if (data.isObject()) {
            return McpJson.mapper().convertValue(data, Map.class);
        }
        return Map.of("value", McpJson.mapper().convertValue(data, Object.class));
    }

    /** {@code null} means the selector was not a string. Blank falls back to the header. */
    private static Optional<String> account(Optional<String> header, Object raw) {
        if (raw == null) {
            return header;
        }
        if (!(raw instanceof String text)) {
            return null;
        }
        if (text.isBlank()) {
            return header;
        }
        return Optional.of(text);
    }

    private static Map<String, Object> copyObject(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    static McpSchema.CallToolResult ok(Object structured) {
        Object body = structured == null ? Map.of() : structured;
        return McpSchema.CallToolResult.builder()
                .addTextContent(ApiJson.write(body))
                .structuredContent(body)
                .isError(false)
                .build();
    }

    private static McpSchema.CallToolResult fail(Attempt attempt, String code, String message) {
        return toolError(code, message, null, attempt.directory(), attempt.traceId());
    }

    private static McpSchema.CallToolResult fail(Attempt attempt, String code, String message, String field) {
        return toolError(code, message, field, attempt.directory(), attempt.traceId());
    }

    static McpSchema.CallToolResult toolError(
            String code,
            String message,
            String field,
            boolean directory,
            String traceId
    ) {
        String resolved = code == null || code.isBlank() ? "INTERNAL" : code;
        String text = message == null ? "" : message;
        if ("NOT_FOUND".equals(resolved) && (text.isBlank() || "not found".equals(text))) {
            text = McpErrors.UNKNOWN_TOOL;
        }
        String id = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        LinkedHashMap<String, String> error = new LinkedHashMap<>();
        error.put("code", resolved);
        error.put("message", text);
        error.put("hint", McpErrors.toolHint(resolved, text, directory));
        error.put("traceId", id);
        String named = field == null || field.isBlank() ? McpErrors.fieldOf(text) : field;
        if (named != null && !named.isBlank()) {
            error.put("field", named);
        }
        LinkedHashMap<String, Object> structured = new LinkedHashMap<>();
        structured.put("error", error);
        return McpSchema.CallToolResult.builder()
                .addTextContent(ApiJson.write(error))
                .structuredContent(structured)
                .isError(true)
                .build();
    }
}
