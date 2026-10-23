package dev.hermit.crab.mcp;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

    final String id;
    final String apiKeyId;
    final String protocolVersion;
    final McpSchema.ClientCapabilities capabilities;
    final AtomicBoolean closed = new AtomicBoolean();
    private long lastUsed;
    private long sequence;
    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
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
        return hasOpenSink();
    }

    Sink openSink() {
        Sink sink = new Sink();
        sinks.add(sink);
        return sink;
    }

    void closeSink(Sink sink) {
        sink.closed.set(true);
        sinks.remove(sink);
        sink.queue.offer(WAKE);
    }

    java.util.List<Sink> copySinks() {
        return java.util.List.copyOf(sinks);
    }

    void wake() {
        closed.set(true);
        for (Sink sink : sinks) {
            sink.queue.offer(WAKE);
        }
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.complete(null);
        }
        pending.clear();
    }

    /** Empty means the client has no open event stream, so the question was not delivered. */
    JsonNode elicit(McpSchema.ElicitFormRequest request) throws TimeoutException, InterruptedException {
        if (!hasOpenSink()) {
            return null;
        }
        String requestId = "e" + serverIds.incrementAndGet();
        ObjectNode message = McpJson.mapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", requestId);
        message.put("method", McpSchema.METHOD_ELICITATION_CREATE);
        message.set("params", McpJson.mapper().valueToTree(request));
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, future);
        publish(McpJson.write(message));
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            pending.remove(requestId, future);
            throw ex;
        } catch (java.util.concurrent.ExecutionException ex) {
            return null;
        } finally {
            pending.remove(requestId, future);
        }
    }

    void complete(JsonNode id, JsonNode result, boolean failed) {
        CompletableFuture<JsonNode> future = pending.remove(idText(id));
        if (future != null) {
            future.complete(failed ? null : result);
        }
    }

    private boolean hasOpenSink() {
        for (Sink sink : sinks) {
            if (!sink.closed.get()) {
                return true;
            }
        }
        return false;
    }

    private void publish(String json) {
        for (Sink sink : sinks) {
            if (!sink.closed.get()) {
                sink.queue.offer(json);
            }
        }
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
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final AtomicBoolean closed = new AtomicBoolean();
    }
}
