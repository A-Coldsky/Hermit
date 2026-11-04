package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.store.Origin;

/**
 * 能力 deadline 60 秒。上游 40 秒后才返回时，没写 timeout_ms 应成功，
 * 不能在 15 秒变成「上游读取超时」。写了 timeout_ms: 15000 时仍在 15 秒失败。
 */
class StepTimeoutTest {

    private static final long UPSTREAM_DELAY_MS = 40_000;

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void defaultReadTimeoutWaitsOutAFortySecondUpstream() throws Exception {
        ExecutionResult result = execute(null);
        assertThat(result.success()).as(result.error() == null ? "" : result.error().message()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.durationMillis()).isBetween(35_000L, 55_000L);
    }

    @Test
    @Timeout(value = 50, unit = TimeUnit.SECONDS)
    void explicitTimeoutMsStillFailsAtFifteenSeconds() throws Exception {
        ExecutionResult result = execute(15_000);
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isNull();
        assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(result.error().message()).isEqualTo(HttpCalls.STEP_TIMEOUT);
        assertThat(result.error().failedStepType()).isEqualTo("request");
        assertThat(result.durationMillis()).isBetween(12_000L, 22_000L);
    }

    private static ExecutionResult execute(Integer timeoutMs) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "step-timeout-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/slow", StepTimeoutTest::slow);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String steps = timeoutMs == null
                    ? """
                        - request: { method: GET, path: /slow }
                        """
                    : """
                        - request: { method: GET, path: /slow, timeout_ms: %d }
                        """.formatted(timeoutMs);
            EngineSettings settings = new EngineSettings(Duration.ofSeconds(60), Duration.ofMinutes(10), 3);
            try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC())) {
                ConchRegistry registry = new ConchRegistry();
                registry.put(ConchParser.parse(EngineTestSupport.actionConch("slow_conch", "slow", steps, true)));
                RuleEngine engine = new RuleEngine(registry, sessions, settings);
                return engine.execute(EngineTestSupport.request(
                        Origin.OFFICIAL,
                        "slow_conch",
                        "slow",
                        "alice",
                        Map.of(),
                        EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base)));
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void slow(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(UPSTREAM_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        } catch (IOException ignored) {
            // 调用方已经按步骤超时断开。
        } finally {
            exchange.close();
        }
    }
}
