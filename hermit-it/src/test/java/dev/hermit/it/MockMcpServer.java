package dev.hermit.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * 本机 Streamable HTTP 上游。{@code echo} 只读，{@code create_ticket} 破坏性。
 * {@code message} 为 {@code fail} 时返回 {@code isError}。不读取任何真实凭证。
 */
final class MockMcpServer implements AutoCloseable {
    static final String FAIL_MESSAGE = "fail";
    static final String FAILURE_TEXT = "intentional tool failure";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Javalin app;
    private final int port;
    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final AtomicInteger initializes = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private volatile String sessionId = "";

    MockMcpServer() {
        app = Javalin.create(config -> config.showJavalinBanner = false);
        app.post("/mcp", this::post);
        app.delete("/mcp", ctx -> {
            deletes.incrementAndGet();
            ctx.status(200);
        });
        app.start(0);
        port = app.port();
    }

    int port() {
        return port;
    }

    int initializes() {
        return initializes.get();
    }

    int deletes() {
        return deletes.get();
    }

    List<Hit> toolHits() {
        return hits.stream().filter(hit -> "tools/call".equals(hit.method())).toList();
    }

    private void post(Context ctx) throws Exception {
        String body = ctx.body();
        JsonNode node = body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        String method = node.path("method").asText("");
        if ("notifications/initialized".equals(method)) {
            ctx.status(202);
            return;
        }
        if ("initialize".equals(method)) {
            initializes.incrementAndGet();
            sessionId = "it-session-" + initializes.get();
            ObjectNode result = JSON.createObjectNode();
            result.put("protocolVersion", "2025-11-25");
            result.putObject("capabilities");
            ObjectNode info = result.putObject("serverInfo");
            info.put("name", "mock-mcp");
            info.put("version", "1");
            write(ctx, sessionId, node.path("id"), result);
            return;
        }
        if (!"tools/call".equals(method)) {
            ctx.status(400);
            return;
        }
        String session = ctx.header("Mcp-Session-Id");
        if (session == null || !session.equals(sessionId)) {
            ctx.status(404);
            ctx.contentType("application/json");
            ctx.result("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Session not found\"},\"id\":null}");
            return;
        }
        String tool = node.path("params").path("name").asText("");
        hits.add(hit(ctx, method, tool, body));
        write(ctx, sessionId, node.path("id"), toolResult(tool, node.path("params").path("arguments")));
    }

    private static Hit hit(Context ctx, String method, String tool, String body) {
        String authorization = ctx.header("Authorization");
        boolean forwardedKey = authorization != null && authorization.contains("hk_");
        if (!forwardedKey && ctx.headerMap() != null) {
            for (String value : ctx.headerMap().values()) {
                if (value != null && value.contains("hk_")) {
                    forwardedKey = true;
                    break;
                }
            }
        }
        return new Hit(
                method,
                tool,
                authorization,
                ctx.header("Mcp-Session-Id"),
                ctx.header("MCP-Protocol-Version"),
                ctx.header("Accept"),
                forwardedKey,
                body == null ? "" : body);
    }

    private static ObjectNode toolResult(String tool, JsonNode arguments) {
        String message = arguments.path("message").asText("");
        ObjectNode result = JSON.createObjectNode();
        if (FAIL_MESSAGE.equals(message) && (tool.startsWith("echo"))) {
            result.putArray("content").addObject().put("type", "text").put("text", FAILURE_TEXT);
            result.put("isError", true);
            return result;
        }
        String text = switch (tool) {
            case "echo" -> "Echo: " + message;
            case "echo_v2" -> "Echo v2: " + message;
            case "create_ticket" -> "created";
            default -> "unknown";
        };
        result.putArray("content").addObject().put("type", "text").put("text", text);
        ObjectNode structured = result.putObject("structuredContent");
        structured.put("text", text);
        if ("create_ticket".equals(tool)) {
            structured.put("ticket", "T-1");
        }
        return result;
    }

    private static void write(Context ctx, String session, JsonNode id, ObjectNode result) throws Exception {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id == null || id.isMissingNode() || id.isNull()) {
            envelope.put("id", 1);
        } else {
            envelope.set("id", id);
        }
        envelope.set("result", result);
        ctx.status(200);
        ctx.header("mcp-session-id", session);
        ctx.contentType("application/json; charset=utf-8");
        ctx.result(JSON.writeValueAsString(envelope));
    }

    @Override
    public void close() {
        app.stop();
    }

    record Hit(
            String method,
            String tool,
            String authorization,
            String session,
            String protocol,
            String accept,
            boolean forwardedApiKey,
            String body) {
    }
}
