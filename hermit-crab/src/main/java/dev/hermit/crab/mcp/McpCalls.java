package dev.hermit.crab.mcp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.CapabilityKind;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.DevTraceDraft;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.gateway.UserMessages;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.runtime.InvokeRequest;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.upstream.McpCallPayload;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallChannel;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.DevTraceStore;
import dev.hermit.crab.store.Origin;
import io.modelcontextprotocol.spec.McpSchema;

final class McpCalls {
    static final String DIRECT_MODE_HINT = "当前是直接模式，工具列表里的每个工具就是一个能力，直接调用。";
    private static final List<String> SEARCH_FIELDS = List.of("query", "conch", "group", "ungrouped");
    private static final List<String> CALL_FIELDS = List.of("name", "arguments", "account");
    /** 只读 Key 调用两个写入口，或经它们调写能力。 */
    static final String READ_ONLY_KEY = "这把 API Key 只允许调用只读能力。";
    static final String TRACE_DENIED =
            "这把 API Key 不能读取这条追踪。点名了该本地规则包的读写 Key 可以读，本机全部权限读写 Key 可读自己用 hermit_dev__trial_run 试跑出来的以及控制台触发的本地调试追踪（含授权器测试登录）。其他 API Key 和正式包的追踪不能读。";
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
        if (McpCatalog.DEV_CONCH.equals(name)) {
            return devEntry(principal, session, arguments, localCaller, attempt);
        }
        if (McpCatalog.devToolName(name)) {
            return devCall(principal, session, name, arguments, localCaller, attempt, CapabilityKind.SENSITIVE);
        }
        if (attempt.directory()) {
            return directory(principal, session, headerAccount, name, arguments, localCaller, attempt);
        }
        Optional<McpCatalog.CapabilityRef> found = catalog.find(principal, name);
        if (found.isEmpty()) {
            if (McpCatalog.directoryToolName(name)) {
                return failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL, null, DIRECT_MODE_HINT);
            }
            return missingTool(attempt, name);
        }
        return invokeDirect(principal, session, headerAccount, found.get(), arguments, attempt);
    }

    /** 内置开发者工具包（hermit_dev）的工具。直接调用和 call_capability 都走这里。 */
    /** 直接模式下开发者工具包的唯一入口：不带 tool 返回清单，带 tool 转给对应的开发者工具。 */
    private McpSchema.CallToolResult devEntry(
            ApiKeyPrincipal principal,
            McpSession session,
            Map<String, Object> arguments,
            boolean localCaller,
            Attempt attempt
    ) {
        if (principal != null && !principal.scope().devTools()) {
            return fail(attempt, "FORBIDDEN", AuthoringCalls.NEEDS_DEV_SCOPE);
        }
        for (String key : arguments.keySet()) {
            if (!"tool".equals(key) && !"arguments".equals(key)) {
                return fail(attempt, "INVALID_INPUT", "不认识的字段 " + key + "。只接受 tool 和 arguments。");
            }
        }
        Object tool = arguments.get("tool");
        if (tool == null) {
            if (!catalog.devVisible(principal)) {
                return fail(attempt, "NOT_CONFIGURED", AuthoringCalls.DEV_MODE_CLOSED);
            }
            return ok(catalog.devOverview(principal, localCaller));
        }
        if (!(tool instanceof String text) || !McpCatalog.devToolName(text)) {
            return fail(attempt, "INVALID_INPUT", "tool 不是开发者工具名。不带参数调用 hermit_dev 查看可用的工具。");
        }
        Object raw = arguments.get("arguments");
        Map<String, Object> input = copyObject(raw);
        if (raw != null && input == null) {
            return fail(attempt, "INVALID_INPUT", "arguments must be an object");
        }
        // 入口工具自己标成有破坏性，客户端按最严的一类审批。
        return devCall(principal, session, text, input == null ? Map.of() : input, localCaller, attempt,
                CapabilityKind.SENSITIVE);
    }

    /**
     * {@code entry} 是调用方用的入口对应的类别：call_capability 是 READ，call_write_capability 是 WRITE，
     * 其余（call_sensitive_capability、直接调用、hermit_dev 入口）是 SENSITIVE。
     */
    private McpSchema.CallToolResult devCall(
            ApiKeyPrincipal principal,
            McpSession session,
            String name,
            Map<String, Object> arguments,
            boolean localCaller,
            Attempt attempt,
            CapabilityKind entry
    ) {
        if (principal != null && !principal.scope().devTools()) {
            return fail(attempt, "FORBIDDEN", AuthoringCalls.NEEDS_DEV_SCOPE);
        }
        // 试跑按被试跑能力的类别判断，在 AuthoringCalls.trial 里查。
        if (!McpCatalog.TRIAL_RUN.equals(name) && !McpCatalog.devKind(name).allowedBy(entry)) {
            // 只读 Key 没有写入口：不要让它换工具，直接说明这把 Key 不能写。
            if (principal != null && principal.scope().readOnlyOnly()) {
                return fail(attempt, "FORBIDDEN", READ_ONLY_KEY);
            }
            return wrongEntry(attempt, name, McpCatalog.devKind(name));
        }
        if (McpCatalog.DEBUG_TRACE.equals(name)) {
            return readTrace(principal, arguments, attempt, localCaller);
        }
        if (!McpCatalog.DEBUG_TRACE.equals(name)) {
            if (!localCaller) {
                return fail(attempt, "FORBIDDEN", AuthoringCalls.LOCAL_ONLY);
            }
        }
        if (McpCatalog.DEBUG_TRACE_LIST.equals(name)) {
            return listTraces(principal, arguments, attempt, localCaller);
        }
        if (McpCatalog.GUIDE.equals(name)) {
            return authoring.guide(arguments, attempt);
        }
        if (McpCatalog.READ_YAML.equals(name)) {
            return authoring.readYaml(principal, arguments, attempt);
        }
        if (McpCatalog.AUTH_LIST.equals(name)) {
            return authoring.authList(principal, arguments, attempt);
        }
        if (McpCatalog.CAPTURE_LIST.equals(name)) {
            return authoring.captureList(principal, arguments, attempt);
        }
        if (McpCatalog.CAPTURE_GET.equals(name)) {
            return authoring.captureGet(principal, arguments, attempt);
        }
        if (McpCatalog.VALIDATE.equals(name)) {
            return authoring.validate(arguments, attempt);
        }
        if (McpCatalog.SAVE_LOCAL.equals(name)) {
            return authoring.save(principal, session, arguments, attempt);
        }
        if (McpCatalog.TEST_LOGIN.equals(name)) {
            return authoring.testLogin(principal, arguments, attempt);
        }
        if (McpCatalog.MCP_REFRESH.equals(name)) {
            return authoring.mcpRefresh(principal, arguments, attempt);
        }
        if (McpCatalog.MCP_CONFIGURE.equals(name)) {
            return authoring.mcpConfigure(principal, arguments, attempt);
        }
        if (McpCatalog.DRY_RUN_RESPONSE.equals(name)) {
            return authoring.dryRunResponse(principal, arguments, attempt);
        }
        if (McpCatalog.STAGE_TEXT_FILE.equals(name)) {
            return authoring.stageTextFile(principal, arguments, attempt);
        }
        if (McpCatalog.TRIAL_RUN.equals(name)) {
            return authoring.trial(principal, arguments, attempt, entry);
        }
        return failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL);
    }

    private McpSchema.CallToolResult directory(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            String name,
            Map<String, Object> arguments,
            boolean localCaller,
            Attempt attempt
    ) {
        return switch (name) {
            case McpCatalog.LIST_SYSTEMS -> ok(catalog.listSystems(principal, localCaller));
            case McpCatalog.SEARCH -> search(principal, arguments, localCaller, attempt);
            case McpCatalog.DESCRIBE -> describe(principal, arguments, localCaller, attempt);
            case McpCatalog.CALL -> callCapability(
                    principal, session, headerAccount, arguments, localCaller, attempt, CapabilityKind.READ);
            case McpCatalog.CALL_WRITE -> callCapability(
                    principal, session, headerAccount, arguments, localCaller, attempt, CapabilityKind.WRITE);
            case McpCatalog.CALL_SENSITIVE -> callCapability(
                    principal, session, headerAccount, arguments, localCaller, attempt, CapabilityKind.SENSITIVE);
            default -> catalog.find(principal, name).isPresent()
                    // 升级前的提示词会按「规则包__能力」直接调用。工具列表里只有目录工具，要告诉它怎么调。
                    ? failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL, null,
                            "能力不直接出现在工具列表里。请用 "
                                    + McpCatalog.callWith(catalog.find(principal, name).get().action().kind())
                                    + "(name=\"" + name + "\", arguments={…}) 调用。")
                    : missingTool(attempt, name);
        };
    }

    /** 用更宽松的入口调更严的能力：不执行，告诉调用方该用哪个入口。 */
    static McpSchema.CallToolResult wrongEntry(Attempt attempt, String name, CapabilityKind kind) {
        return failAsRequest(attempt, "INVALID_INPUT", wrongEntryMessage(kind), "name",
                "请改用 " + McpCatalog.callWith(kind) + "(name=\"" + name + "\", arguments={…})。"
                        + (kind == CapabilityKind.SENSITIVE ? "调用前先向用户说明要做什么和参数，取得同意。" : ""));
    }

    static String wrongEntryMessage(CapabilityKind kind) {
        return kind == CapabilityKind.SENSITIVE
                ? "这个能力是敏感操作，没有执行。要用 " + McpCatalog.CALL_SENSITIVE + " 调用。"
                : "这个能力会修改数据，没有执行。要用 " + McpCatalog.CALL_WRITE + " 调用。";
    }

    /** 目录工具只认声明过的字段。多出来的字段悄悄丢掉会让调用方以为它生效了。 */
    private static McpSchema.CallToolResult unknownField(
            Map<String, Object> arguments, List<String> allowed, String advice, Attempt attempt) {
        for (String key : arguments.keySet()) {
            if (!allowed.contains(key)) {
                return fail(attempt, "INVALID_INPUT", "不认识的字段 " + key + "。" + advice, key);
            }
        }
        return null;
    }

    private McpSchema.CallToolResult search(
            ApiKeyPrincipal principal, Map<String, Object> arguments, boolean localCaller, Attempt attempt) {
        McpSchema.CallToolResult unknown = unknownField(
                arguments, SEARCH_FIELDS, "只接受 query、conch、group、ungrouped。", attempt);
        if (unknown != null) {
            return unknown;
        }
        Object rawQuery = arguments.get("query");
        String query = null;
        if (rawQuery != null) {
            if (!(rawQuery instanceof String text)) {
                return fail(attempt, "INVALID_INPUT", "query 必须是字符串。", "query");
            }
            if (!text.isBlank()) {
                query = text;
            }
        }
        Object rawConch = arguments.get("conch");
        if (rawConch != null && !(rawConch instanceof String)) {
            return fail(attempt, "INVALID_INPUT", "conch 必须是字符串。", "conch");
        }
        String conch = rawConch instanceof String name && !name.isBlank() ? name : null;
        Object rawGroup = arguments.get("group");
        if (rawGroup != null && !(rawGroup instanceof String)) {
            return fail(attempt, "INVALID_INPUT", "group 必须是字符串。", "group");
        }
        String group = rawGroup instanceof String path && !path.isBlank() ? path : null;
        Object rawUngrouped = arguments.get("ungrouped");
        boolean ungrouped = false;
        if (rawUngrouped != null) {
            if (!(rawUngrouped instanceof Boolean flag)) {
                return fail(attempt, "INVALID_INPUT", "ungrouped 必须是布尔值。", "ungrouped");
            }
            ungrouped = flag;
        }
        if (group != null && ungrouped) {
            return fail(attempt, "INVALID_INPUT", "group 和 ungrouped 不能同时使用。", "group");
        }
        if (query == null && group == null && !ungrouped && conch == null) {
            return fail(attempt, "INVALID_INPUT", "缺少 query。传 query 搜索，或传 conch 列出该规则包的能力。", "query");
        }
        return ok(catalog.search(principal, query, conch, group, ungrouped, localCaller));
    }

    private McpSchema.CallToolResult describe(
            ApiKeyPrincipal principal, Map<String, Object> arguments, boolean localCaller, Attempt attempt) {
        McpSchema.CallToolResult unknown = unknownField(arguments, List.of("name"), "只接受 name。", attempt);
        if (unknown != null) {
            return unknown;
        }
        Object name = arguments.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return fail(attempt, "INVALID_INPUT", "name is required");
        }
        return catalog.describe(principal, text, localCaller)
                .map(McpCalls::ok)
                .orElseGet(() -> failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL));
    }

    private McpSchema.CallToolResult callCapability(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            Map<String, Object> arguments,
            boolean localCaller,
            Attempt attempt,
            CapabilityKind entry
    ) {
        // 两个写入口不出现在只读 Key 的工具列表里；直接按名字调也拒绝。
        if (entry != CapabilityKind.READ && principal != null && principal.scope().readOnlyOnly()) {
            return fail(attempt, "FORBIDDEN", READ_ONLY_KEY);
        }
        // 先于开发者工具分流：否则 call_capability(name=hermit_dev__read_yaml, conch=…) 仍会丢参数。
        McpSchema.CallToolResult unknown = unknownField(
                arguments, CALL_FIELDS, "只接受 name、arguments、account；能力参数要放进 arguments。", attempt);
        if (unknown != null) {
            return unknown;
        }
        Object name = arguments.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return fail(attempt, "INVALID_INPUT", "name is required");
        }
        if (McpCatalog.devToolName(text)) {
            Object raw = arguments.get("arguments");
            Map<String, Object> devInput = copyObject(raw);
            if (raw != null && devInput == null) {
                return fail(attempt, "INVALID_INPUT", "arguments must be an object");
            }
            return devCall(
                    principal, session, text, devInput == null ? Map.of() : devInput, localCaller, attempt, entry);
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
        CapabilityKind kind = found.get().action().kind();
        if (!kind.allowedBy(entry)) {
            if (principal != null && principal.scope().readOnlyOnly()) {
                return fail(attempt, "FORBIDDEN", READ_ONLY_KEY);
            }
            return wrongEntry(attempt, text, kind);
        }
        return invoke(principal, found.get(), input, account, attempt);
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
        return invoke(principal, ref, input, account, attempt);
    }

    /**
     * A tool name is {@code conch__action}. A tombstoned conch that is not loaded is
     * {@code CONCH_DISABLED}. A loaded conch, including one Reef has stopped distributing,
     * is called through {@code Invoker} and is not handled here.
     */
    private McpSchema.CallToolResult missingTool(Attempt attempt, String toolName) {
        String disabled = longestConchPrefix(toolName, callableNames(runtime.disabledConches().names()));
        if (disabled != null && runtime.registry().find(disabled).isEmpty()) {
            String live = longestConchPrefix(toolName, runtime.registry().snapshot().keySet());
            if (live == null || live.length() <= disabled.length()) {
                return failAsRequest(attempt, "CONCH_DISABLED", DisabledConches.CALL_MESSAGE);
            }
        }
        // 整包在本机停用：工具不在列表里，说明原因而不是说没有这个工具。
        String switchedOff = longestConchPrefix(toolName, callableNames(runtime.conchSwitchedOffNames()));
        if (switchedOff != null && runtime.registry().find(switchedOff).isPresent()) {
            String live = longestConchPrefix(toolName, runtime.registry().snapshot().keySet());
            if (live == null || live.length() <= switchedOff.length()) {
                return failAsRequest(attempt, "CONCH_DISABLED", Invoker.CONCH_SWITCHED_OFF_CALL);
            }
        }
        // 装着、但当前校验不让载入的包：工具不在列表里，也不能说成没有这个工具。
        String rejected = longestConchPrefix(toolName, callableNames(runtime.ruleDisabledNames()));
        if (rejected != null) {
            String live = longestConchPrefix(toolName, runtime.registry().snapshot().keySet());
            if (live == null || live.length() <= rejected.length()) {
                return failAsRequest(attempt, "CONCH_DISABLED", Invoker.RULE_DISABLED_CALL);
            }
        }
        return failAsRequest(attempt, "NOT_FOUND", McpErrors.UNKNOWN_TOOL);
    }

    private java.util.Set<String> callableNames(java.util.Set<String> names) {
        if (names.isEmpty()) {
            return names;
        }
        java.util.LinkedHashSet<String> filtered = new java.util.LinkedHashSet<>();
        for (String name : names) {
            if (!runtime.isKnownAuthenticator(name)) {
                filtered.add(name);
            }
        }
        return filtered;
    }

    private McpSchema.CallToolResult readTrace(
            ApiKeyPrincipal principal,
            Map<String, Object> arguments,
            Attempt attempt,
            boolean localCaller
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
        if (!visible(principal, trace.origin(), trace.conch(), trace.action(), trace.traceId(), localCaller)) {
            return fail(attempt, "FORBIDDEN", TRACE_DENIED);
        }
        Object devTrace;
        try {
            devTrace = ApiJson.objectMap(trace.body().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            return fail(attempt, "NOT_FOUND", "not found");
        }
        String note = DevTraceDraft.UNTRUSTED_NOTE;
        if (hasCredentialBearingStep(devTrace)) {
            note = note + "（含 credentialBearing=true 步骤：携带凭证或 credential: true 脚本派生签名的请求，其响应正文与紧邻提取的变量摘要在追踪中按防泄密策略显示为 ***，不影响非敏感字段的正式返回结果。）";
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", trace.traceId());
        body.put("conch", trace.conch());
        body.put("account", trace.account());
        body.put("action", trace.action());
        body.put("note", note);
        body.put("untrusted", true);
        body.put("createdAt", trace.createdAt() == null ? null : trace.createdAt().toString());
        body.put("expiresAt", trace.expiresAt() == null ? null : trace.expiresAt().toString());
        body.put("devTrace", devTrace);
        return ok(body);
    }

    private static boolean hasCredentialBearingStep(Object devTrace) {
        if (!(devTrace instanceof Map<?, ?> map)) {
            return false;
        }
        if (!(map.get("steps") instanceof java.util.List<?> steps)) {
            return false;
        }
        for (Object item : steps) {
            if (item instanceof Map<?, ?> step && Boolean.TRUE.equals(step.get("credentialBearing"))) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> TRACE_LIST_FIELDS = Set.of("conch", "action", "limit");

    private McpSchema.CallToolResult listTraces(
            ApiKeyPrincipal principal,
            Map<String, Object> arguments,
            Attempt attempt,
            boolean localCaller
    ) {
        if (!runtime.debug().get().enabled()) {
            return fail(attempt, "NOT_FOUND", AuthoringCalls.DEV_MODE_CLOSED);
        }
        for (String key : arguments.keySet()) {
            if (!TRACE_LIST_FIELDS.contains(key)) {
                return failAsRequest(attempt, "INVALID_INPUT", "未知字段 " + key + "。");
            }
        }
        String conch = null;
        if (arguments.containsKey("conch") && arguments.get("conch") != null) {
            if (!(arguments.get("conch") instanceof String text)) {
                return failAsRequest(attempt, "INVALID_INPUT", "conch 必须是字符串。", "conch");
            }
            if (!text.isBlank()) {
                conch = text.trim();
            }
        }
        String action = null;
        if (arguments.containsKey("action") && arguments.get("action") != null) {
            if (!(arguments.get("action") instanceof String text)) {
                return failAsRequest(attempt, "INVALID_INPUT", "action 必须是字符串。", "action");
            }
            if (!text.isBlank()) {
                action = text.trim();
            }
        }
        int limit = 10;
        if (arguments.containsKey("limit") && arguments.get("limit") != null) {
            Object rawLimit = arguments.get("limit");
            if (!(rawLimit instanceof Number number)
                    || rawLimit instanceof Double
                    || rawLimit instanceof Float) {
                return failAsRequest(attempt, "INVALID_INPUT", "limit 必须是 1 到 30 之间的整数。", "limit");
            }
            int parsed = number.intValue();
            if (parsed < 1 || parsed > 30) {
                return failAsRequest(attempt, "INVALID_INPUT", "limit 必须是 1 到 30 之间的整数。", "limit");
            }
            limit = parsed;
        }
        java.util.List<DevTraceStore.TraceSummary> candidates =
                runtime.devTraces().listRecent(conch, action, DevTraceStore.MAX_LIST_LIMIT);
        java.util.ArrayList<Map<String, Object>> traces = new java.util.ArrayList<>();
        for (DevTraceStore.TraceSummary row : candidates) {
            if (!visible(principal, row.origin(), row.conch(), row.action(), row.traceId(), localCaller)) {
                continue;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("traceId", row.traceId());
            item.put("conch", row.conch());
            item.put("account", row.account());
            item.put("action", row.action());
            item.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
            item.put("expiresAt", row.expiresAt() == null ? null : row.expiresAt().toString());
            traces.add(item);
            if (traces.size() >= limit) {
                break;
            }
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("traces", traces);
        body.put("total", traces.size());
        body.put("note", traces.isEmpty()
                ? "当前没有可见的本机开发追踪。请在控制台执行测试登录/试跑，或调用 hermit_dev__trial_run 后再查。"
                : "调用 hermit_dev__debug_trace(traceId=...) 查看完整脱敏步骤详情。");
        return ok(body);
    }

    /**
     * Local traces only. A call recorded under another API key always stays hidden.
     * A named key that lists the local conch can read console/CLI traces within its permission scope.
     * A full-scope key can read its own {@code hermit_dev__trial_run} traces, and when connecting locally
     * without read-only restriction in developer mode, can also read console/CLI local traces
     * ({@code test_login}, {@code login_input}, {@code mcp_refresh}, and console trials where {@code apiKeyId == null}).
     * Official traces are never stored and stay hidden.
     */
    private boolean visible(
            ApiKeyPrincipal principal,
            Origin traceOrigin,
            String conch,
            String actionName,
            String traceId,
            boolean localCaller
    ) {
        if (traceOrigin != Origin.LOCAL || principal == null || principal.scope() == null) {
            return false;
        }
        CallLogStore.CallDetail detail = runtime.callLogs().findByTraceId(traceId).orElse(null);
        if (detail != null && detail.call().apiKeyId() != null
                && !detail.call().apiKeyId().equals(principal.id())) {
            return false;
        }
        boolean fullPackageReadWrite = !principal.scope().readOnlyOnly()
                && principal.scope().groups().isEmpty()
                && principal.scope().actions().isEmpty();
        boolean namesConch = principal.scope().conches().contains(conch);
        boolean ownTrial = ownLocalTrial(principal, detail);
        boolean localConsoleTrace = localCaller
                && principal.scope().grantsAllOfficial()
                && fullPackageReadWrite
                && (detail == null || detail.call().apiKeyId() == null);
        if (!namesConch && !ownTrial && !localConsoleTrace) {
            return false;
        }
        if (DevTraceStore.MCP_REFRESH_ACTION.equals(actionName)
                || DevTraceStore.TEST_LOGIN_ACTION.equals(actionName)
                || DevTraceStore.LOGIN_INPUT_ACTION.equals(actionName)) {
            return (namesConch || localConsoleTrace) && fullPackageReadWrite;
        }
        ConchDoc doc = runtime.registry().find(conch, Origin.LOCAL).orElse(null);
        Action action = doc == null ? null : doc.actions().get(actionName);
        if (action == null) {
            return fullPackageReadWrite;
        }
        if (localConsoleTrace) {
            return true;
        }
        return principal.permits(conch, actionName, action.group(), CapabilityHttp.readOnly(action));
    }

    /** Full-scope key, and the call log is the trial that key started. */
    private static boolean ownLocalTrial(ApiKeyPrincipal principal, CallLogStore.CallDetail detail) {
        if (detail == null || principal.scope() == null || !principal.scope().grantsAllOfficial()) {
            return false;
        }
        String channel = detail.call().channel();
        return ("CONSOLE".equals(channel) || "MCP".equals(channel))
                && principal.id().equals(detail.call().apiKeyId());
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
            // 每次都带上按类别放行时看到的那份规则包和来源：之后换了版本或来源就拒绝（审计 A01），
            // 不会按只读放行、执行的却是写操作。
            outcome = runtime.invoker().invoke(
                    InvokeRequest.of(principal, ref.conch().conch(), ref.action().name(), input, attempt.traceId())
                            .account(account)
                            .channel(CallChannel.MCP)
                            .confirmedAgainst(ref.conch(), ref.origin()));
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
        return failAsRequest(attempt, code, message, field, null);
    }

    /** {@code hint} 非空时替换按错误码推出的默认提示。 */
    static McpSchema.CallToolResult failAsRequest(
            Attempt attempt, String code, String message, String field, String hint) {
        String resolved = code == null || code.isBlank() ? "INTERNAL" : code;
        String english = message == null ? "" : message;
        String text = UserMessages.zh(english);
        LinkedHashMap<String, String> error = new LinkedHashMap<>();
        error.put("code", resolved);
        error.put("message", text);
        error.put("hint", hint != null ? hint : McpErrors.toolHint(resolved, english, attempt.directory()));
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
