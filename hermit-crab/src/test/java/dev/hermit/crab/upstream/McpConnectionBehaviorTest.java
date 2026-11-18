package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.McpAuth;
import dev.hermit.crab.http.HttpMessageLimits;
import dev.hermit.crab.upstream.McpOAuthClient.Resolved;
import dev.hermit.crab.upstream.McpOAuthClient.Target;
import dev.hermit.crab.upstream.McpUpstreamClient.Post;
import dev.hermit.crab.upstream.McpUpstreamClient.RpcKind;
import dev.hermit.crab.upstream.McpUpstreamClient.RpcResult;

/**
 * MCP 转发与 OAuth 的回环连接。不访问外网。
 */
class McpConnectionBehaviorTest {

    @Test
    @Timeout(15)
    void getRetriesOnceAndPostDoesNot() throws Exception {
        try (DropServer server = new DropServer()) {
            try (McpUpstreamClient client = new McpUpstreamClient()) {
                RpcResult result = client.exchange(post("GET", server.base() + "/t", null));
                assertThat(result.kind()).isEqualTo(RpcKind.EMPTY);
                assertThat(result.status()).isEqualTo(200);
            }
            server.join();
            assertThat(server.requests).hasSize(2);
            assertThat(server.requests.get(0)).startsWith("GET ");
        }

        try (DropServer server = DropServer.alwaysDrop()) {
            try (McpUpstreamClient client = new McpUpstreamClient()) {
                RpcResult result = client.exchange(post("POST", server.base() + "/t", "{}".getBytes(StandardCharsets.UTF_8)));
                assertThat(result.kind()).isEqualTo(RpcKind.CONNECT);
                assertThat(result.detail()).contains("上游连接失败");
            }
            server.join();
            assertThat(server.requests).hasSize(1);
            assertThat(server.requests.get(0)).startsWith("POST ");
        }
    }

    @Test
    @Timeout(15)
    void headerLimitsApplyToMcpAndOauth() throws Exception {
        String wide = fat(9_000, 120, "{}");
        try (AnswerServer server = new AnswerServer(wide)) {
            try (McpUpstreamClient client = new McpUpstreamClient()) {
                RpcResult result = client.exchange(post("GET", server.base() + "/t", null));
                assertThat(result.kind()).isNotEqualTo(RpcKind.CONNECT);
                assertThat(result.status()).isEqualTo(200);
            }
        }

        try (AnswerServer server = new AnswerServer(fat(2_000, 1, "{}"))) {
            try (McpUpstreamClient client = new McpUpstreamClient(new HttpMessageLimits(1024, 500))) {
                RpcResult result = client.exchange(post("GET", server.base() + "/t", null));
                assertThat(result.kind()).isEqualTo(RpcKind.CONNECT);
                assertThat(result.detail()).contains("上游连接失败");
            }
        }

        try (AnswerServer server = new AnswerServer(wide)) {
            try (McpOAuthClient client = new McpOAuthClient()) {
                Resolved resolved = client.resolve(target(server.base()), auth(), deadline());
                assertThat(resolved.ok()).isFalse();
                assertThat(resolved.message()).isEqualTo(McpOAuth.DISCOVERY_FAILED);
            }
        }

        try (AnswerServer server = new AnswerServer(fat(2_000, 1, "{}"))) {
            try (McpOAuthClient client = new McpOAuthClient(new HttpMessageLimits(1024, 500))) {
                Resolved resolved = client.resolve(target(server.base()), auth(), deadline());
                assertThat(resolved.ok()).isFalse();
                assertThat(resolved.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
                assertThat(resolved.message()).contains("上游连接失败");
                assertThat(resolved.message()).isNotEqualTo(McpOAuth.DISCOVERY_FAILED);
            }
        }
    }

    @Test
    @Timeout(15)
    void oauthDiscoveryDoesNotRetryADroppedGet() throws Exception {
        try (DropServer server = DropServer.alwaysDrop()) {
            String upstream = server.base() + "/mcp";
            int expected = 1 + McpOAuthClient.protectedResourceUrls(upstream).size();
            try (McpOAuthClient client = new McpOAuthClient()) {
                Resolved resolved = client.resolve(new Target(upstream, List.of("127.0.0.1")), auth(), deadline());
                assertThat(resolved.ok()).isFalse();
                assertThat(resolved.message()).contains("上游连接失败");
            }
            server.join();
            assertThat(server.requests).hasSize(expected);
        }
    }

    private static Post post(String method, String url, byte[] body) {
        return new Post(
                method,
                URI.create(url),
                Map.of(),
                null,
                null,
                body,
                null,
                deadline(),
                null,
                true,
                false);
    }

    private static Target target(String base) {
        return new Target(base + "/mcp", List.of("127.0.0.1"));
    }

    private static McpAuth auth() {
        return new McpAuth("oauth", List.of(), "lab-client", null, null);
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }

    private static String fat(int valueBytes, int extraHeaders, String body) {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 200 OK\r\n");
        response.append("X-Long: ").append("a".repeat(valueBytes)).append("\r\n");
        for (int i = 0; i < extraHeaders; i++) {
            response.append("X-N-").append(i).append(": v\r\n");
        }
        response.append("Content-Length: ").append(body.length()).append("\r\n");
        response.append("Connection: close\r\n\r\n");
        response.append(body);
        return response.toString();
    }

    private static String readRequest(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int matched = 0;
        while (raw.size() < 1_000_000) {
            int value = in.read();
            if (value < 0) {
                break;
            }
            raw.write(value);
            if (value == "\r\n\r\n".charAt(matched)) {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        String text = raw.toString(StandardCharsets.ISO_8859_1);
        int line = text.indexOf("\r\n");
        return line < 0 ? text : text.substring(0, line);
    }

    /**
     * 第一条连接读完请求后直接关掉。GET 的重试会再来一条，并得到 200。
     * {@link #alwaysDrop()} 则每条连接都关掉。
     */
    private static final class DropServer implements AutoCloseable {
        final List<String> requests = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final ServerSocket server;
        private final AtomicReference<Exception> error = new AtomicReference<>();
        private final Thread thread;

        DropServer() throws IOException {
            this(false);
        }

        static DropServer alwaysDrop() throws IOException {
            return new DropServer(true);
        }

        private DropServer(boolean always) throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            thread = new Thread(() -> {
                try {
                    if (always) {
                        while (!Thread.currentThread().isInterrupted()) {
                            try {
                                dropOne();
                            } catch (SocketTimeoutException ex) {
                                return;
                            }
                        }
                        return;
                    }
                    dropOne();
                    try (Socket retried = accept(3_000)) {
                        requests.add(readRequest(retried));
                        OutputStream out = retried.getOutputStream();
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    }
                } catch (IOException ex) {
                    if (!Thread.currentThread().isInterrupted()) {
                        error.set(ex);
                    }
                }
            }, "mcp-drop");
            thread.setDaemon(true);
            thread.start();
        }

        private void dropOne() throws IOException {
            try (Socket socket = accept(3_000)) {
                requests.add(readRequest(socket));
            }
        }

        private Socket accept(int timeoutMs) throws IOException {
            server.setSoTimeout(timeoutMs);
            return server.accept();
        }

        String base() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        void join() throws Exception {
            thread.join(8_000);
            if (error.get() != null) {
                throw error.get();
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }

    private static final class AnswerServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicInteger served = new AtomicInteger();

        AnswerServer(String response) throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            byte[] bytes = response.getBytes(StandardCharsets.ISO_8859_1);
            thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        try (Socket socket = server.accept()) {
                            readRequest(socket);
                            OutputStream out = socket.getOutputStream();
                            out.write(bytes);
                            out.flush();
                            served.incrementAndGet();
                        }
                    } catch (IOException ex) {
                        return;
                    }
                }
            }, "mcp-answer");
            thread.setDaemon(true);
            thread.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }
}
