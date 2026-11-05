package dev.hermit.crab.upstream;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.version.SemVer;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import io.modelcontextprotocol.spec.ProtocolVersions;

/**
 * 每个 {@code (origin, conch, account)} 一条上游会话。
 * 账号锁只包住创建和删除，{@code tools/call} 只占并发名额。
 */
final class McpUpstreamSessions implements AutoCloseable {

    /** 只提供最高版本。服务端回它自己的版本；不在这四份里就失败，不再换版本重试。 */
    static final List<String> SUPPORTED = List.of(
            ProtocolVersions.MCP_2025_11_25,
            ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2024_11_05);
    static final String OFFERED = ProtocolVersions.MCP_2025_11_25;
    static final int MAX_LIST_PAGES = 20;
    static final int MAX_LIST_TOOLS = 500;
    static final String TOO_MANY_TOOLS = "上游工具超过 500 个。";
    static final String TOO_MANY_PAGES = "上游工具列表超过 20 页。";
    static final String CURSOR_STUCK = "上游工具列表的分页没有前进。";
    static final String LIST_INCOMPLETE = "上游工具列表不完整。";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpUpstreamClient client;
    private final SessionManager accounts;
    private final int permits;
    private final ConcurrentHashMap<SessionKey, Handle> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SessionKey, Semaphore> lanes = new ConcurrentHashMap<>();

    McpUpstreamSessions(McpUpstreamClient client, SessionManager accounts, int permits) {
        this.client = client;
        this.accounts = accounts;
        this.permits = permits;
    }

    Permit acquire(SessionKey key, long deadlineNanos) throws InterruptedException {
        Semaphore lane = lanes.computeIfAbsent(key, ignored -> new Semaphore(permits, true));
        long wait = deadlineNanos - System.nanoTime();
        if (wait <= 0 || !lane.tryAcquire(wait, TimeUnit.NANOSECONDS)) {
            return null;
        }
        return new Permit(lane);
    }

    CallResult call(
            SessionKey key,
            ConchDoc doc,
            String material,
            Map<String, String> headers,
            String tool,
            Map<String, Object> arguments,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            ExchangeSink sink
    ) {
        String fingerprint = fingerprint(doc, material);
        Handle current = handles.get(key);
        if (current == null || !current.fingerprint.equals(fingerprint)) {
            CallResult opened = open(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, current, sink);
            if (opened.error() != null || opened.handle() == null) {
                return opened;
            }
            current = opened.handle();
        }
        CallResult first = toolsCall(
                key, current, doc, headers, tool, arguments, deadlineNanos, timeoutMs, allowLoopback, sink);
        if (first.error() != SessionError.NOT_FOUND) {
            return first;
        }
        CallResult rebuilt = open(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, current, sink);
        if (rebuilt.error() != null || rebuilt.handle() == null) {
            return rebuilt.error() == null
                    ? CallResult.fail(SessionError.NOT_FOUND, 404, null)
                    : rebuilt;
        }
        CallResult second = toolsCall(
                key, rebuilt.handle(), doc, headers, tool, arguments, deadlineNanos, timeoutMs, allowLoopback, sink);
        if (second.error() == SessionError.NOT_FOUND) {
            handles.remove(key, rebuilt.handle());
            return CallResult.fail(SessionError.LOST, 404, null);
        }
        return second;
    }

    ListResult list(
            SessionKey key,
            ConchDoc doc,
            String material,
            Map<String, String> headers,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            ExchangeSink sink
    ) {
        String fingerprint = fingerprint(doc, material);
        Handle current = handles.get(key);
        if (current == null || !current.fingerprint.equals(fingerprint)) {
            CallResult opened = open(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, current, sink);
            if (opened.error() != null || opened.handle() == null) {
                return ListResult.failed(opened.error() == null
                        ? CallResult.fail(SessionError.NOT_FOUND, 404, null)
                        : opened);
            }
            current = opened.handle();
        }
        ListResult first = collect(key, current, doc, headers, deadlineNanos, timeoutMs, allowLoopback, sink);
        if (first.failure() == null || first.failure().error() != SessionError.NOT_FOUND) {
            return first;
        }
        CallResult rebuilt = open(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, current, sink);
        if (rebuilt.error() != null || rebuilt.handle() == null) {
            return ListResult.failed(rebuilt.error() == null
                    ? CallResult.fail(SessionError.NOT_FOUND, 404, null)
                    : rebuilt);
        }
        ListResult second = collect(key, rebuilt.handle(), doc, headers, deadlineNanos, timeoutMs, allowLoopback, sink);
        if (second.failure() != null && second.failure().error() == SessionError.NOT_FOUND) {
            handles.remove(key, rebuilt.handle());
            return ListResult.failed(CallResult.fail(SessionError.LOST, 404, null));
        }
        return second;
    }

    private CallResult open(
            SessionKey key,
            ConchDoc doc,
            String fingerprint,
            Map<String, String> headers,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            Handle previous,
            ExchangeSink sink
    ) {
        boolean locked = false;
        try {
            locked = accounts.tryLock(key, deadlineNanos);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CallResult.fail(SessionError.DEADLINE, 0, null);
        }
        if (!locked) {
            return CallResult.fail(SessionError.DEADLINE, 0, null);
        }
        try {
            Handle raced = handles.get(key);
            if (raced != null && raced.fingerprint.equals(fingerprint) && raced != previous) {
                return CallResult.ready(raced);
            }
            if (previous != null && previous.sessionId != null) {
                delete(doc, headers, previous, allowLoopback);
            }
            if (raced != null && raced != previous && raced.sessionId != null) {
                delete(doc, headers, raced, allowLoopback);
            }
            return initialize(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, sink);
        } finally {
            accounts.unlock(key);
        }
    }

    private CallResult initialize(
            SessionKey key,
            ConchDoc doc,
            String fingerprint,
            Map<String, String> headers,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            ExchangeSink sink
    ) {
        long id = 1L;
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", "initialize");
        ObjectNode params = body.putObject("params");
        params.put("protocolVersion", OFFERED);
        params.putObject("capabilities");
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "hermit-crab");
        info.put("version", dev.hermit.crab.engine.CrabVersion.CURRENT.toString());
        McpUpstreamClient.RpcResult response = post(
                doc, headers, null, OFFERED, body, id, deadlineNanos, timeoutMs, allowLoopback, false, sink);
        CallResult failed = transport(response);
        if (failed != null) {
            return failed;
        }
        if (response.status() == 404) {
            return CallResult.fail(SessionError.HTTP, 404, null);
        }
        if (response.status() != 200 || response.message() == null) {
            return CallResult.fail(SessionError.HTTP, response.status(), response.message());
        }
        JsonNode message = response.message();
        if (message.has("error")) {
            return CallResult.fail(SessionError.RPC, response.status(), message);
        }
        JsonNode result = message.path("result");
        String protocol = result.path("protocolVersion").asText("");
        if (!SUPPORTED.contains(protocol)) {
            return CallResult.fail(SessionError.PROTOCOL, response.status(), message);
        }
        String sessionId = response.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return CallResult.fail(SessionError.HTTP, response.status(), message);
        }
        ObjectNode notice = JSON.createObjectNode();
        notice.put("jsonrpc", "2.0");
        notice.put("method", "notifications/initialized");
        McpUpstreamClient.RpcResult ack = post(
                doc, headers, sessionId, protocol, notice, null, deadlineNanos, timeoutMs, allowLoopback, false, sink);
        CallResult ackFailed = transport(ack);
        if (ackFailed != null) {
            return ackFailed;
        }
        if (ack.status() != 202 && ack.status() != 200) {
            return CallResult.fail(SessionError.HTTP, ack.status(), ack.message());
        }
        JsonNode serverInfo = result.path("serverInfo");
        Handle handle = new Handle(
                fingerprint,
                sessionId,
                protocol,
                doc.version(),
                textOrNull(serverInfo.get("name")),
                textOrNull(serverInfo.get("version")));
        handles.put(key, handle);
        return CallResult.ready(handle);
    }

    private CallResult toolsCall(
            SessionKey key,
            Handle handle,
            ConchDoc doc,
            Map<String, String> headers,
            String tool,
            Map<String, Object> arguments,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            ExchangeSink sink
    ) {
        long id = nextId();
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", tool);
        params.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
        McpUpstreamClient.RpcResult response = post(
                doc, headers, handle.sessionId, handle.protocol, body, id, deadlineNanos, timeoutMs, allowLoopback, true, sink);
        if (response.status() == 404) {
            handles.remove(key, handle);
            return CallResult.fail(SessionError.NOT_FOUND, 404, null);
        }
        CallResult failed = transport(response);
        if (failed != null) {
            return failed;
        }
        if (response.status() != 200 || response.message() == null) {
            return CallResult.fail(SessionError.HTTP, response.status(), response.message());
        }
        String refreshed = response.sessionId();
        if (refreshed != null && !refreshed.isBlank() && !refreshed.equals(handle.sessionId)) {
            Handle updated = handle.withSession(refreshed);
            handles.replace(key, handle, updated);
            handle = updated;
        }
        return CallResult.message(handle, response.message());
    }

    private void delete(ConchDoc doc, Map<String, String> headers, Handle handle, boolean allowLoopback) {
        handles.values().remove(handle);
        long budget = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        post(doc, headers, handle.sessionId, handle.protocol, null, null, budget, 2_000L, allowLoopback, false, null);
    }

    private ListResult collect(
            SessionKey key,
            Handle handle,
            ConchDoc doc,
            Map<String, String> headers,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            ExchangeSink sink
    ) {
        ArrayList<JsonNode> tools = new ArrayList<>();
        HashSet<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            CallResult fetched = toolsListPage(
                    key, handle, doc, headers, cursor, deadlineNanos, timeoutMs, allowLoopback, sink);
            if (fetched.error() != null) {
                return ListResult.failed(fetched);
            }
            if (fetched.handle() != null) {
                handle = fetched.handle();
            }
            JsonNode message = fetched.message();
            if (message == null || !message.isObject()) {
                return ListResult.limit(LIST_INCOMPLETE);
            }
            if (message.has("error")) {
                return ListResult.failed(CallResult.fail(SessionError.RPC, 200, message));
            }
            JsonNode result = message.get("result");
            if (result == null || !result.isObject() || !result.path("tools").isArray()) {
                return ListResult.limit(LIST_INCOMPLETE);
            }
            for (JsonNode tool : result.path("tools")) {
                tools.add(tool);
            }
            if (tools.size() > MAX_LIST_TOOLS) {
                return ListResult.limit(TOO_MANY_TOOLS);
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull()) {
                return ListResult.ready(handle, tools);
            }
            if (!next.isTextual()) {
                return ListResult.limit(LIST_INCOMPLETE);
            }
            String nextCursor = next.asText();
            if (nextCursor.isBlank()) {
                return ListResult.ready(handle, tools);
            }
            if (page == MAX_LIST_PAGES) {
                return ListResult.limit(TOO_MANY_PAGES);
            }
            if (!cursors.add(nextCursor)) {
                return ListResult.limit(CURSOR_STUCK);
            }
            cursor = nextCursor;
        }
        return ListResult.limit(TOO_MANY_PAGES);
    }

    private CallResult toolsListPage(
            SessionKey key,
            Handle handle,
            ConchDoc doc,
            Map<String, String> headers,
            String cursor,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            ExchangeSink sink
    ) {
        long id = nextId();
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", "tools/list");
        ObjectNode params = body.putObject("params");
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        McpUpstreamClient.RpcResult response = post(
                doc, headers, handle.sessionId, handle.protocol, body, id, deadlineNanos, timeoutMs, allowLoopback, false, sink);
        if (response.status() == 404) {
            handles.remove(key, handle);
            return CallResult.fail(SessionError.NOT_FOUND, 404, null);
        }
        CallResult failed = transport(response);
        if (failed != null) {
            return failed;
        }
        if (response.status() != 200 || response.message() == null) {
            return CallResult.fail(SessionError.HTTP, response.status(), response.message());
        }
        String refreshed = response.sessionId();
        if (refreshed != null && !refreshed.isBlank() && !refreshed.equals(handle.sessionId)) {
            Handle updated = handle.withSession(refreshed);
            handles.replace(key, handle, updated);
            handle = updated;
        }
        return CallResult.message(handle, response.message());
    }

    private McpUpstreamClient.RpcResult post(
            ConchDoc doc,
            Map<String, String> headers,
            String sessionId,
            String protocol,
            ObjectNode body,
            Long awaitId,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            boolean notifyCancel,
            ExchangeSink sink
    ) {
        byte[] bytes = null;
        if (body != null) {
            try {
                bytes = JSON.writeValueAsBytes(body);
            } catch (java.io.IOException ex) {
                return McpUpstreamClient.RpcResult.connect();
            }
        }
        long started = System.nanoTime();
        McpUpstreamClient.RpcResult response = client.exchange(new McpUpstreamClient.Post(
                body == null && sessionId != null && awaitId == null ? "DELETE" : "POST",
                URI.create(doc.upstream().url()),
                headers,
                sessionId,
                protocol,
                bytes,
                awaitId,
                deadlineNanos,
                timeoutMs,
                allowLoopback,
                notifyCancel));
        record(sink, body, response, sessionId, started);
        return response;
    }

    private static void record(
            ExchangeSink sink,
            ObjectNode body,
            McpUpstreamClient.RpcResult response,
            String requestSession,
            long startedNanos
    ) {
        if (sink == null || body == null) {
            return;
        }
        try {
            String method = body.path("method").asText("");
            if (method.isBlank()) {
                return;
            }
            Object id = jsonValue(body.get("id"));
            Object params = jsonValue(body.get("params"));
            JsonNode message = response.message();
            Object result = message == null ? null : jsonValue(message.get("result"));
            Object error = message == null ? null : jsonValue(message.get("error"));
            if (error == null && response.kind() != McpUpstreamClient.RpcKind.MESSAGE
                    && response.kind() != McpUpstreamClient.RpcKind.EMPTY) {
                error = Map.of("kind", response.kind().name());
            } else if (error == null && response.status() != 200 && response.status() != 202) {
                error = Map.of("httpStatus", response.status());
            }
            String session = response.sessionId();
            if (session == null || session.isBlank()) {
                session = requestSession;
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
            sink.onExchange(method, id, response.status(), duration, params, result, error, session);
        } catch (RuntimeException ex) {
            // 追踪失败不影响这次调用。
        }
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return JSON.convertValue(node, Object.class);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    private static CallResult transport(McpUpstreamClient.RpcResult response) {
        return switch (response.kind()) {
            case DEADLINE -> CallResult.fail(SessionError.DEADLINE, 0, null);
            case UPSTREAM_TIMEOUT -> CallResult.fail(SessionError.UPSTREAM_TIMEOUT, 0, null);
            case TOO_LARGE -> CallResult.fail(SessionError.TOO_LARGE, 0, null);
            case CONNECT -> CallResult.fail(SessionError.CONNECT, 0, null);
            case ADDRESS -> CallResult.fail(SessionError.ADDRESS, 0, null);
            case EMPTY, MESSAGE -> null;
        };
    }

    private static String fingerprint(ConchDoc doc, String material) {
        String raw = doc.version() + "\n" + doc.upstream().url() + "\n" + material;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(Character.forDigit((value >>> 4) & 0xf, 16));
                text.append(Character.forDigit(value & 0xf, 16));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long nextId() {
        long value = System.nanoTime();
        return value == 0L ? 1L : Math.abs(value);
    }

    /** 断开登录时丢掉本机会话。不向上游发 DELETE，下一次调用会重新 initialize。 */
    void discard(SessionKey key) {
        if (key != null) {
            handles.remove(key);
        }
    }

    @Override
    public void close() {
        handles.clear();
        client.close();
    }

    static final class Handle {
        final String fingerprint;
        final String sessionId;
        final String protocol;
        final SemVer version;
        final String serverName;
        final String serverVersion;

        Handle(String fingerprint, String sessionId, String protocol, SemVer version, String serverName, String serverVersion) {
            this.fingerprint = fingerprint;
            this.sessionId = sessionId;
            this.protocol = protocol;
            this.version = version;
            this.serverName = serverName;
            this.serverVersion = serverVersion;
        }

        Handle withSession(String sessionId) {
            return new Handle(fingerprint, sessionId, protocol, version, serverName, serverVersion);
        }
    }

    @FunctionalInterface
    interface ExchangeSink {
        void onExchange(
                String method,
                Object id,
                int httpStatus,
                long durationMs,
                Object params,
                Object result,
                Object error,
                String sessionId
        );
    }

    enum SessionError {
        DEADLINE,
        UPSTREAM_TIMEOUT,
        TOO_LARGE,
        CONNECT,
        ADDRESS,
        NOT_FOUND,
        LOST,
        PROTOCOL,
        HTTP,
        RPC
    }

    record CallResult(Handle handle, JsonNode message, SessionError error, int status) {
        static CallResult ready(Handle handle) {
            return new CallResult(handle, null, null, 200);
        }

        static CallResult message(Handle handle, JsonNode message) {
            return new CallResult(handle, message, null, 200);
        }

        static CallResult fail(SessionError error, int status, JsonNode message) {
            return new CallResult(null, message, error, status);
        }
    }

    record ListResult(Handle handle, List<JsonNode> tools, CallResult failure, String limitMessage) {
        static ListResult ready(Handle handle, List<JsonNode> tools) {
            return new ListResult(handle, List.copyOf(tools), null, null);
        }

        static ListResult failed(CallResult failure) {
            return new ListResult(failure == null ? null : failure.handle(), List.of(), failure, null);
        }

        static ListResult limit(String message) {
            return new ListResult(null, List.of(), null, message);
        }
    }

    static final class Permit implements AutoCloseable {
        private final Semaphore lane;

        Permit(Semaphore lane) {
            this.lane = lane;
        }

        @Override
        public void close() {
            lane.release();
        }
    }

}
