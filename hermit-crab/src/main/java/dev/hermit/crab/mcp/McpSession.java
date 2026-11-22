package dev.hermit.crab.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.spec.McpSchema;

final class McpSession {
    static final String WAKE = " ";
    static final int MAX_SINKS = 16;
    static final int SINK_QUEUE_CAPACITY = 256;
    /** Package-visible so a test can observe a confirmation timeout without waiting a minute. */
    static volatile long elicitWaitMillis = 60_000L;

    final String id;
    final String apiKeyId;
    final String protocolVersion;
    final McpSchema.ClientCapabilities capabilities;
    final AtomicBoolean closed = new AtomicBoolean();
    private long lastUsed;
    private long sequence;
    private final Object stateLock = new Object();
    private final List<Sink> sinks = new ArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger serverIds = new AtomicInteger();

    McpSession(String id, String apiKeyId, String protocolVersion, McpSchema.ClientCapabilities capabilities) {
        this.id = id;
        this.apiKeyId = apiKeyId;
        this.protocolVersion = protocolVersion == null ? "" : protocolVersion;
        this.capabilities = capabilities == null
                ? new McpSchema.ClientCapabilities(null, null, null, null)
                : capabilities;
    }

    synchronized void touch(long nowMillis, long useSequence) {
        lastUsed = nowMillis;
        sequence = useSequence;
    }

    synchronized long lastUsed() {
        return lastUsed;
    }

    synchronized long sequence() {
        return sequence;
    }

    boolean supportsElicitation() {
        return capabilities.elicitation() != null;
    }

    boolean streaming() {
        synchronized (stateLock) {
            return !closed.get() && hasOpenSinkLocked();
        }
    }

    Sink openSink() {
        synchronized (stateLock) {
            if (closed.get() || sinks.size() >= MAX_SINKS) {
                return null;
            }
            Sink sink = new Sink();
            sinks.add(sink);
            return sink;
        }
    }

    void closeSink(Sink sink) {
        if (sink == null) {
            return;
        }
        List<CompletableFuture<JsonNode>> cancelled = List.of();
        synchronized (stateLock) {
            sink.wake();
            sinks.remove(sink);
            if (!hasOpenSinkLocked() && !pending.isEmpty()) {
                cancelled = List.copyOf(pending.values());
                pending.clear();
            }
        }
        for (CompletableFuture<JsonNode> future : cancelled) {
            future.complete(null);
        }
    }

    List<Sink> copySinks() {
        synchronized (stateLock) {
            return List.copyOf(sinks);
        }
    }

    void wake() {
        List<CompletableFuture<JsonNode>> cancelled;
        synchronized (stateLock) {
            closed.set(true);
            for (Sink sink : sinks) {
                sink.wake();
            }
            sinks.clear();
            cancelled = List.copyOf(pending.values());
            pending.clear();
        }
        for (CompletableFuture<JsonNode> future : cancelled) {
            future.complete(null);
        }
    }

    /** Empty means the client has no open event stream, so the question was not delivered. */
    JsonNode elicit(McpSchema.ElicitFormRequest request) throws TimeoutException, InterruptedException {
        String requestId = "e" + serverIds.incrementAndGet();
        ObjectNode message = McpJson.mapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", requestId);
        message.put("method", McpSchema.METHOD_ELICITATION_CREATE);
        message.set("params", McpJson.mapper().valueToTree(request));
        String payload = McpJson.write(message);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        synchronized (stateLock) {
            if (closed.get() || !hasOpenSinkLocked()) {
                return null;
            }
            pending.put(requestId, future);
            if (!publishLocked(payload)) {
                pending.remove(requestId, future);
                return null;
            }
        }
        try {
            return future.get(elicitWaitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            synchronized (stateLock) {
                pending.remove(requestId, future);
            }
            throw ex;
        } catch (java.util.concurrent.ExecutionException ex) {
            return null;
        } finally {
            synchronized (stateLock) {
                pending.remove(requestId, future);
            }
        }
    }

    void complete(JsonNode id, JsonNode result, boolean failed) {
        CompletableFuture<JsonNode> future;
        synchronized (stateLock) {
            future = pending.remove(idText(id));
        }
        if (future != null) {
            future.complete(failed ? null : result);
        }
    }

    private boolean hasOpenSinkLocked() {
        for (Sink sink : sinks) {
            if (!sink.closed.get()) {
                return true;
            }
        }
        return false;
    }

    private boolean publishLocked(String json) {
        boolean delivered = false;
        for (Sink sink : sinks) {
            if (!sink.closed.get() && sink.queue.offer(json)) {
                delivered = true;
            }
        }
        return delivered;
    }

    static String idText(JsonNode id) {
        if (id == null || id.isNull()) {
            return "";
        }
        if (id.isIntegralNumber()) {
            return id.bigIntegerValue().toString();
        }
        return id.asText();
    }

    static final class Sink {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(SINK_QUEUE_CAPACITY);
        final AtomicBoolean closed = new AtomicBoolean();

        void wake() {
            closed.set(true);
            if (!queue.offer(WAKE)) {
                queue.poll();
                queue.offer(WAKE);
            }
        }
    }
}
