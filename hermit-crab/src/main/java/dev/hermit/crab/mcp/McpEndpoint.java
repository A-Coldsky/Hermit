package dev.hermit.crab.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.gateway.RequestBodies;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.ApiKeyPrincipal;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;

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
    private static final Set<String> PROTOCOLS = Set.of(
            ProtocolVersions.MCP_2024_11_05,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_11_25);
    private static final McpSchema.ServerCapabilities CAPABILITIES = McpSchema.ServerCapabilities.builder()
            .tools(true)
            .resources(false, true)
            .build();
    private static final McpSchema.Implementation SERVER_INFO = McpSchema.Implementation.builder(
            "hermit-crab", CrabVersion.CURRENT.toString()).build();

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
        this.catalog = new McpCatalog(runtime.registry(), runtime::disabled);
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
        sweep();
        Optional<ApiKeyPrincipal> principal = authenticate(ctx);
        if (principal.isEmpty()) {
            writeAuth(ctx);
            return;
        }
        JsonNode message;
        try {
            message = McpJson.tree(RequestBodies.read(ctx.req()));
        } catch (RequestBodies.BodyTooLargeException ex) {
            writeRpcError(ctx, null, 400, McpSchema.ErrorCodes.INVALID_REQUEST, "request body too large", null);
            return;
        } catch (IllegalArgumentException ex) {
            writeRpcError(ctx, null, 200, McpSchema.ErrorCodes.PARSE_ERROR, "parse error", null);
            return;
        } catch (IOException ex) {
            writeRpcError(ctx, null, 200, McpSchema.ErrorCodes.PARSE_ERROR, "parse error", null);
            return;
        }
        try {
            dispatch(ctx, principal.get(), message);
        } catch (RuntimeException ex) {
            LOG.warning("mcp request failed: " + ex.getClass().getName());
            if (!ctx.res().isCommitted()) {
                writeRpcError(ctx, id(message), 200, McpSchema.ErrorCodes.INTERNAL_ERROR, "internal error", null);
            }
        }
    }

    private void dispatch(Context ctx, ApiKeyPrincipal principal, JsonNode message) {
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            writeRpcError(ctx, id(message), 200, McpSchema.ErrorCodes.INVALID_REQUEST, "invalid request", null);
            return;
        }
        boolean hasMethod = message.hasNonNull("method");
        JsonNode id = id(message);
        boolean hasId = id != null;
        if (!hasMethod && hasId && (message.has("result") || message.has("error"))) {
            McpSession session = requireSession(ctx, principal);
            if (session == null) {
                return;
            }
            session.complete(id, message.get("result"), message.hasNonNull("error"));
            accepted(ctx, session);
            return;
        }
        if (!hasMethod) {
            writeRpcError(ctx, id, 200, McpSchema.ErrorCodes.INVALID_REQUEST, "invalid request", null);
            return;
        }
        String method = message.path("method").asText();
        if (!hasId) {
            McpSession session = requireSession(ctx, principal);
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
        McpSession session = requireSession(ctx, principal);
        if (session == null) {
            return;
        }
        switch (method) {
            case "ping" -> writeResult(ctx, id, Map.of(), session);
            case "tools/list" -> writeResult(ctx, id, McpSchema.ListToolsResult.builder(catalog.tools(principal)).build(), session);
            case "tools/call" -> writeResult(ctx, id, calls.call(principal, session, headerAccount(ctx), message.get("params")), session);
            case "resources/list" -> writeResult(ctx, id, McpSchema.ListResourcesResult.builder(catalog.resources(principal)).build(), session);
            case "resources/read" -> readResource(ctx, principal, id, message.get("params"), session);
            default -> writeRpcError(ctx, id, 200, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "method not found", session);
        }
    }

    private void initialize(Context ctx, ApiKeyPrincipal principal, JsonNode id, JsonNode params) {
        McpSchema.InitializeRequest request;
        try {
            request = McpJson.convert(params, McpSchema.InitializeRequest.class);
        } catch (IllegalArgumentException ex) {
            writeRpcError(ctx, id, 200, McpSchema.ErrorCodes.INVALID_PARAMS, "invalid params", null);
            return;
        }
        String protocol = PROTOCOLS.contains(request.protocolVersion())
                ? request.protocolVersion()
                : ProtocolVersions.MCP_2025_11_25;
        McpSession session = new McpSession(UUID.randomUUID().toString(), principal.id(), request.capabilities());
        synchronized (sessionLock) {
            reap(clock.millis());
            session.touch(clock.millis(), useSequence.incrementAndGet());
            sessions.put(session.id, session);
            trim();
        }
        McpSchema.InitializeResult result = McpSchema.InitializeResult.builder(protocol, CAPABILITIES, SERVER_INFO).build();
        writeResult(ctx, id, result, session);
    }

    private void readResource(Context ctx, ApiKeyPrincipal principal, JsonNode id, JsonNode params, McpSession session) {
        McpSchema.ReadResourceRequest request;
        try {
            request = McpJson.convert(params, McpSchema.ReadResourceRequest.class);
        } catch (IllegalArgumentException ex) {
            writeRpcError(ctx, id, 200, McpSchema.ErrorCodes.INVALID_PARAMS, "invalid params", session);
            return;
        }
        Optional<String> guide = catalog.guide(principal, request.uri());
        if (guide.isEmpty()) {
            writeRpcError(ctx, id, 200, McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "not found", session);
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
        sweep();
        Optional<ApiKeyPrincipal> principal = authenticate(ctx);
        if (principal.isEmpty()) {
            writeAuth(ctx);
            return;
        }
        McpSession session = requireSession(ctx, principal.get());
        if (session == null) {
            return;
        }
        McpSession.Sink sink = session.openSink();
        try {
            ctx.status(200);
            ctx.contentType("text/event-stream; charset=utf-8");
            ctx.header("Cache-Control", "no-cache");
            ctx.header(SESSION_HEADER, session.id);
            OutputStream out = ctx.res().getOutputStream();
            writeEvent(ctx, out, ": open\n\n");
            while (!closed.get() && !session.closed.get() && !sink.closed.get()) {
                String json = sink.queue.poll(15, TimeUnit.SECONDS);
                if (closed.get() || session.closed.get()) {
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
        sweep();
        Optional<ApiKeyPrincipal> principal = authenticate(ctx);
        if (principal.isEmpty()) {
            writeAuth(ctx);
            return;
        }
        McpSession session = requireSession(ctx, principal.get());
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

    private McpSession requireSession(Context ctx, ApiKeyPrincipal principal) {
        String id = ctx.header(SESSION_HEADER);
        if (id == null || id.isBlank()) {
            writeRpcError(ctx, null, 400, McpSchema.ErrorCodes.INVALID_REQUEST, "session required", null);
            return null;
        }
        McpSession session = sessions.get(id.trim());
        if (session == null || session.closed.get()) {
            writeRpcError(ctx, null, 404, McpSchema.ErrorCodes.INVALID_REQUEST, "session not found", null);
            return null;
        }
        if (!session.apiKeyId.equals(principal.id())) {
            writeAuth(ctx);
            return null;
        }
        synchronized (sessionLock) {
            if (session.closed.get() || !sessions.containsKey(session.id)) {
                writeRpcError(ctx, null, 404, McpSchema.ErrorCodes.INVALID_REQUEST, "session not found", null);
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

    private Optional<ApiKeyPrincipal> authenticate(Context ctx) {
        String token = token(ctx);
        if (token == null) {
            return Optional.empty();
        }
        return runtime.apiKeys().authenticate(token);
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

    private static void writeAuth(Context ctx) {
        String traceId = UUID.randomUUID().toString();
        ctx.status(401);
        ctx.contentType("application/json; charset=utf-8");
        ctx.header("X-Hermit-Trace", traceId);
        ctx.result(ApiJson.bytes(ApiEnvelope.json(null, ErrorCode.AUTH_FAILED, "invalid api key", traceId)));
    }

    private static void writeResult(Context ctx, JsonNode id, Object result, McpSession session) {
        ObjectNode envelope = McpJson.mapper().createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id == null) {
            envelope.putNull("id");
        } else {
            envelope.set("id", id);
        }
        envelope.set("result", McpJson.mapper().valueToTree(result));
        writeJson(ctx, 200, envelope, session);
    }

    private static void writeRpcError(Context ctx, JsonNode id, int status, int code, String message, McpSession session) {
        ObjectNode envelope = McpJson.mapper().createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id == null) {
            envelope.putNull("id");
        } else {
            envelope.set("id", id);
        }
        ObjectNode error = McpJson.mapper().createObjectNode();
        error.put("code", code);
        error.put("message", message);
        envelope.set("error", error);
        writeJson(ctx, status, envelope, session);
    }

    private static void writeJson(Context ctx, int status, ObjectNode envelope, McpSession session) {
        ctx.status(status);
        ctx.contentType("application/json; charset=utf-8");
        if (session != null) {
            ctx.header(SESSION_HEADER, session.id);
        }
        ctx.result(McpJson.write(envelope));
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
