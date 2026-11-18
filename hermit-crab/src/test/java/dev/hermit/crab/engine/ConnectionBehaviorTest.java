package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.http.HttpMessageLimits;
import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.store.Origin;

/**
 * 回环模拟站。不访问外网，也不使用真实证书。
 */
class ConnectionBehaviorTest {

    @Test
    @Timeout(15)
    void idempotentMethodsRetryOnceWhenThePeerDropsTheResponse() throws Exception {
        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            try (Script script = new Script(server -> {
                try (Socket dropped = server.accept(3_000)) {
                    server.readRequest(dropped);
                }
                try (Socket retried = server.accept(3_000)) {
                    server.readRequest(retried);
                    Script.write(retried, fixed("ok"));
                }
            })) {
                try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                    HttpResult result = execute(client.client, script.base(), method, 5_000, Duration.ofSeconds(5));
                    assertThat(result.status()).isEqualTo(200);
                    if (!"HEAD".equals(method)) {
                        assertThat(result.body()).isEqualTo("ok");
                    }
                    assertThat(result.warnings()).isEmpty();
                }
                script.join();
                assertThat(script.requests).hasSize(2);
                assertThat(script.requests.get(0)).startsWith(method + " ");
                assertThat(script.requests.get(1)).startsWith(method + " ");
            }
        }
    }

    @Test
    @Timeout(15)
    void connectionResetIsRetriedOnceForGet() throws Exception {
        try (Script script = new Script(server -> {
            Socket dropped = server.accept(3_000);
            try {
                server.readRequest(dropped);
                dropped.setSoLinger(true, 0);
            } finally {
                dropped.close();
            }
            try (Socket retried = server.accept(3_000)) {
                server.readRequest(retried);
                Script.write(retried, fixed("again"));
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofSeconds(5));
                assertThat(result.body()).isEqualTo("again");
            }
            script.join();
            assertThat(script.requests).hasSize(2);
        }
    }

    @Test
    @Timeout(15)
    void postIsNotRetriedAndAGetStopsAfterOneRetry() throws Exception {
        try (Script script = new Script(server -> {
            try (Socket dropped = server.accept(3_000)) {
                server.readRequest(dropped);
            }
            try {
                server.accept(800).close();
                server.requests.add("UNEXPECTED");
            } catch (SocketTimeoutException expected) {
                // POST 没有第二条连接。
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                RuleProblem problem = failure(client.client, script.base(), "POST", 5_000, Duration.ofSeconds(5));
                assertThat(problem.summary).contains("上游连接失败");
            }
            script.join();
            assertThat(script.requests).containsExactly("POST /t HTTP/1.1");
        }

        try (Script script = new Script(server -> {
            for (int i = 0; i < 2; i++) {
                try (Socket dropped = server.accept(3_000)) {
                    server.readRequest(dropped);
                }
            }
            try {
                server.accept(800).close();
                server.requests.add("UNEXPECTED");
            } catch (SocketTimeoutException expected) {
                // 第三次不会来。
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                RuleProblem problem = failure(client.client, script.base(), "GET", 5_000, Duration.ofSeconds(5));
                assertThat(problem.summary).contains("上游连接失败");
            }
            script.join();
            assertThat(script.requests).hasSize(2);
        }
    }

    @Test
    @Timeout(15)
    void statusAndStepTimeoutAreNotRetried() throws Exception {
        try (Script script = new Script(server -> {
            try (Socket socket = server.accept(3_000)) {
                server.readRequest(socket);
                Script.write(socket, """
                        HTTP/1.1 503 Service Unavailable\r
                        Content-Length: 0\r
                        Connection: close\r
                        \r
                        """);
            }
            try {
                server.accept(800).close();
                server.requests.add("UNEXPECTED");
            } catch (SocketTimeoutException expected) {
                // 503 不重试。
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                RuleProblem problem = failure(client.client, script.base(), "GET", 5_000, Duration.ofSeconds(5));
                assertThat(problem.summary).contains("503");
            }
            script.join();
            assertThat(script.requests).hasSize(1);
        }

        try (Script script = new Script(server -> {
            try (Socket socket = server.accept(3_000)) {
                server.readRequest(socket);
                Thread.sleep(3_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                long started = System.nanoTime();
                RuleProblem problem = failure(client.client, script.base(), "GET", 400, Duration.ofSeconds(5));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(problem.summary).isEqualTo(HttpCalls.STEP_TIMEOUT);
                assertThat(elapsed).isBetween(250L, 1_500L);
            }
            script.join();
            assertThat(script.requests).hasSize(1);
        }
    }

    @Test
    @Timeout(20)
    void pooledConnectionIsCheckedAfterTwoSecondsOfInactivity() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (Script script = new Script(server -> {
            Socket first = server.accept(3_000);
            try {
                server.readRequest(first);
                Script.write(first, kept("one"));
                sent.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                first.close();
                closed.countDown();
            }
            try (Socket second = server.accept(5_000)) {
                server.readRequest(second);
                Script.write(second, fixed("two"));
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                HttpResult first = execute(client.client, script.base(), "POST", 5_000, Duration.ofSeconds(5));
                assertThat(first.body()).isEqualTo("one");
                assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(2_500);
                HttpResult second = execute(client.client, script.base(), "POST", 5_000, Duration.ofSeconds(5));
                assertThat(second.body()).isEqualTo("two");
            }
            script.join();
            assertThat(script.requests).hasSize(2);
            assertThat(script.requests).allMatch(line -> line.startsWith("POST "));
        }
    }

    @Test
    @Timeout(15)
    void postDoesNotRetryAConnectionClosedBeforeTheInactivityWindow() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (Script script = new Script(server -> {
            Socket first = server.accept(3_000);
            try {
                server.readRequest(first);
                Script.write(first, kept("one"));
                sent.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                first.close();
                closed.countDown();
            }
            try {
                server.accept(1_000).close();
                server.requests.add("UNEXPECTED");
            } catch (SocketTimeoutException expected) {
                // 2 秒内不复查，POST 也不重试，所以没有新连接。
            }
        })) {
            try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
                HttpResult first = execute(client.client, script.base(), "POST", 5_000, Duration.ofSeconds(5));
                assertThat(first.body()).isEqualTo("one");
                release.countDown();
                assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                RuleProblem problem = failure(client.client, script.base(), "POST", 5_000, Duration.ofSeconds(5));
                assertThat(problem.summary).contains("上游连接失败");
            }
            script.join();
            assertThat(script.requests).containsExactly("POST /t HTTP/1.1");
        }
    }

    @Test
    @Timeout(20)
    void headerLimitsFollowTheConfiguredLineAndCount() throws Exception {
        try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
            try (Script script = new Script(server -> reply(server, fat(9_000, 120, "hello")))) {
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofSeconds(5));
                assertThat(result.status()).isEqualTo(200);
                assertThat(result.body()).isEqualTo("hello");
                script.join();
            }
        }

        EngineSettings tight = new EngineSettings(
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                3,
                Duration.ofSeconds(15),
                Duration.ofSeconds(5),
                new HttpMessageLimits(1024, 8));
        try (ClientFixture client = ClientFixture.open(tight)) {
            try (Script script = new Script(server -> reply(server, fat(2_000, 1, "hello")))) {
                RuleProblem problem = failure(client.client, script.base(), "GET", 5_000, Duration.ofSeconds(5));
                assertThat(problem.summary).contains("上游连接失败");
                assertThat(problem.transport.get("kind")).isEqualTo("OTHER");
                assertThat(problem.summary).doesNotContain("aaaa");
                script.join();
            }
            try (Script script = new Script(server -> reply(server, fat(8, 12, "hello")))) {
                RuleProblem problem = failure(client.client, script.base(), "GET", 5_000, Duration.ofSeconds(5));
                assertThat(problem.summary).contains("上游连接失败");
                assertThat(problem.transport.get("kind")).isEqualTo("OTHER");
                script.join();
            }
        }
    }

    @Test
    @Timeout(20)
    void indefiniteBodyEndsOnIdleAndDefiniteBodiesDoNot() throws Exception {
        try (ClientFixture client = ClientFixture.open(EngineSettings.defaults())) {
            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, indefinite("hello"));
                    hold(socket);
                }
            })) {
                long started = System.nanoTime();
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofMillis(400));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(result.body()).isEqualTo("hello");
                assertThat(result.warnings()).hasSize(1);
                assertThat(result.warnings().get(0).code()).isEqualTo(OutboundHttp.BODY_ENDED_BY_IDLE);
                assertThat(result.warnings().get(0).message()).isEqualTo(OutboundHttp.BODY_ENDED_BY_IDLE_MESSAGE);
                assertThat(result.view()).doesNotContainKey("warnings");
                assertThat(elapsed).isBetween(250L, 2_000L);
                script.join();
            }

            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n");
                    Thread.sleep(800);
                    Script.write(socket, "hello");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            })) {
                long started = System.nanoTime();
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofMillis(400));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(result.body()).isEqualTo("hello");
                assertThat(result.warnings()).isEmpty();
                assertThat(elapsed).isGreaterThan(700);
                script.join();
            }

            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
                    Thread.sleep(800);
                    Script.write(socket, "5\r\nhello\r\n0\r\n\r\n");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            })) {
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofMillis(400));
                assertThat(result.body()).isEqualTo("hello");
                assertThat(result.warnings()).isEmpty();
                script.join();
            }

            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, indefinite("hello"));
                    Thread.sleep(300);
                    Script.write(socket, "world");
                    hold(socket);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            })) {
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofMillis(400));
                assertThat(result.body()).isEqualTo("helloworld");
                assertThat(result.warnings()).extracting(TraceWarning::code)
                        .containsExactly(OutboundHttp.BODY_ENDED_BY_IDLE);
                script.join();
            }

            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nhello");
                }
            })) {
                long started = System.nanoTime();
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofMillis(1_000));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(result.body()).isEqualTo("hello");
                assertThat(result.warnings()).isEmpty();
                assertThat(elapsed).isLessThan(800);
                script.join();
            }

            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, indefinite(""));
                    hold(socket);
                }
            })) {
                HttpResult result = execute(client.client, script.base(), "GET", 5_000, Duration.ofMillis(300));
                assertThat(result.body()).isEmpty();
                assertThat(result.warnings()).extracting(TraceWarning::code)
                        .containsExactly(OutboundHttp.BODY_ENDED_BY_IDLE);
                script.join();
            }

            try (Script script = new Script(server -> {
                try (Socket socket = server.accept(3_000)) {
                    server.readRequest(socket);
                    Script.write(socket, indefinite(""));
                    hold(socket);
                }
            })) {
                RuleProblem problem = failure(client.client, script.base(), "GET", 400, Duration.ofSeconds(5));
                assertThat(problem.summary).isEqualTo(HttpCalls.STEP_TIMEOUT);
                script.join();
            }
        }
    }

    @Test
    @Timeout(20)
    void engineUsesConfiguredBodyIdleAndRedactsTheResponseNotTheWarning() throws Exception {
        String secret = EngineTestSupport.PASSWORD;
        CountDownLatch held = new CountDownLatch(1);
        try (Script script = new Script(server -> {
            try (Socket socket = server.accept(3_000)) {
                server.readRequest(socket);
                Script.write(socket, indefinite(secret));
                held.countDown();
                hold(socket);
            }
        })) {
            EngineSettings settings = new EngineSettings(
                    Duration.ofSeconds(8),
                    Duration.ofMinutes(10),
                    3,
                    Duration.ofSeconds(15),
                    Duration.ofMillis(400),
                    HttpMessageLimits.defaults());
            MemoryTrace traces = new MemoryTrace();
            try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC())) {
                ConchRegistry registry = new ConchRegistry();
                registry.put(ConchParser.parse(EngineTestSupport.actionConch("idle_conch", "read", """
                        - request: { method: GET, path: /t }
                        """, true)));
                RuleEngine engine = new RuleEngine(registry, sessions, settings);
                engine.bindDevTraceSink(traces);
                long started = System.nanoTime();
                ExecutionResult result = engine.execute(EngineTestSupport.request(
                        Origin.LOCAL,
                        "idle_conch",
                        "read",
                        "alice",
                        Map.of(),
                        EngineTestSupport.account("alice", secret, script.base())));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(result.success()).as(result.error() == null ? "" : result.error().message()).isTrue();
                assertThat(elapsed).isBetween(250L, 2_000L);
                assertThat(traces.body).isNotNull();
                assertThat(traces.body).doesNotContain(secret);
                JsonNode warning = ApiJson.tree(traces.body).path("steps").get(0).path("warnings").get(0);
                assertThat(warning.path("code").asText()).isEqualTo(OutboundHttp.BODY_ENDED_BY_IDLE);
                assertThat(warning.path("message").asText()).isEqualTo(OutboundHttp.BODY_ENDED_BY_IDLE_MESSAGE);
                assertThat(ApiJson.tree(traces.body).path("steps").get(0).path("responseBody").asText()).isEqualTo("***");
            }
            script.join();
        }
    }

    @Test
    @Timeout(20)
    void connectTimeoutUsesTheEngineSettingWhenTheHandshakeCannotComplete() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            List<Socket> fillers = new ArrayList<>();
            try {
                // 内核在 accept 队列满之后不再完成新的握手。队列满的那一次连接自己会超时，
                // 不能把它当成测试失败。
                for (int i = 0; i < 128; i++) {
                    Socket filler = new Socket();
                    try {
                        filler.connect(server.getLocalSocketAddress(), 200);
                        fillers.add(filler);
                    } catch (SocketTimeoutException ex) {
                        filler.close();
                        break;
                    }
                }
                assertThat(fillers).as("accept queue stayed open for every filler").isNotEmpty();
                EngineSettings settings = new EngineSettings(
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        3,
                        Duration.ofMillis(400),
                        Duration.ofSeconds(5),
                        HttpMessageLimits.defaults());
                try (ClientFixture client = ClientFixture.open(settings)) {
                    long started = System.nanoTime();
                    RuleProblem problem = failure(
                            client.client,
                            "http://127.0.0.1:" + server.getLocalPort(),
                            "GET",
                            10_000,
                            Duration.ofSeconds(5));
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertThat(problem.summary).as("elapsed %s ms, summary %s", elapsed, problem.summary)
                            .isEqualTo(HttpCalls.STEP_TIMEOUT);
                    assertThat(elapsed).isBetween(250L, 2_000L);
                }
            } finally {
                for (Socket filler : fillers) {
                    filler.close();
                }
            }
        }
    }

    private static void reply(Script server, String response) throws IOException {
        try (Socket socket = server.accept(3_000)) {
            server.readRequest(socket);
            Script.write(socket, response);
        }
    }

    private static String fixed(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body;
    }

    private static String kept(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                + "\r\nConnection: keep-alive\r\n\r\n" + body;
    }

    private static String indefinite(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: keep-alive\r\n\r\n" + body;
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

    private static void hold(Socket socket) throws IOException {
        try {
            socket.getInputStream().read();
        } catch (IOException ignored) {
            // 客户端截断或步骤超时后会关掉连接。
        }
    }

    private static HttpResult execute(
            CloseableHttpClient client,
            String base,
            String method,
            int timeoutMs,
            Duration idle
    ) {
        return HttpCalls.execute(
                spec(method, timeoutMs), client, () -> { }, java.net.URI.create(base), null,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(20), idle);
    }

    private static RuleProblem failure(
            CloseableHttpClient client,
            String base,
            String method,
            int timeoutMs,
            Duration idle
    ) {
        try {
            execute(client, base, method, timeoutMs, idle);
        } catch (RuleProblem problem) {
            return problem;
        }
        throw new AssertionError("expected a RuleProblem");
    }

    private static Map<String, Object> spec(String method, int timeoutMs) {
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("method", method);
        spec.put("path", "/t");
        spec.put("timeout_ms", timeoutMs);
        return spec;
    }

    private static final class ClientFixture implements AutoCloseable {
        private final SessionManager sessions;
        private final SessionKey key;
        final CloseableHttpClient client;

        private ClientFixture(SessionManager sessions, SessionKey key, CloseableHttpClient client) {
            this.sessions = sessions;
            this.key = key;
            this.client = client;
        }

        static ClientFixture open(EngineSettings settings) {
            SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC());
            SessionKey key = new SessionKey(Origin.OFFICIAL, "conn", "a");
            boolean locked;
            try {
                locked = sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            } catch (InterruptedException ex) {
                sessions.close();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("lock", ex);
            }
            if (!locked) {
                sessions.close();
                throw new IllegalStateException("lock");
            }
            return new ClientFixture(sessions, key, sessions.client(key));
        }

        @Override
        public void close() {
            sessions.unlock(key);
            sessions.close();
        }
    }

    private static final class MemoryTrace implements DevTraceSink {
        private String body;

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            return new DevTraceDraft(Instant.parse("2026-11-18T00:00:00Z"));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            this.body = body;
        }
    }

    @FunctionalInterface
    private interface ScriptBody {
        void accept(Script script) throws Exception;
    }

    private static final class Script implements AutoCloseable {
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final ServerSocket server;
        private final AtomicReference<Exception> error = new AtomicReference<>();
        private final Thread thread;

        Script(ScriptBody body) throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            thread = new Thread(() -> {
                try {
                    body.accept(this);
                } catch (Exception ex) {
                    if (!(ex instanceof InterruptedException) && !Thread.currentThread().isInterrupted()) {
                        error.set(ex);
                    }
                }
            }, "scripted-http");
            thread.setDaemon(true);
            thread.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        Socket accept(int timeoutMs) throws IOException {
            server.setSoTimeout(timeoutMs);
            return server.accept();
        }

        String readRequest(Socket socket) throws IOException {
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
            String request = line < 0 ? text : text.substring(0, line);
            requests.add(request);
            return request;
        }

        static void write(Socket socket, String text) throws IOException {
            OutputStream out = socket.getOutputStream();
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        void join() throws Exception {
            thread.join(12_000);
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
}
