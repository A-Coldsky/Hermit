package dev.hermit.crab.upstream;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
            boolean allowLoopback
    ) {
        String fingerprint = fingerprint(doc, material);
        Handle current = handles.get(key);
        if (current == null || !current.fingerprint.equals(fingerprint)) {
            CallResult opened = open(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, current);
            if (opened.error() != null || opened.handle() == null) {
                return opened;
            }
            current = opened.handle();
        }
        CallResult first = toolsCall(key, current, doc, headers, tool, arguments, deadlineNanos, timeoutMs, allowLoopback);
        if (first.error() != SessionError.NOT_FOUND) {
            return first;
        }
        CallResult rebuilt = open(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback, current);
        if (rebuilt.error() != null || rebuilt.handle() == null) {
            return rebuilt.error() == null
                    ? CallResult.fail(SessionError.NOT_FOUND, 404, null)
                    : rebuilt;
        }
        CallResult second = toolsCall(key, rebuilt.handle(), doc, headers, tool, arguments, deadlineNanos, timeoutMs, allowLoopback);
        if (second.error() == SessionError.NOT_FOUND) {
            handles.remove(key, rebuilt.handle());
            return CallResult.fail(SessionError.LOST, 404, null);
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
            Handle previous
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
            return initialize(key, doc, fingerprint, headers, deadlineNanos, timeoutMs, allowLoopback);
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
            boolean allowLoopback
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
        McpUpstreamClient.RpcResult response = post(doc, headers, null, OFFERED, body, id, deadlineNanos, timeoutMs, allowLoopback, false);
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
                doc, headers, sessionId, protocol, notice, null, deadlineNanos, timeoutMs, allowLoopback, false);
        CallResult ackFailed = transport(ack);
        if (ackFailed != null) {
            return ackFailed;
        }
        if (ack.status() != 202 && ack.status() != 200) {
            return CallResult.fail(SessionError.HTTP, ack.status(), ack.message());
        }
        Handle handle = new Handle(fingerprint, sessionId, protocol, doc.version());
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
            boolean allowLoopback
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
                doc, headers, handle.sessionId, handle.protocol, body, id, deadlineNanos, timeoutMs, allowLoopback, true);
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
            Handle updated = new Handle(handle.fingerprint, refreshed, handle.protocol, handle.version);
            handles.replace(key, handle, updated);
            handle = updated;
        }
        return CallResult.message(handle, response.message());
    }

    private void delete(ConchDoc doc, Map<String, String> headers, Handle handle, boolean allowLoopback) {
        handles.values().remove(handle);
        long budget = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        post(doc, headers, handle.sessionId, handle.protocol, null, null, budget, 2_000L, allowLoopback, false);
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
            boolean notifyCancel
    ) {
        byte[] bytes = null;
        if (body != null) {
            try {
                bytes = JSON.writeValueAsBytes(body);
            } catch (java.io.IOException ex) {
                return McpUpstreamClient.RpcResult.connect();
            }
        }
        return client.exchange(new McpUpstreamClient.Post(
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

        Handle(String fingerprint, String sessionId, String protocol, SemVer version) {
            this.fingerprint = fingerprint;
            this.sessionId = sessionId;
            this.protocol = protocol;
            this.version = version;
        }
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
