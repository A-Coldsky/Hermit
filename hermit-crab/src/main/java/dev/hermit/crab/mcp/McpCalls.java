package dev.hermit.crab.mcp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.DevTraceDraft;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.gateway.UserMessages;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.upstream.McpCallPayload;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallChannel;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.DevTraceStore;
import dev.hermit.crab.store.Origin;
import io.modelcontextprotocol.spec.McpSchema;

final class McpCalls {
    static final String NO_CONFIRM = "客户端不支持确认，拒绝执行写操作";
    static final String REJECTED = "用户拒绝执行";
    static final String TRACE_DENIED =
            "这把 API Key 不能读取这条追踪。点名了该本地规则包的 Key 可以读。全部权限 Key 只能读自己用 conch_trial_run 试跑出来的本地规则包追踪。正式包的追踪不能读。";
    static final String TRACE_ABSENT = "没有这条追踪。编号不存在。";
    /** 编号对得上一条调用记录，但那次调用没有开发追踪（正式规则包，或开发者模式关着时的调用）。 */
    static final String TRACE_OUT_OF_SCOPE =
            "这条调用不在可查范围：它没有开发追踪（正式规则包和开发者模式关着时的调用都不记）。可以在 Crab 控制台的调用记录里按这个编号查看。";
    static final String TRACE_REJECTED = "试跑被拒，没有留下追踪。";
    static final String TRACE_DELETED = "追踪已删。关掉开发者模式时会删除全部追踪。";
    static final String CONFIRM_TIMEOUT = "等待确认超时。请重新发起，并在客户端确认。";

    private final CrabRuntime runtime;
    private final McpCatalog catalog;
    private final AuthoringCalls authoring;

    McpCalls(CrabRuntime runtime, McpCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.authoring = new AuthoringCalls(runtime);
    }

    /** 调用记录、响应头和失败体共用的这一次编号。目录模式决定未知工具的 hint。 */
    /**
     * {@code traceId} 在执行前被拒时就是请求编号。{@code stored} 在真正执行（写了调用记录或追踪）后置为 true，
     * 只有这时编号才能拿去查，响应头才带 {@code X-Hermit-Trace}。
     */
    record Attempt(boolean directory, String traceId, AtomicBoolean stored) {
        Attempt(boolean directory, String traceId) {
            this(directory, traceId, new AtomicBoolean());
        }
    }

    /** 工具结果，以及这次调用有没有留下能查的记录。 */
    record Called(McpSchema.CallToolResult result, boolean traced) {
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
        return call(principal, session, headerAccount, params, traceId, true);
    }

    McpSchema.CallToolResult call(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            JsonNode params,
            String traceId,
            boolean localCaller
    ) {
        return callTraced(principal, session, headerAccount, params, traceId, localCaller).result();
    }

    Called callTraced(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            JsonNode params,
            String traceId,
            boolean localCaller
    ) {
        Attempt attempt = new Attempt(catalog.directory(principal), traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString()
                : traceId);
        McpSchema.CallToolResult result = dispatch(principal, session, headerAccount, params, localCaller, attempt);
        return new Called(result, attempt.stored().get());
    }

    private McpSchema.CallToolResult dispatch(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            JsonNode params,
            boolean localCaller,
            Attempt attempt
    ) {
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
            return readTrace(principal, arguments, attempt);
        }
        if (McpCatalog.VALIDATE.equals(name) || McpCatalog.SAVE_LOCAL.equals(name) || McpCatalog.TRIAL_RUN.equals(name)) {
            if (!localCaller) {
                return fail(attempt, "FORBIDDEN", AuthoringCalls.LOCAL_ONLY);
            }
        }
        if (McpCatalog.VALIDATE.equals(name)) {
            return authoring.validate(arguments, attempt);
        }
        if (McpCatalog.SAVE_LOCAL.equals(name)) {
            return authoring.save(principal, session, arguments, attempt);
        }
        if (McpCatalog.TRIAL_RUN.equals(name)) {
            return authoring.trial(principal, session, arguments, attempt);
        }
        if (attempt.directory()) {
            return directory(principal, session, headerAccount, name, arguments, attempt);
        }
        Optional<McpCatalog.CapabilityRef> found = catalog.find(principal, name);
        if (found.isEmpty()) {
            return missingTool(attempt, name);
        }
        return invokeDirect(principal, session, headerAccount, found.get(), arguments, attempt);
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
            default -> failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL);
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
                .orElseGet(() -> failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL));
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
            return failAsRequest(attempt, "INVALID_INPUT", Invoker.INVALID_ACCOUNT_NAME, "account");
        }
        boolean confirm = found.get().action().effectiveConfirm();
        if (confirm) {
            McpSchema.CallToolResult confirmed = confirm(session, found.get().action(), input, account, attempt);
            if (confirmed != null) {
                return confirmed;
            }
        }
        return invoke(principal, found.get(), input, account, attempt, confirm);
    }

    private McpSchema.CallToolResult invokeDirect(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            McpCatalog.CapabilityRef ref,
            Map<String, Object> arguments,
            Attempt attempt
    ) {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>(arguments);
        // 账号选择不是能力入参。校验前先拿掉，避免被当成未声明字段。
        String selector = McpCatalog.hasAccountParam(ref.action()) ? "_account" : "account";
        Object rawAccount = input.remove(selector);
        Optional<String> account = account(headerAccount, rawAccount);
        if (account == null) {
            return failAsRequest(attempt, "INVALID_INPUT", Invoker.INVALID_ACCOUNT_NAME, selector);
        }
        boolean confirm = ref.action().effectiveConfirm();
        if (confirm) {
            McpSchema.CallToolResult confirmed = confirm(session, ref.action(), input, account, attempt);
            if (confirmed != null) {
                return confirmed;
            }
        }
        return invoke(principal, ref, input, account, attempt, confirm);
    }

    /** Elicitation for a destructive call. {@code null} means the user accepted. */
    static McpSchema.CallToolResult confirm(
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
            return fail(attempt, "TIMEOUT", CONFIRM_TIMEOUT);
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
                return failAsRequest(attempt, "CONCH_DISABLED", DisabledConches.CALL_MESSAGE);
            }
        }
        // 整包在本机停用：工具不在列表里，说明原因而不是说没有这个工具。
        String switchedOff = longestConchPrefix(toolName, runtime.conchSwitchedOffNames());
        if (switchedOff != null && runtime.registry().find(switchedOff).isPresent()) {
            String live = longestConchPrefix(toolName, runtime.registry().snapshot().keySet());
            if (live == null || live.length() <= switchedOff.length()) {
                return failAsRequest(attempt, "CONCH_DISABLED", Invoker.CONCH_SWITCHED_OFF_CALL);
            }
        }
        // 装着、但当前校验不让载入的包：工具不在列表里，也不能说成没有这个工具。
        String rejected = longestConchPrefix(toolName, runtime.ruleDisabledNames());
        if (rejected != null) {
            String live = longestConchPrefix(toolName, runtime.registry().snapshot().keySet());
            if (live == null || live.length() <= rejected.length()) {
                return failAsRequest(attempt, "CONCH_DISABLED", Invoker.RULE_DISABLED_CALL);
            }
        }
        return failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL);
    }

    private McpSchema.CallToolResult readTrace(
            ApiKeyPrincipal principal,
            Map<String, Object> arguments,
            Attempt attempt
    ) {
        if (!runtime.debug().get().enabled()) {
            String id = arguments.get("traceId") instanceof String text ? text.trim() : "";
            if (runtime.deletedTrace(id)) {
                return fail(attempt, "NOT_FOUND", TRACE_DELETED);
            }
            return fail(attempt, "NOT_FOUND", AuthoringCalls.DEV_MODE_CLOSED);
        }
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
        if (trace == null) {
            if (runtime.deletedTrace(traceId)) {
                return fail(attempt, "NOT_FOUND", TRACE_DELETED);
            }
            if (runtime.rejectedTrial(traceId)) {
                return fail(attempt, "NOT_FOUND", TRACE_REJECTED);
            }
            if (runtime.callLogs().findByTraceId(traceId).isPresent()) {
                return fail(attempt, "NOT_FOUND", TRACE_OUT_OF_SCOPE);
            }
            return fail(attempt, "NOT_FOUND", TRACE_ABSENT);
        }
        if (!visible(principal, trace)) {
            return fail(attempt, "FORBIDDEN", TRACE_DENIED);
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
     * Local traces only. A named key still has to list the conch. A call recorded under another
     * API key stays hidden, except a console-channel trial, which named keys could already read.
     * A full-scope key can read the local trial it started with {@code conch_trial_run}.
     * Official traces are not local and stay hidden.
     */
    private boolean visible(ApiKeyPrincipal principal, DevTraceStore.Trace trace) {
        if (trace.origin() != Origin.LOCAL || principal == null || principal.scope() == null) {
            return false;
        }
        CallLogStore.CallDetail detail = runtime.callLogs().findByTraceId(trace.traceId()).orElse(null);
        boolean namesConch = principal.scope().conches().contains(trace.conch());
        boolean ownTrial = ownLocalTrial(principal, detail);
        if (!namesConch && !ownTrial) {
            return false;
        }
        if (detail != null && detail.call().apiKeyId() != null
                && !detail.call().apiKeyId().equals(principal.id())
                && !(namesConch && "CONSOLE".equals(detail.call().channel()))) {
            return false;
        }
        if (DevTraceStore.MCP_REFRESH_ACTION.equals(trace.action())) {
            return namesConch;
        }
        ConchDoc doc = runtime.registry().find(trace.conch(), Origin.LOCAL).orElse(null);
        Action action = doc == null ? null : doc.actions().get(trace.action());
        if (action == null) {
            return !principal.scope().readOnlyOnly();
        }
        return principal.permits(trace.conch(), trace.action(), CapabilityHttp.readOnly(action));
    }

    /** Full-scope key, and the call log is the trial that key started. */
    private static boolean ownLocalTrial(ApiKeyPrincipal principal, CallLogStore.CallDetail detail) {
        if (detail == null || principal.scope() == null || !principal.scope().grantsAllOfficial()) {
            return false;
        }
        return "CONSOLE".equals(detail.call().channel()) && principal.id().equals(detail.call().apiKeyId());
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
            Attempt attempt,
            boolean confirmed
    ) {
        InvokeOutcome outcome;
        try {
            // MCP 不传 Idempotency-Key。同键回放只在 HTTP POST。
            // 确认过的调用带上确认时那份规则包和来源：等待期间换了版本或来源就拒绝，不执行没确认过的内容。
            outcome = runtime.invoker().invoke(
                    principal,
                    ref.conch().conch(),
                    ref.action().name(),
                    input,
                    account,
                    Optional.empty(),
                    null,
                    CallChannel.MCP,
                    attempt.traceId(),
                    false,
                    confirmed ? ref.conch() : null,
                    confirmed ? ref.origin() : null,
                    null,
                    null);
        } catch (RuntimeException ex) {
            return fail(attempt, "INTERNAL", "internal error");
        }
        return fromOutcome(outcome, attempt);
    }

    private static McpSchema.CallToolResult fromOutcome(InvokeOutcome outcome, Attempt attempt) {
        if (!outcome.requestOnly()) {
            attempt.stored().set(true);
        }
        JsonNode envelope;
        try {
            envelope = ApiJson.tree(outcome.body());
        } catch (IllegalArgumentException ex) {
            return toolError("INTERNAL", "internal error", null, attempt.directory(), attempt.traceId());
        }
        if (outcome.mcpCall() != null) {
            return mcpPayload(outcome, envelope, attempt);
        }
        if (envelope.path("success").asBoolean(false)) {
            return ok(structured(envelope.get("data")));
        }
        String code = envelope.path("error").path("code").asText("INTERNAL");
        String message = envelope.path("error").path("message").asText("");
        if (outcome.requestOnly()) {
            return failAsRequest(attempt, code, message);
        }
        String logged = envelope.path("traceId").asText("");
        String traceId = logged.isBlank() ? attempt.traceId() : logged;
        return toolError(code, message, null, attempt.directory(), traceId);
    }

    private static McpSchema.CallToolResult mcpPayload(InvokeOutcome outcome, JsonNode envelope, Attempt attempt) {
        McpCallPayload payload = outcome.mcpCall();
        try {
            if (envelope.path("success").asBoolean(false) && !payload.upstreamToolError()) {
                return mcpResult(payload.content(), payload.structuredContent(), false);
            }
            LinkedHashMap<String, Object> structured = new LinkedHashMap<>();
            if (payload.copyStructured() != null) {
                structured.putAll(payload.copyStructured());
            }
            LinkedHashMap<String, Object> error = new LinkedHashMap<>();
            error.put("code", envelope.path("error").path("code").asText("UPSTREAM_ERROR"));
            error.put("message", envelope.path("error").path("message").asText(""));
            error.put("upstreamToolError", true);
            error.put("traceId", envelope.path("traceId").asText(attempt.traceId()));
            structured.put("error", error);
            return mcpResult(payload.content(), structured, true);
        } catch (RuntimeException ex) {
            return toolError("INTERNAL", "internal error", null, attempt.directory(), attempt.traceId());
        }
    }

    private static McpSchema.CallToolResult mcpResult(java.util.List<Object> content, Object structured, boolean isError) {
        com.fasterxml.jackson.databind.node.ObjectNode node = McpJson.mapper().createObjectNode();
        node.set("content", McpJson.mapper().valueToTree(content == null ? java.util.List.of() : content));
        if (structured != null) {
            node.set("structuredContent", McpJson.mapper().valueToTree(structured));
        }
        node.put("isError", isError);
        return McpJson.mapper().convertValue(node, McpSchema.CallToolResult.class);
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

    /**
     * 没有执行就失败：参数不对、开发者模式关着、确认没过、查不到追踪等。这些都没有留下记录，
     * 只给请求编号，不给查不到的追踪编号。执行过的失败走 {@link #fromOutcome}。
     */
    static McpSchema.CallToolResult fail(Attempt attempt, String code, String message) {
        return failAsRequest(attempt, code, message);
    }

    /** A trial rejected before execution has no trace. The response must not hand back an id. */
    static McpSchema.CallToolResult failWithoutTrace(Attempt attempt, String code, String message) {
        return toolError(code, message, null, attempt.directory(), null, false);
    }

    /** The id is a request number. It is not a trace and must not be opened in the call drawer. */
    static McpSchema.CallToolResult failAsRequest(Attempt attempt, String code, String message) {
        return failAsRequest(attempt, code, message, null);
    }

    static McpSchema.CallToolResult failAsRequest(Attempt attempt, String code, String message, String field) {
        String resolved = code == null || code.isBlank() ? "INTERNAL" : code;
        String english = message == null ? "" : message;
        String text = UserMessages.zh(english);
        LinkedHashMap<String, String> error = new LinkedHashMap<>();
        error.put("code", resolved);
        error.put("message", text);
        error.put("hint", McpErrors.toolHint(resolved, english, attempt.directory()));
        error.put("requestId", attempt.traceId());
        error.put("requestNote", ApiEnvelope.REQUEST_NOTE);
        String named = field == null || field.isBlank() ? McpErrors.fieldOf(english) : field;
        if (named != null && !named.isBlank()) {
            error.put("field", named);
        }
        LinkedHashMap<String, Object> structured = new LinkedHashMap<>();
        structured.put("error", error);
        structured.put("requestId", attempt.traceId());
        structured.put("requestNote", ApiEnvelope.REQUEST_NOTE);
        return McpSchema.CallToolResult.builder()
                .addTextContent(ApiJson.write(error))
                .structuredContent(structured)
                .isError(true)
                .build();
    }

    static McpSchema.CallToolResult fail(Attempt attempt, String code, String message, String field) {
        return failAsRequest(attempt, code, message, field);
    }

    static McpSchema.CallToolResult toolError(
            String code,
            String message,
            String field,
            boolean directory,
            String traceId
    ) {
        return toolError(code, message, field, directory, traceId, true);
    }

    static McpSchema.CallToolResult toolError(
            String code,
            String message,
            String field,
            boolean directory,
            String traceId,
            boolean includeTrace
    ) {
        String resolved = code == null || code.isBlank() ? "INTERNAL" : code;
        String text = message == null ? "" : message;
        if ("NOT_FOUND".equals(resolved) && (text.isBlank() || "not found".equals(text))) {
            text = McpErrors.UNKNOWN_TOOL;
        }
        LinkedHashMap<String, String> error = new LinkedHashMap<>();
        error.put("code", resolved);
        error.put("message", text);
        error.put("hint", McpErrors.toolHint(resolved, text, directory));
        if (includeTrace) {
            String id = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
            error.put("traceId", id);
        }
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
