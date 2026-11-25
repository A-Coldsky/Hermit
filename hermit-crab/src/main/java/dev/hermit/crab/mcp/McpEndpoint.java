package dev.hermit.crab.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.gateway.Loopback;
import dev.hermit.crab.gateway.RequestBodies;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.ApiKeyPrincipal;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Streamable HTTP on Javalin. The SDK servlet transport is compiled against Jakarta Servlet 6.1,
 * which Jetty 11 (Javalin 6) does not provide, and its tool list is global, so it cannot hide
 * capabilities outside an API key. Tool definitions still use the SDK schema types.
 */
public final class McpEndpoint {
    static final Duration IDLE_LIMIT = Duration.ofHours(1);
    static final int MAX_SESSIONS = 1000;

    private static final Logger LOG = Logger.getLogger(McpEndpoint.class.getName());
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String TRACE = "hermit.mcp.traceId";
    private static final McpSchema.ServerCapabilities CAPABILITIES = McpSchema.ServerCapabilities.builder()
            .tools(true)
            .resources(false, true)
            .build();
    private static final McpSchema.Implementation SERVER_INFO = McpSchema.Implementation.builder(
            "hermit-crab", CrabVersion.CURRENT.toString()).build();
    static final String AUTHORING_INSTRUCTIONS =
            "当前已开启 Hermit Crab 本机开发者模式，可用于编写或修改规则包（Conch）。"
                    + "如果用户在 Crab 控制台录制或导入并保存了脱敏抓包快照，优先用 conch_capture_list 查看快照目录，再用 conch_capture_get 按序号读取详情；"
                    + "写或改 YAML 前先调用 conch_guide（或读取资源 hermit://authoring/guide）获取可直接复制修改的 starterTemplate、pitfalls 避坑清单、步骤速查与相关章节；"
                    + "修改已有规则包前先用 conch_read_yaml 读取原文（Reef 订阅的正式包只读不可直接修改，如需改动请用 conch_save_local 保存为本地规则包，可起新包名立即生效，或保存为同名本地副本再由用户在控制台切换来源）。"
                    + "推荐顺序：先和用户确认要做哪些功能 → conch_validate 校验至无错（失败可按 fixHints/suggestedSections 修正） → conch_save_local 保存 → 用户在控制台建账号填凭证并可点「测试登录」 → conch_trial_run 试跑；测试登录、验证码续登或试跑失败时，用 debug_trace 查 traceId，或直接用 debug_trace_list 列出最近本机开发追踪排查原因。"
                    + "不要向用户索要口令、Cookie 或令牌，也不要写入 YAML。";

    private final CrabRuntime runtime;
    private final McpCatalog catalog;
    private final McpCalls calls;
    private final Clock clock;
    private final long idleMillis;
    private final int maxSessions;
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final Object sessionLock = new Object();
    private final AtomicLong useSequence = new AtomicLong();
    private final ScheduledExecutorService sweeper;
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpEndpoint(CrabRuntime runtime) {
        this(runtime, Clock.systemUTC(), IDLE_LIMIT, MAX_SESSIONS);
    }

    McpEndpoint(CrabRuntime runtime, Clock clock, Duration idleLimit, int maxSessions) {
        this.runtime = runtime;
        this.catalog = new McpCatalog(runtime.registry(), runtime::disabled,
                () -> runtime.debug().get().enabled(), runtime.config().mcpDirectLimit());
        this.calls = new McpCalls(runtime, catalog);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idleMillis = (idleLimit == null ? IDLE_LIMIT : idleLimit).toMillis();
        this.maxSessions = maxSessions;
        runtime.registry().addListener(this::broadcastChanges);
        runtime.addCatalogListener(this::broadcastChanges);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcp-session-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweepQuietly, 1, 1, TimeUnit.MINUTES);
    }

    public void register(Javalin http) {
        http.post("/mcp", this::post);
        http.get("/mcp", this::get);
        http.delete("/mcp", this::delete);
    }

    int openSessions() {
        synchronized (sessionLock) {
            return sessions.size();
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sweeper.shutdownNow();
        synchronized (sessionLock) {
            for (McpSession session : sessions.values()) {
                session.wake();
            }
            sessions.clear();
        }
    }

    private void broadcastChanges() {
        if (closed.get() || sessions.isEmpty()) {
            return;
        }
        String tools = notification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);
        String resources = notification(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED);
        for (McpSession session : sessions.values()) {
            for (McpSession.Sink sink : snapshotSinks(session)) {
                sink.queue.offer(tools);
                sink.queue.offer(resources);
            }
        }
    }

    private void post(Context ctx) {
        if (closed.get()) {
            return;
        }
        if (!acceptOrigin(ctx)) {
            return;
        }
        sweep();
        Optional<ApiKeyPrincipal> principal = authenticate(ctx);
        if (principal.isEmpty()) {
            return;
        }
        JsonNode message;
        try {
            message = McpJson.tree(RequestBodies.read(ctx.req()));
        } catch (RequestBodies.BodyTooLargeException ex) {
            writeRpcError(ctx, null, McpErrors.requestTooLarge(), null);
            return;
        } catch (IllegalArgumentException ex) {
            writeRpcError(ctx, null, McpErrors.parseError(), null);
            return;
        } catch (IOException ex) {
            writeRpcError(ctx, null, McpErrors.parseError(), null);
            return;
        }
        try {
            dispatch(ctx, principal.get(), message);
        } catch (RuntimeException ex) {
            LOG.warning("mcp request failed: " + ex.getClass().getName());
            if (!ctx.res().isCommitted()) {
                writeRpcError(ctx, id(message), McpErrors.internalError(), null);
            }
        }
    }

    private void dispatch(Context ctx, ApiKeyPrincipal principal, JsonNode message) {
        if (!isInitialize(message) && !acceptProtocolHeader(ctx, id(message))) {
            return;
        }
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            writeRpcError(ctx, id(message), McpErrors.invalidRequest(), null);
            return;
        }
        boolean hasMethod = message.hasNonNull("method");
        JsonNode id = id(message);
        boolean hasId = id != null;
        if (!hasMethod && hasId && (message.has("result") || message.has("error"))) {
            McpSession session = requireSession(ctx, principal, id);
            if (session == null) {
                return;
            }
            session.complete(id, message.get("result"), message.hasNonNull("error"));
            accepted(ctx, session);
            return;
        }
        if (!hasMethod) {
            writeRpcError(ctx, id, McpErrors.invalidRequest(), null);
            return;
        }
        String method = message.path("method").asText();
        if (!hasId) {
            McpSession session = requireSession(ctx, principal, null);
            if (session == null) {
                return;
            }
            accepted(ctx, session);
            return;
        }
        if ("initialize".equals(method)) {
            initialize(ctx, principal, id, message.get("params"));
            return;
        }
        McpSession session = requireSession(ctx, principal, id);
        if (session == null) {
            return;
        }
        switch (method) {
            case "ping" -> writeResult(ctx, id, Map.of(), session);
            case "tools/list" -> writeResult(ctx, id,
                    McpSchema.ListToolsResult.builder(catalog.tools(
                            principal, session.protocolVersion, Loopback.address(ctx.req().getRemoteAddr()))).build(),
                    session);
            case "tools/call" -> callTool(ctx, principal, id, message.get("params"), session);
            case "resources/list" -> writeResult(ctx, id,
                    McpSchema.ListResourcesResult.builder(catalog.resources(
                            principal, Loopback.address(ctx.req().getRemoteAddr()))).build(),
                    session);
            case "resources/read" -> readResource(ctx, principal, id, message.get("params"), session);
            default -> writeRpcError(ctx, id, McpErrors.methodNotFound(), session);
        }
    }

    private void initialize(Context ctx, ApiKeyPrincipal principal, JsonNode id, JsonNode params) {
        McpSchema.InitializeRequest request;
        try {
            request = McpJson.convert(params, McpSchema.InitializeRequest.class);
        } catch (IllegalArgumentException ex) {
            writeRpcError(ctx, id, McpErrors.invalidParams(), null);
            return;
        }
        String protocol = McpTransportPolicy.negotiate(request.protocolVersion());
        if (protocol == null) {
            writeRpcError(ctx, id, McpErrors.unsupportedProtocolVersion(), null);
            return;
        }
        McpSession session = new McpSession(UUID.randomUUID().toString(), principal.id(), protocol, request.capabilities());
        synchronized (sessionLock) {
            reap(clock.millis());
            session.touch(clock.millis(), useSequence.incrementAndGet());
            sessions.put(session.id, session);
            trim();
        }
        McpSchema.InitializeResult.Builder resultBuilder = McpSchema.InitializeResult.builder(
                protocol, CAPABILITIES, SERVER_INFO);
        if (runtime.debug().get().enabled() && Loopback.address(ctx.req().getRemoteAddr())) {
            resultBuilder.instructions(AUTHORING_INSTRUCTIONS);
        }
        McpSchema.InitializeResult result = resultBuilder.build();
        writeResult(ctx, id, result, session);
    }

    private void readResource(Context ctx, ApiKeyPrincipal principal, JsonNode id, JsonNode params, McpSession session) {
        McpSchema.ReadResourceRequest request;
        try {
            request = McpJson.convert(params, McpSchema.ReadResourceRequest.class);
        } catch (IllegalArgumentException ex) {
            writeRpcError(ctx, id, McpErrors.invalidParams(), session);
            return;
        }
        if (AuthoringGuide.URI.equals(request.uri()) && !Loopback.address(ctx.req().getRemoteAddr())) {
            writeRpcError(ctx, id, McpErrors.authoringRemote(), session);
            return;
        }
        if (AuthoringGuide.URI.equals(request.uri()) && !runtime.debug().get().enabled()) {
            writeRpcError(ctx, id, McpErrors.devModeClosed(), session);
            return;
        }
        Optional<String> guide = catalog.guide(principal, request.uri());
        if (guide.isEmpty()) {
            writeRpcError(ctx, id, McpErrors.resourceNotFound(), session);
            return;
        }
        McpSchema.TextResourceContents contents = McpSchema.TextResourceContents.builder(request.uri(), guide.get())
                .mimeType("text/markdown")
                .build();
        writeResult(ctx, id, McpSchema.ReadResourceResult.builder(java.util.List.of(contents)).build(), session);
    }

    private void get(Context ctx) {
        if (closed.get()) {
            return;
        }
        if (!acceptOrigin(ctx) || !acceptProtocolHeader(ctx, null)) {
            return;
        }
        sweep();
        Optional<ApiKeyPrincipal> principal = authenticate(ctx);
        if (principal.isEmpty()) {
            return;
        }
        McpSession session = requireSession(ctx, principal.get(), null);
        if (session == null) {
            return;
        }
        McpSession.Sink sink = session.openSink();
        if (sink == null) {
            boolean gone = session.closed.get();
            writeRpcError(ctx, null, gone ? McpErrors.sessionNotFound() : McpErrors.tooManyStreams(),
                    gone ? null : session);
            return;
        }
        try {
            ctx.status(200);
            ctx.contentType("text/event-stream; charset=utf-8");
            ctx.header("Cache-Control", "no-cache");
            ctx.header(SESSION_HEADER, session.id);
            OutputStream out = ctx.res().getOutputStream();
            writeEvent(ctx, out, ": open\n\n");
            while (!closed.get() && !session.closed.get() && !sink.closed.get()) {
                String json = sink.queue.poll(15, TimeUnit.SECONDS);
                if (closed.get() || session.closed.get() || sink.closed.get()) {
                    break;
                }
                if (json == null) {
                    writeEvent(ctx, out, ": keep-alive\n\n");
                } else if (!json.isBlank()) {
                    writeEvent(ctx, out, "event: message\ndata: " + json + "\n\n");
                }
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            session.closeSink(sink);
        }
    }

    private void delete(Context ctx) {
        if (closed.get()) {
            return;
        }
        if (!acceptOrigin(ctx) || !acceptProtocolHeader(ctx, null)) {
            return;
        }
        sweep();
        Optional<ApiKeyPrincipal> principal = authenticate(ctx);
        if (principal.isEmpty()) {
            return;
        }
        McpSession session = requireSession(ctx, principal.get(), null);
        if (session == null) {
            return;
        }
        synchronized (sessionLock) {
            sessions.remove(session.id, session);
        }
        session.wake();
        ctx.status(200);
        ctx.result("");
    }

    private McpSession requireSession(Context ctx, ApiKeyPrincipal principal, JsonNode requestId) {
        String id = ctx.header(SESSION_HEADER);
        if (id == null || id.isBlank()) {
            writeRpcError(ctx, requestId, McpErrors.sessionRequired(), null);
            return null;
        }
        McpSession session = sessions.get(id.trim());
        if (session == null || session.closed.get()) {
            writeRpcError(ctx, requestId, McpErrors.sessionNotFound(), null);
            return null;
        }
        if (!session.apiKeyId.equals(principal.id())) {
            writeAuthFailure(ctx, McpErrors.sessionMismatch());
            return null;
        }
        synchronized (sessionLock) {
            if (session.closed.get() || !sessions.containsKey(session.id)) {
                writeRpcError(ctx, requestId, McpErrors.sessionNotFound(), null);
                return null;
            }
            session.touch(clock.millis(), useSequence.incrementAndGet());
        }
        return session;
    }

    private void sweep() {
        synchronized (sessionLock) {
            reap(clock.millis());
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException ex) {
            LOG.warning("mcp session sweep failed: " + ex.getClass().getName());
        }
    }

    /** Caller holds {@code sessionLock}. Drops sessions idle for an hour, then the least recently used past the cap. */
    private void reap(long now) {
        long cutoff = now - idleMillis;
        for (McpSession session : List.copyOf(sessions.values())) {
            if (session.lastUsed() <= cutoff) {
                drop(session);
            }
        }
        trim();
    }

    private void trim() {
        int extra = sessions.size() - maxSessions;
        if (extra <= 0) {
            return;
        }
        ArrayList<McpSession> ranked = new ArrayList<>(sessions.values());
        ranked.sort(Comparator.comparingLong(McpSession::lastUsed).thenComparingLong(McpSession::sequence));
        for (int i = 0; i < extra; i++) {
            drop(ranked.get(i));
        }
    }

    private void drop(McpSession session) {
        if (sessions.remove(session.id, session)) {
            session.wake();
        }
    }

    private boolean acceptOrigin(Context ctx) {
        if (McpTransportPolicy.originAllowed(
                headerValues(ctx, "Origin"),
                runtime.config().serverHost(),
                ctx.req().getLocalPort(),
                runtime.config().mcpAllowedOrigins())) {
            return true;
        }
        writeRpcError(ctx, null, McpErrors.originRejected(), null);
        return false;
    }

    private boolean acceptProtocolHeader(Context ctx, JsonNode requestId) {
        if (McpTransportPolicy.protocolHeaderAllowed(headerValues(ctx, McpTransportPolicy.PROTOCOL_HEADER))) {
            return true;
        }
        writeRpcError(ctx, requestId, McpErrors.unsupportedProtocolHeader(), null);
        return false;
    }

    private static boolean isInitialize(JsonNode message) {
        return message != null && message.isObject() && "initialize".equals(message.path("method").asText());
    }

    private static List<String> headerValues(Context ctx, String name) {
        Enumeration<String> values = ctx.req().getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return List.of();
        }
        ArrayList<String> collected = new ArrayList<>();
        while (values.hasMoreElements()) {
            collected.add(values.nextElement());
        }
        return List.copyOf(collected);
    }

    private Optional<ApiKeyPrincipal> authenticate(Context ctx) {
        String token = token(ctx);
        if (token == null) {
            writeAuthFailure(ctx, McpErrors.authMissing());
            return Optional.empty();
        }
        Optional<ApiKeyPrincipal> principal = runtime.apiKeys().authenticate(token);
        if (principal.isEmpty()) {
            writeAuthFailure(ctx, McpErrors.authInvalid());
        }
        return principal;
    }

    private static Optional<String> headerAccount(Context ctx) {
        String account = ctx.header("X-Hermit-Account");
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(account.trim());
    }

    private static String token(Context ctx) {
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = authorization.substring(7).trim();
            if (!bearer.isEmpty()) {
                return bearer;
            }
        }
        String header = ctx.header("X-Hermit-Key");
        if (header == null) {
            return null;
        }
        String value = header.trim();
        return value.isEmpty() ? null : value;
    }

    private void callTool(Context ctx, ApiKeyPrincipal principal, JsonNode id, JsonNode params, McpSession session) {
        String traceId = trace(ctx);
        try {
            McpCalls.Called called = calls.callTraced(
                    principal,
                    session,
                    headerAccount(ctx),
                    params,
                    traceId,
                    Loopback.address(ctx.req().getRemoteAddr()));
            writeResult(ctx, id, called.result(), session, called.traced());
        } catch (McpProtocolException ex) {
            writeRpcError(ctx, id, McpErrors.invalidParams(), session);
        }
    }

    private static void writeAuthFailure(Context ctx, McpErrors.Failure failure) {
        writeRpcError(ctx, null, failure, null);
    }

    /** 同一次 MCP HTTP 请求的调用编号，和 HTTP 网关的 {@code X-Hermit-Trace} 是同一类标识。 */
    private static String trace(Context ctx) {
        Object value = ctx.attribute(TRACE);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        String created = UUID.randomUUID().toString();
        ctx.attribute(TRACE, created);
        return created;
    }

    /** initialize、ping、各种 list/read：不执行能力，不留记录，响应头不带追踪编号。 */
    private static void writeResult(Context ctx, JsonNode id, Object result, McpSession session) {
        writeResult(ctx, id, result, session, false);
    }

    private static void writeResult(Context ctx, JsonNode id, Object result, McpSession session, boolean traced) {
        ObjectNode envelope = McpJson.mapper().createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id == null) {
            envelope.putNull("id");
        } else {
            envelope.set("id", id);
        }
        JsonNode tree = McpJson.mapper().valueToTree(result);
        envelope.set("result", tree);
        writeJson(ctx, 200, envelope, session, traced && !requestOnly(tree));
    }

    private static void writeRpcError(Context ctx, JsonNode id, McpErrors.Failure failure, McpSession session) {
        ObjectNode envelope = McpJson.mapper().createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id == null) {
            envelope.putNull("id");
        } else {
            envelope.set("id", id);
        }
        ObjectNode error = McpJson.mapper().createObjectNode();
        error.put("code", failure.rpcCode());
        error.put("message", failure.message());
        ObjectNode data = McpJson.mapper().createObjectNode();
        data.put("code", failure.code());
        data.put("hint", failure.hint());
        // 协议层的错误（Key 无效、会话不对、方法不存在、开发者模式关着…）都发生在执行之前，
        // 没有调用记录可查：只给请求编号，响应头也不带 X-Hermit-Trace。
        data.put("requestId", trace(ctx));
        data.put("requestNote", ApiEnvelope.REQUEST_NOTE);
        error.set("data", data);
        envelope.set("error", error);
        writeJson(ctx, failure.status(), envelope, session, false);
    }

    private static void writeJson(Context ctx, int status, ObjectNode envelope, McpSession session, boolean includeTrace) {
        ctx.status(status);
        ctx.contentType("application/json; charset=utf-8");
        if (includeTrace) {
            ctx.header("X-Hermit-Trace", trace(ctx));
        }
        if (session != null) {
            ctx.header(SESSION_HEADER, session.id);
        }
        ctx.result(McpJson.write(envelope));
    }

    /** A tool result that carries a request id and no trace id must not set {@code X-Hermit-Trace}. */
    private static boolean requestOnly(JsonNode tree) {
        JsonNode structured = tree.path("structuredContent");
        return requestNumber(structured) || requestNumber(structured.path("error"));
    }

    private static boolean requestNumber(JsonNode node) {
        return node.isObject() && node.hasNonNull("requestId") && !node.has("traceId");
    }

    private static void accepted(Context ctx, McpSession session) {
        ctx.status(202);
        if (session != null) {
            ctx.header(SESSION_HEADER, session.id);
        }
        ctx.result("");
    }

    private static JsonNode id(JsonNode message) {
        if (message == null || !message.has("id") || message.get("id").isNull()) {
            return null;
        }
        return message.get("id");
    }

    private static String notification(String method) {
        return McpJson.write(new McpSchema.JSONRPCNotification(method));
    }

    private static java.util.List<McpSession.Sink> snapshotSinks(McpSession session) {
        // Sinks are added only through openSink; broadcast offers onto the same queues.
        return java.util.List.copyOf(sessionSinks(session));
    }

    private static java.util.List<McpSession.Sink> sessionSinks(McpSession session) {
        return session.copySinks();
    }

    private static void writeEvent(Context ctx, OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
        ctx.res().flushBuffer();
    }
}
