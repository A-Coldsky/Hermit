package dev.hermit.crab.upstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import dev.hermit.crab.gateway.RequestBodies;

/**
 * Streamable HTTP。不跟随重定向，不发 {@code Origin}。
 * 协商只做一次 {@code initialize}，版本由调用方给出；这里只负责收发。
 */
public final class McpUpstreamClient implements AutoCloseable {

    static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    static final int MAX_EVENT_BYTES = 1024 * 1024;
    static final String ACCEPT = "application/json, text/event-stream";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<Boolean> ALLOW_LOOPBACK = new ThreadLocal<>();

    private final CloseableHttpClient http;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ExecutorService replies;
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpUpstreamClient() {
        this.http = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(checkedDns())
                        .setMaxConnPerRoute(8)
                        .setMaxConnTotal(32)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(10))
                                .build())
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .build();
        this.deadlines = newDeadlineExecutor();
        this.replies = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "hermit-mcp-reply");
            thread.setDaemon(true);
            return thread;
        });
    }

    public RpcResult exchange(Post post) {
        if (post.body() != null && post.body().length > RequestBodies.MAX_BYTES) {
            return RpcResult.status(0, "request-too-large");
        }
        long now = System.nanoTime();
        if (now >= post.deadlineNanos()) {
            return RpcResult.deadline();
        }
        long upstreamNanos = post.timeoutMs() == null
                ? Long.MAX_VALUE
                : now + TimeUnit.MILLISECONDS.toNanos(Math.max(1, post.timeoutMs()));
        boolean upstreamFirst = upstreamNanos < post.deadlineNanos();
        long cancelAt = Math.min(upstreamNanos, post.deadlineNanos());
        HttpUriRequestBase request = new HttpUriRequestBase(post.httpMethod(), post.uri());
        long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(cancelAt - now));
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(remainingMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(remainingMs, 15_000)))
                .setRedirectsEnabled(false)
                .build());
        applyHeaders(request, post);
        if (post.body() != null) {
            request.setEntity(new ByteArrayEntity(post.body(), org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
        }
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<String> stop = new AtomicReference<>();
        ScheduledFuture<?> cancellation = deadlines.schedule(() -> {
            if (completed.get()) {
                return;
            }
            String reason = upstreamFirst && System.nanoTime() < post.deadlineNanos() ? "upstream" : "deadline";
            stop.set(reason);
            if ("deadline".equals(reason) && post.notifyCancel() && post.awaitId() != null) {
                sendCancelled(post);
            }
            request.cancel();
        }, Math.max(1, cancelAt - System.nanoTime()), TimeUnit.NANOSECONDS);
        ALLOW_LOOPBACK.set(post.allowLoopback());
        try {
            return http.execute(request, response -> {
                int status = response.getCode();
                String session = header(response, "mcp-session-id");
                String media = mediaType(header(response, "content-type"));
                if (status != 200 && status != 202) {
                    drain(response.getEntity() == null ? null : response.getEntity().getContent(), 64 * 1024);
                    completed.set(true);
                    return new RpcResult(RpcKind.MESSAGE, status, session, null, null);
                }
                if (post.awaitId() == null || response.getEntity() == null) {
                    drain(response.getEntity() == null ? null : response.getEntity().getContent(), 64 * 1024);
                    completed.set(true);
                    return new RpcResult(RpcKind.EMPTY, status, session, null, null);
                }
                try (InputStream in = response.getEntity().getContent()) {
                    if ("text/event-stream".equals(media)) {
                        JsonNode message = readSse(in, post);
                        completed.set(true);
                        return new RpcResult(RpcKind.MESSAGE, status, session, message, null);
                    }
                    byte[] bytes = readLimited(in, MAX_RESPONSE_BYTES);
                    completed.set(true);
                    if (bytes.length == 0) {
                        return new RpcResult(RpcKind.EMPTY, status, session, null, null);
                    }
                    return new RpcResult(RpcKind.MESSAGE, status, session, JSON.readTree(bytes), null);
                }
            });
        } catch (TooLarge ex) {
            return RpcResult.tooLarge();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return new RpcResult(RpcKind.MESSAGE, 200, null, null, "invalid-json");
        } catch (IOException ex) {
            if (causedBy(ex, TooLarge.class)) {
                return RpcResult.tooLarge();
            }
            if ("sse-ended".equals(ex.getMessage()) || causedMessage(ex, "sse-ended")) {
                return new RpcResult(RpcKind.MESSAGE, 200, null, null, "incomplete");
            }
            if ("hermit-rejected-address".equals(ex.getMessage())
                    || (ex.getCause() != null && "hermit-rejected-address".equals(ex.getCause().getMessage()))) {
                return RpcResult.address();
            }
            if ("upstream".equals(stop.get())) {
                return RpcResult.upstreamTimeout();
            }
            if ("deadline".equals(stop.get()) || System.nanoTime() >= post.deadlineNanos()) {
                return RpcResult.deadline();
            }
            return RpcResult.connect();
        } catch (RuntimeException ex) {
            if (causedBy(ex, TooLarge.class)) {
                return RpcResult.tooLarge();
            }
            if ("upstream".equals(stop.get())) {
                return RpcResult.upstreamTimeout();
            }
            if ("deadline".equals(stop.get()) || System.nanoTime() >= post.deadlineNanos()) {
                return RpcResult.deadline();
            }
            throw ex;
        } finally {
            completed.set(true);
            cancellation.cancel(false);
            ALLOW_LOOPBACK.remove();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        replies.shutdownNow();
        deadlines.shutdownNow();
        try {
            http.close();
        } catch (IOException ignored) {
            // 关闭失败不影响进程退出。
        }
    }

    private JsonNode readSse(InputStream in, Post post) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean sawData = false;
        int total = 0;
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            for (int i = 0; i < read; i++) {
                total++;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new TooLarge();
                }
                int value = buf[i] & 0xff;
                if (value != '\n') {
                    line.write(value);
                    if (line.size() > MAX_EVENT_BYTES + 64) {
                        throw new TooLarge();
                    }
                    continue;
                }
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                if (!text.isEmpty()) {
                    if (text.charAt(0) == ':') {
                        continue;
                    }
                    int colon = text.indexOf(':');
                    String field = colon < 0 ? text : text.substring(0, colon);
                    String raw = colon < 0 ? "" : text.substring(colon + 1);
                    if (raw.startsWith(" ")) {
                        raw = raw.substring(1);
                    }
                    if ("data".equals(field)) {
                        if (sawData) {
                            data.write('\n');
                        }
                        byte[] encoded = raw.getBytes(StandardCharsets.UTF_8);
                        if (data.size() + encoded.length > MAX_EVENT_BYTES) {
                            throw new TooLarge();
                        }
                        data.write(encoded);
                        sawData = true;
                    }
                    continue;
                }
                if (!sawData) {
                    data.reset();
                    continue;
                }
                byte[] payload = data.toByteArray();
                data.reset();
                sawData = false;
                if (payload.length == 0) {
                    continue;
                }
                JsonNode node = JSON.readTree(payload);
                if (serverRequest(node)) {
                    replyMethodNotFound(post, node.get("id"));
                    continue;
                }
                if (matches(node, post.awaitId())) {
                    return node;
                }
            }
        }
        throw new IOException("sse-ended");
    }

    private void replyMethodNotFound(Post post, JsonNode id) {
        if (id == null || id.isNull()) {
            return;
        }
        replies.execute(() -> {
            ObjectNode body = JSON.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.set("id", id.deepCopy());
            ObjectNode error = body.putObject("error");
            error.put("code", -32601);
            error.put("message", "Method not found");
            byte[] bytes;
            try {
                bytes = JSON.writeValueAsBytes(body);
            } catch (IOException ex) {
                return;
            }
            Post reply = new Post(
                    "POST",
                    post.uri(),
                    post.headers(),
                    post.sessionId(),
                    post.protocol(),
                    bytes,
                    null,
                    Math.min(post.deadlineNanos(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5)),
                    5_000L,
                    post.allowLoopback(),
                    false);
            try {
                exchange(reply);
            } catch (RuntimeException ignored) {
                // 反向请求答不上时，原调用会以工具错误或超时结束。
            }
        });
    }

    private void sendCancelled(Post post) {
        if (post.sessionId() == null || post.awaitId() == null) {
            return;
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "notifications/cancelled");
        ObjectNode params = body.putObject("params");
        params.put("requestId", post.awaitId());
        params.put("reason", "deadline");
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (IOException ex) {
            return;
        }
        Post notice = new Post(
                "POST",
                post.uri(),
                post.headers(),
                post.sessionId(),
                post.protocol(),
                bytes,
                null,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                2_000L,
                post.allowLoopback(),
                false);
        try {
            exchange(notice);
        } catch (RuntimeException ignored) {
            // 取消通知失败时仍然结束这次读取。
        }
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean causedMessage(Throwable error, String message) {
        Throwable current = error;
        while (current != null) {
            if (message.equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean serverRequest(JsonNode node) {
        return node != null
                && node.isObject()
                && node.hasNonNull("method")
                && node.has("id")
                && !node.get("id").isNull()
                && !node.has("result")
                && !node.has("error");
    }

    private static boolean matches(JsonNode node, long awaitId) {
        if (node == null || !node.isObject() || !node.has("id") || node.get("id").isNull()) {
            return false;
        }
        if (!node.has("result") && !node.has("error")) {
            return false;
        }
        JsonNode id = node.get("id");
        return id.isIntegralNumber() && id.longValue() == awaitId;
    }

    private static void applyHeaders(HttpUriRequestBase request, Post post) {
        request.setHeader("Accept", ACCEPT);
        if (post.body() != null) {
            request.setHeader("Content-Type", "application/json");
        }
        if (post.sessionId() != null && !post.sessionId().isBlank()) {
            request.setHeader("Mcp-Session-Id", post.sessionId());
        }
        if (post.protocol() != null && !post.protocol().isBlank()) {
            request.setHeader("MCP-Protocol-Version", post.protocol());
        }
        if (post.headers() != null) {
            for (Map.Entry<String, String> entry : post.headers().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    request.setHeader(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static String header(org.apache.hc.core5.http.HttpResponse response, String name) {
        for (Header header : response.getHeaders()) {
            if (header.getName() != null && header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    static String mediaType(String header) {
        if (header == null) {
            return "";
        }
        int semi = header.indexOf(';');
        String value = semi < 0 ? header : header.substring(0, semi);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            if (out.size() + read > max) {
                throw new TooLarge();
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static void drain(InputStream in, int max) throws IOException {
        if (in == null) {
            return;
        }
        try (InputStream stream = in) {
            byte[] buf = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buf)) != -1) {
                total += read;
                if (total > max) {
                    return;
                }
            }
        }
    }

    private static DnsResolver checkedDns() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] all = InetAddress.getAllByName(host);
                boolean allow = Boolean.TRUE.equals(ALLOW_LOOPBACK.get());
                for (InetAddress address : all) {
                    if (McpAddressGuard.rejected(address, allow)) {
                        throw new UnknownHostException("hermit-rejected-address");
                    }
                }
                return all;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static ScheduledThreadPoolExecutor newDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "hermit-mcp-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public record Post(
            String httpMethod,
            URI uri,
            Map<String, String> headers,
            String sessionId,
            String protocol,
            byte[] body,
            Long awaitId,
            long deadlineNanos,
            Long timeoutMs,
            boolean allowLoopback,
            boolean notifyCancel
    ) {
    }

    public enum RpcKind {
        MESSAGE,
        EMPTY,
        DEADLINE,
        UPSTREAM_TIMEOUT,
        TOO_LARGE,
        CONNECT,
        ADDRESS
    }

    public record RpcResult(RpcKind kind, int status, String sessionId, JsonNode message, String detail) {
        static RpcResult deadline() {
            return new RpcResult(RpcKind.DEADLINE, 0, null, null, null);
        }

        static RpcResult upstreamTimeout() {
            return new RpcResult(RpcKind.UPSTREAM_TIMEOUT, 0, null, null, null);
        }

        static RpcResult tooLarge() {
            return new RpcResult(RpcKind.TOO_LARGE, 0, null, null, null);
        }

        static RpcResult connect() {
            return new RpcResult(RpcKind.CONNECT, 0, null, null, null);
        }

        static RpcResult address() {
            return new RpcResult(RpcKind.ADDRESS, 0, null, null, null);
        }

        static RpcResult status(int status, String detail) {
            return new RpcResult(RpcKind.MESSAGE, status, null, null, detail);
        }
    }

    private static final class TooLarge extends IOException {
    }
}
