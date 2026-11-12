package dev.hermit.crab.upstream;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.mcp.McpLimits;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.crab.engine.AccountContext;
import dev.hermit.crab.engine.ConchExecutor;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.InputPreparer;
import dev.hermit.crab.engine.InvalidInput;
import dev.hermit.crab.engine.OutputMask;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.StepLog;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.DevTraceStore;
import dev.hermit.crab.store.Origin;

/**
 * {@code driver: mcp} 的执行器。权限、停用、审计和幂等仍在 {@code Invoker}。
 * 这里只做入参、主机、会话和 {@code tools/call}。
 */
public final class McpForwarder implements ConchExecutor, AutoCloseable {

    public static final String OAUTH_NOT_CONNECTED = "尚未连接上游。请到控制台连接上游。";
    public static final String HOST_DENIED = "上游主机不在允许列表中。";
    public static final String ADDRESS_DENIED = "上游地址不可用。";
    public static final String UPSTREAM_TIMEOUT = "上游超时";
    public static final String DEADLINE = "执行超时";
    public static final String TOO_LARGE = "上游响应过大";
    public static final String AUTH_REJECTED = "上游拒绝了凭证，请核对凭证。";
    public static final String UNKNOWN_TOOL = "上游没有这个工具。";
    public static final String BAD_ARGUMENTS = "上游拒绝了参数。";
    public static final String SESSION_LOST = "上游会话失效。";
    public static final String CONNECT_FAILED = "上游连接失败。";
    public static final String BAD_PROTOCOL = "上游协议版本不受支持。";
    public static final String HTTP_STATUS = "上游返回 HTTP ";
    public static final String TOOL_FAILED = "上游工具返回失败。";
    public static final String INCOMPLETE = "上游响应不完整。";

    private static final Pattern UNKNOWN_TOOL_TEXT = Pattern.compile("(?i)^Tool\\s+\\S+\\s+not found\\s*$");
    private static final String INPUT_VALIDATION = "Input validation error:";
    private static final int MESSAGE_LIMIT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpUpstreamSessions sessions;
    private final McpOAuth oauth;
    private final KnownSecrets knownSecrets;
    private final Duration capabilityTimeout;
    private final TemplateEngine templates = new TemplateEngine();
    private final InputPreparer inputs = new InputPreparer();
    private volatile DevTraceStore traces;

    public McpForwarder(SessionManager accounts, KnownSecrets knownSecrets, Duration capabilityTimeout, int concurrency) {
        this(accounts, knownSecrets, capabilityTimeout, concurrency, null, null);
    }

    public McpForwarder(
            SessionManager accounts,
            KnownSecrets knownSecrets,
            Duration capabilityTimeout,
            int concurrency,
            CredentialVault vault,
            Clock clock
    ) {
        if (concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("mcp concurrency must be 1..32");
        }
        this.knownSecrets = knownSecrets == null ? KnownSecrets.empty() : knownSecrets;
        this.capabilityTimeout = capabilityTimeout;
        this.sessions = new McpUpstreamSessions(new McpUpstreamClient(), accounts, concurrency);
        this.oauth = new McpOAuth(vault, accounts, sessions::discard, this.knownSecrets, clock);
    }

    /** 账号已删除：丢掉上游会话、并发通道和 OAuth 刷新锁。 */
    public void forgetAccount(SessionKey key) {
        sessions.forget(key);
        oauth.forget(key.origin(), key.conch(), key.account());
    }

    public McpOAuth oauth() {
        return oauth;
    }

    /** 调试追踪。不放进构造器，避免和上游 OAuth 的构造改动缠在一起。 */
    public void bindDevTraces(DevTraceStore traces) {
        this.traces = traces;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, ConchDoc authorized) {
        long started = System.nanoTime();
        long deadline = started + capabilityTimeout.toNanos();
        ConchDoc doc = authorized;
        if (doc == null || doc.upstream() == null || !"mcp".equals(doc.driver())) {
            return finish(request, doc, started, false, null, ErrorCode.INTERNAL, "内部错误", null, List.of());
        }
        Action action = doc.actions().get(request.action());
        if (action == null || action.tool() == null) {
            return finish(request, doc, started, false, null, ErrorCode.RULE_MISMATCH, "能力没有上游工具。", null, List.of());
        }
        if (oauthPackage(doc) && !oauthConnected(request)) {
            return finish(request, doc, started, false, null, ErrorCode.NOT_CONFIGURED, OAUTH_NOT_CONNECTED, null, List.of());
        }
        Map<String, Object> arguments;
        try {
            arguments = inputs.prepare(action, request.rawInput());
        } catch (InvalidInput ex) {
            return finish(request, doc, started, false, null, ErrorCode.INVALID_INPUT, ex.getMessage(), null, List.of());
        }
        Prepared prepared = prepareUpstream(request, doc, started);
        if (prepared.denied() != null) {
            return prepared.denied();
        }
        SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
        McpUpstreamSessions.Permit permit;
        try {
            permit = sessions.acquire(key, deadline);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return finish(request, doc, started, false, null, ErrorCode.TIMEOUT, DEADLINE, null, List.of());
        }
        if (permit == null) {
            return finish(request, doc, started, false, null, ErrorCode.TIMEOUT, DEADLINE, null, List.of());
        }
        try {
            TokenGate gate = freshToken(request, doc, prepared.headers(), deadline, started);
            if (gate.denied() != null) {
                return gate.denied();
            }
            Map<String, String> headers = gate.headers();
            Long timeoutMs = doc.upstream().timeoutMs() == null ? null : doc.upstream().timeoutMs().longValue();
            McpUpstreamSessions.CallResult result = sessions.call(
                    key,
                    doc,
                    material(headers),
                    headers,
                    action.tool().name(),
                    arguments,
                    deadline,
                    timeoutMs,
                    prepared.allowLoopback(),
                    sink(request));
            if (oauthPackage(doc) && unauthorized(result)) {
                TokenGate again = refreshedToken(request, doc, headers, deadline, started);
                if (again.denied() != null) {
                    return again.denied();
                }
                headers = again.headers();
                result = sessions.call(
                        key,
                        doc,
                        material(headers),
                        headers,
                        action.tool().name(),
                        arguments,
                        deadline,
                        timeoutMs,
                        prepared.allowLoopback(),
                        sink(request));
                if (unauthorized(result)) {
                    oauth.clear(request.origin(), request.conch(), request.account());
                    return finish(request, doc, started, false, null, ErrorCode.AUTH_FAILED, McpOAuth.REJECTED, null, List.of());
                }
            }
            return map(request, doc, action.tool().name(), started, result);
        } finally {
            permit.close();
        }
    }

    /**
     * 分页拉取 {@code tools/list}。失败时 {@link ListOutcome#failure()} 已是脱敏后的执行结果。
     * 成功不写调用日志。调试开关和本地包的判断在追踪存储里。
     */
    ListOutcome listTools(ExecutionRequest request, ConchDoc doc) {
        long started = System.nanoTime();
        long deadline = started + capabilityTimeout.toNanos();
        if (doc == null || doc.upstream() == null || !"mcp".equals(doc.driver())) {
            return ListOutcome.failed(finish(request, doc, started, false, null, ErrorCode.INTERNAL, "内部错误", null, List.of()));
        }
        if (oauthPackage(doc) && !oauthConnected(request)) {
            return ListOutcome.failed(finish(
                    request, doc, started, false, null, ErrorCode.NOT_CONFIGURED, OAUTH_NOT_CONNECTED, null, List.of()));
        }
        Prepared prepared = prepareUpstream(request, doc, started);
        if (prepared.denied() != null) {
            return ListOutcome.failed(prepared.denied());
        }
        SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
        McpUpstreamSessions.Permit permit;
        try {
            permit = sessions.acquire(key, deadline);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ListOutcome.failed(finish(request, doc, started, false, null, ErrorCode.TIMEOUT, DEADLINE, null, List.of()));
        }
        if (permit == null) {
            return ListOutcome.failed(finish(request, doc, started, false, null, ErrorCode.TIMEOUT, DEADLINE, null, List.of()));
        }
        try {
            TokenGate gate = freshToken(request, doc, prepared.headers(), deadline, started);
            if (gate.denied() != null) {
                return ListOutcome.failed(gate.denied());
            }
            Map<String, String> headers = gate.headers();
            Long timeoutMs = doc.upstream().timeoutMs() == null ? null : doc.upstream().timeoutMs().longValue();
            McpUpstreamSessions.ListResult listed = listOnce(
                    key, doc, headers, deadline, timeoutMs, prepared.allowLoopback(), sink(request));
            if (oauthPackage(doc) && listed.failure() != null && unauthorized(listed.failure())) {
                TokenGate again = refreshedToken(request, doc, headers, deadline, started);
                if (again.denied() != null) {
                    return ListOutcome.failed(again.denied());
                }
                headers = again.headers();
                listed = listOnce(key, doc, headers, deadline, timeoutMs, prepared.allowLoopback(), sink(request));
                if (listed.failure() != null && unauthorized(listed.failure())) {
                    oauth.clear(request.origin(), request.conch(), request.account());
                    return ListOutcome.failed(finish(
                            request, doc, started, false, null, ErrorCode.AUTH_FAILED, McpOAuth.REJECTED, null, List.of()));
                }
            }
            if (listed.limitMessage() != null) {
                return ListOutcome.failed(finish(
                        request, doc, started, false, null, ErrorCode.UPSTREAM_ERROR, listed.limitMessage(), null, List.of()));
            }
            if (listed.failure() != null) {
                return ListOutcome.failed(map(request, doc, "tools/list", started, listed.failure()));
            }
            if (listed.handle() == null || listed.handle().protocol == null || listed.handle().protocol.isBlank()) {
                return ListOutcome.failed(finish(
                        request, doc, started, false, null, ErrorCode.UPSTREAM_ERROR, BAD_PROTOCOL, null, List.of()));
            }
            return ListOutcome.ready(
                    listed.tools(),
                    listed.handle().protocol,
                    listed.handle().serverName,
                    listed.handle().serverVersion);
        } finally {
            permit.close();
        }
    }

    @Override
    public void close() {
        sessions.close();
        oauth.close();
    }

    private McpUpstreamSessions.ListResult listOnce(
            SessionKey key,
            ConchDoc doc,
            Map<String, String> headers,
            long deadline,
            Long timeoutMs,
            boolean allowLoopback,
            McpUpstreamSessions.ExchangeSink sink
    ) {
        return sessions.list(key, doc, material(headers), headers, deadline, timeoutMs, allowLoopback, sink);
    }

    private static boolean oauthPackage(ConchDoc doc) {
        return doc.upstream().auth() != null && doc.upstream().auth().oauth();
    }

    private boolean oauthConnected(ExecutionRequest request) {
        return oauth.peek(
                request.origin(), request.conch(), request.account(), credentials(request.accountContext())) != null;
    }

    private TokenGate freshToken(
            ExecutionRequest request,
            ConchDoc doc,
            Map<String, String> headers,
            long deadline,
            long started
    ) {
        if (!oauthPackage(doc)) {
            return TokenGate.ready(headers);
        }
        McpOAuth.Attempt fresh = oauth.ensureFresh(
                request.origin(),
                request.conch(),
                request.account(),
                doc,
                credentials(request.accountContext()),
                deadline);
        if (!fresh.ok()) {
            return TokenGate.deny(finish(request, doc, started, false, null, fresh.code(), fresh.message(), null, List.of()));
        }
        return TokenGate.ready(authorize(headers, fresh.token()));
    }

    private TokenGate refreshedToken(
            ExecutionRequest request,
            ConchDoc doc,
            Map<String, String> headers,
            long deadline,
            long started
    ) {
        McpOAuth.Attempt again = oauth.refreshAfterUnauthorized(
                request.origin(),
                request.conch(),
                request.account(),
                doc,
                credentials(request.accountContext()),
                bearerAccess(headers),
                deadline);
        if (!again.ok()) {
            return TokenGate.deny(finish(request, doc, started, false, null, again.code(), again.message(), null, List.of()));
        }
        return TokenGate.ready(authorize(headers, again.token()));
    }

    private Prepared prepareUpstream(ExecutionRequest request, ConchDoc doc, long started) {
        McpAddresses.ParseResult parsed = McpAddresses.parse(doc.upstream().url());
        if (!parsed.ok() || !McpAddresses.hostAllowed(parsed.url().host(), doc.upstream().allowedHosts())) {
            return Prepared.deny(finish(request, doc, started, false, null, ErrorCode.NOT_CONFIGURED, HOST_DENIED, null, List.of()));
        }
        boolean allowLoopback = McpAddressGuard.packageAllowsLoopback(parsed.url().host());
        if (literal(parsed.url().host()) && rejectedLiteral(parsed.url().host(), allowLoopback)) {
            return Prepared.deny(finish(request, doc, started, false, null, ErrorCode.UPSTREAM_ERROR, ADDRESS_DENIED, null, List.of()));
        }
        try {
            return Prepared.ready(renderHeaders(doc, request.accountContext()), allowLoopback);
        } catch (TemplateException ex) {
            return Prepared.deny(finish(request, doc, started, false, null, ErrorCode.NOT_CONFIGURED, "上游请求头无法渲染。", null, List.of()));
        }
    }

    private McpUpstreamSessions.ExchangeSink sink(ExecutionRequest request) {
        return (method, id, httpStatus, durationMs, params, result, error, sessionId) ->
                record(request, method, id, httpStatus, durationMs, params, result, error, sessionId);
    }

    private void record(
            ExecutionRequest request,
            String method,
            Object id,
            int httpStatus,
            long durationMs,
            Object params,
            Object result,
            Object error,
            String sessionId
    ) {
        DevTraceStore store = traces;
        if (store == null || request.origin() != Origin.LOCAL) {
            return;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            knownSecrets.add(sessionId);
        }
        LinkedHashSet<String> secrets = new LinkedHashSet<>();
        if (request.accountContext() != null && request.accountContext().credentials() != null) {
            for (String value : request.accountContext().credentials().values()) {
                if (value != null) {
                    secrets.add(value);
                }
            }
        }
        secrets.addAll(knownSecrets.copy());
        if (sessionId != null && !sessionId.isBlank()) {
            secrets.add(sessionId);
        }
        try {
            store.recordJsonRpc(new DevTraceStore.JsonRpcTrace(
                    request.traceId(),
                    Origin.LOCAL,
                    request.conch(),
                    request.account(),
                    request.action(),
                    method,
                    id,
                    httpStatus,
                    durationMs,
                    params,
                    "initialize".equals(method) ? dropInstructions(result) : result,
                    error,
                    List.copyOf(secrets)));
        } catch (RuntimeException ex) {
            // 存储自己会记下失败。这里不再打断转发。
        }
    }

    private static Object dropInstructions(Object result) {
        if (!(result instanceof Map<?, ?> map) || !map.containsKey("instructions")) {
            return result;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!"instructions".equals(String.valueOf(entry.getKey()))) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private ExecutionResult map(
            ExecutionRequest request,
            ConchDoc doc,
            String tool,
            long started,
            McpUpstreamSessions.CallResult result
    ) {
        if (result.handle() != null && result.handle().sessionId != null) {
            knownSecrets.add(result.handle().sessionId);
        }
        if (result.error() != null) {
            return mapTransport(request, doc, tool, started, result);
        }
        JsonNode message = result.message();
        if (message == null || !message.isObject()) {
            return logged(request, doc, tool, started, 200, false, null, ErrorCode.UPSTREAM_ERROR, INCOMPLETE, null);
        }
        if (message.has("error")) {
            return mapRpc(request, doc, tool, started, message);
        }
        JsonNode body = message.get("result");
        if (body == null || !body.isObject()) {
            return logged(request, doc, tool, started, 200, false, null, ErrorCode.UPSTREAM_ERROR, INCOMPLETE, null);
        }
        List<Object> content = content(body.get("content"));
        Object structured = body.has("structuredContent") && !body.get("structuredContent").isNull()
                ? JSON.convertValue(body.get("structuredContent"), Object.class)
                : null;
        boolean toolError = body.path("isError").asBoolean(false);
        if (!toolError) {
            McpCallPayload payload = new McpCallPayload(content, structured, false);
            return logged(request, doc, tool, started, 200, true, payload, null, null, null);
        }
        String text = firstText(content);
        if (text.startsWith(INPUT_VALIDATION)) {
            return logged(request, doc, tool, started, 200, false, null, ErrorCode.INVALID_INPUT, clip(text), null);
        }
        McpCallPayload payload = new McpCallPayload(content, structured, true);
        String messageText = text.isBlank() ? TOOL_FAILED : clip(text);
        return logged(request, doc, tool, started, 200, false, payload, ErrorCode.UPSTREAM_ERROR, messageText, "mcp_tool_error");
    }

    private ExecutionResult mapRpc(
            ExecutionRequest request,
            ConchDoc doc,
            String tool,
            long started,
            JsonNode message
    ) {
        JsonNode error = message.get("error");
        int code = error == null ? 0 : error.path("code").asInt(0);
        String text = error == null ? "" : error.path("message").asText("");
        if (code == -32602 && UNKNOWN_TOOL_TEXT.matcher(text).matches()) {
            return logged(request, doc, tool, started, 200, false, null, ErrorCode.RULE_MISMATCH, UNKNOWN_TOOL, null);
        }
        if (code == -32602) {
            return logged(request, doc, tool, started, 200, false, null, ErrorCode.INVALID_INPUT, BAD_ARGUMENTS, null);
        }
        return logged(request, doc, tool, started, 200, false, null, ErrorCode.UPSTREAM_ERROR, HTTP_STATUS + "200", null);
    }

    private ExecutionResult mapTransport(
            ExecutionRequest request,
            ConchDoc doc,
            String tool,
            long started,
            McpUpstreamSessions.CallResult result
    ) {
        return switch (result.error()) {
            case DEADLINE -> logged(request, doc, tool, started, 0, false, null, ErrorCode.TIMEOUT, DEADLINE, null);
            case UPSTREAM_TIMEOUT -> logged(request, doc, tool, started, 0, false, null, ErrorCode.UPSTREAM_ERROR, UPSTREAM_TIMEOUT, null);
            case TOO_LARGE -> logged(request, doc, tool, started, 0, false, null, ErrorCode.UPSTREAM_ERROR, TOO_LARGE, null);
            case CONNECT -> logged(request, doc, tool, started, 0, false, null, ErrorCode.UPSTREAM_ERROR, CONNECT_FAILED, null);
            case ADDRESS -> logged(request, doc, tool, started, 0, false, null, ErrorCode.UPSTREAM_ERROR, ADDRESS_DENIED, null);
            case PROTOCOL -> logged(request, doc, tool, started, result.status(), false, null, ErrorCode.UPSTREAM_ERROR, BAD_PROTOCOL, null);
            case LOST, NOT_FOUND -> logged(request, doc, tool, started, 404, false, null, ErrorCode.UPSTREAM_ERROR, SESSION_LOST, null);
            case RPC -> mapRpc(request, doc, tool, started, result.message() == null ? JSON.missingNode() : result.message());
            case HTTP -> mapHttp(request, doc, tool, started, result.status());
        };
    }

    private ExecutionResult mapHttp(
            ExecutionRequest request,
            ConchDoc doc,
            String tool,
            long started,
            int status
    ) {
        if (status == 200) {
            return logged(request, doc, tool, started, status, false, null, ErrorCode.UPSTREAM_ERROR, INCOMPLETE, null);
        }
        if (status == 401 || status == 403) {
            return logged(request, doc, tool, started, status, false, null, ErrorCode.AUTH_FAILED, AUTH_REJECTED, null);
        }
        if (status >= 300 && status < 400) {
            return logged(request, doc, tool, started, status, false, null, ErrorCode.UPSTREAM_ERROR, HTTP_STATUS + status, null);
        }
        if (status == 0) {
            return logged(request, doc, tool, started, 0, false, null, ErrorCode.UPSTREAM_ERROR, CONNECT_FAILED, null);
        }
        return logged(request, doc, tool, started, status, false, null, ErrorCode.UPSTREAM_ERROR, HTTP_STATUS + status, null);
    }

    private ExecutionResult logged(
            ExecutionRequest request,
            ConchDoc doc,
            String tool,
            long started,
            int status,
            boolean success,
            Object data,
            ErrorCode code,
            String message,
            String failedType
    ) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
        String path = tool == null ? "" : tool;
        if (path.length() > 2048) {
            path = path.substring(0, 2048);
        }
        StepLog step = new StepLog(0, "mcp_call", elapsed, "POST", path, status == 0 ? null : status, null);
        return finish(request, doc, started, success, data, code, message, failedType, List.of(step));
    }

    private ExecutionResult finish(
            ExecutionRequest request,
            ConchDoc doc,
            long started,
            boolean success,
            Object data,
            ErrorCode code,
            String message,
            String failedType,
            List<StepLog> logs
    ) {
        Redactor redactor = redactor(request.accountContext());
        ArrayList<StepLog> scrubbed = new ArrayList<>();
        for (StepLog log : logs) {
            scrubbed.add(new StepLog(
                    log.index(),
                    redactor.redact(log.type()),
                    log.durationMillis(),
                    redactor.redact(log.method()),
                    redactor.redact(log.path()),
                    log.status(),
                    log.extractCount()));
        }
        Object masked = data == null ? null : OutputMask.apply(data, redactor);
        if (masked instanceof McpCallPayload payload) {
            masked = new McpCallPayload(
                    castList(OutputMask.apply(payload.content(), redactor)),
                    payload.structuredContent() == null ? null : OutputMask.apply(payload.structuredContent(), redactor),
                    payload.upstreamToolError());
        }
        ExecutionError error = success ? null : new ExecutionError(
                code,
                redactor.redact(clip(message)),
                failedType == null ? null : 0,
                failedType == null ? null : redactor.redact(failedType),
                redactor.redact(clip(message)));
        String version = doc == null ? null : doc.version().toString();
        return new ExecutionResult(
                success,
                success || (masked instanceof McpCallPayload payload && payload.upstreamToolError()) ? masked : null,
                error,
                TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started)),
                scrubbed,
                version,
                request.traceId());
    }

    private Map<String, String> renderHeaders(ConchDoc doc, AccountContext account) {
        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("credential", account.credentials() == null ? Map.of() : account.credentials());
        vars.put("config", account.config() == null ? Map.of() : account.config());
        LinkedHashMap<String, String> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : doc.upstream().headers().entrySet()) {
            String key = McpLimits.headerKey(entry.getKey());
            if (key.isBlank() || McpLimits.FORBIDDEN_HEADERS.contains(key) || key.startsWith("x-hermit-")) {
                continue;
            }
            Object value = templates.render(entry.getValue(), vars);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            rendered.put(entry.getKey(), text);
            knownSecrets.add(text);
        }
        return Map.copyOf(rendered);
    }

    private Redactor redactor(AccountContext account) {
        LinkedHashSet<String> secrets = new LinkedHashSet<>(knownSecrets.copy());
        if (account != null && account.credentials() != null) {
            secrets.addAll(account.credentials().values());
        }
        return Redactor.of(secrets);
    }

    private Map<String, String> authorize(Map<String, String> headers, McpOAuthToken token) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!"authorization".equalsIgnoreCase(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        String header = token.authorizationHeader();
        copy.put("Authorization", header);
        knownSecrets.add(token.accessToken());
        knownSecrets.add(token.refreshToken());
        knownSecrets.add(header);
        return Map.copyOf(copy);
    }

    private static boolean unauthorized(McpUpstreamSessions.CallResult result) {
        return result != null
                && result.error() == McpUpstreamSessions.SessionError.HTTP
                && result.status() == 401;
    }

    private static String bearerAccess(Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!"authorization".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            String value = entry.getValue().strip();
            if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                return value.substring("Bearer ".length()).strip();
            }
        }
        return null;
    }

    private static Map<String, String> credentials(AccountContext account) {
        if (account == null || account.credentials() == null) {
            return Map.of();
        }
        return account.credentials();
    }

    private static String material(Map<String, String> headers) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            text.append(entry.getKey()).append('\n').append(entry.getValue()).append('\n');
        }
        return text.toString();
    }

    private static boolean literal(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private static boolean rejectedLiteral(String host, boolean allowLoopback) {
        try {
            return McpAddressGuard.rejected(InetAddress.getByName(host), allowLoopback);
        } catch (UnknownHostException ex) {
            return true;
        }
    }

    private static List<Object> content(JsonNode node) {
        ArrayList<Object> items = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return items;
        }
        for (JsonNode item : node) {
            items.add(JSON.convertValue(item, Object.class));
        }
        return items;
    }

    private static String firstText(List<Object> content) {
        for (Object item : content) {
            if (item instanceof Map<?, ?> map && "text".equals(String.valueOf(map.get("type")))) {
                Object text = map.get("text");
                if (text != null) {
                    return String.valueOf(text);
                }
            }
        }
        return "";
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= MESSAGE_LIMIT ? trimmed : trimmed.substring(0, MESSAGE_LIMIT);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private record TokenGate(ExecutionResult denied, Map<String, String> headers) {
        static TokenGate deny(ExecutionResult result) {
            return new TokenGate(result, Map.of());
        }

        static TokenGate ready(Map<String, String> headers) {
            return new TokenGate(null, headers);
        }
    }

    private record Prepared(ExecutionResult denied, Map<String, String> headers, boolean allowLoopback) {
        static Prepared deny(ExecutionResult result) {
            return new Prepared(result, Map.of(), false);
        }

        static Prepared ready(Map<String, String> headers, boolean allowLoopback) {
            return new Prepared(null, headers, allowLoopback);
        }
    }

    record ListOutcome(
            List<JsonNode> tools,
            String protocol,
            String serverName,
            String serverVersion,
            ExecutionResult failure
    ) {
        static ListOutcome failed(ExecutionResult failure) {
            return new ListOutcome(List.of(), null, null, null, failure);
        }

        static ListOutcome ready(List<JsonNode> tools, String protocol, String serverName, String serverVersion) {
            return new ListOutcome(List.copyOf(tools), protocol, serverName, serverVersion, null);
        }
    }
}
