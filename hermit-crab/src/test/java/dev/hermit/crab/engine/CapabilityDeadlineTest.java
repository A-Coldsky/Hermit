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

class CapabilityDeadlineTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void abortsSlowChunkedBodyAtTheCapabilityDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "capability-deadline-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/slow", CapabilityDeadlineTest::slow);
        server.createContext("/fast", CapabilityDeadlineTest::fast);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            EngineSettings settings = new EngineSettings(Duration.ofMillis(500), Duration.ofMinutes(10), 3);
            try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC())) {
                ConchRegistry registry = new ConchRegistry();
                registry.put(ConchParser.parse(EngineTestSupport.actionConch("deadline_conch", "slow", """
                        - request: { method: GET, path: /slow }
                        """, true)));
                registry.put(ConchParser.parse(EngineTestSupport.actionConch("fast_conch", "fast", """
                        - request: { method: GET, path: /fast }
                        """, true)));
                RuleEngine engine = new RuleEngine(registry, sessions, settings);

                ExecutionResult slow = execute(engine, base, "deadline_conch", "slow");
                assertThat(slow.success()).isFalse();
                assertThat(slow.error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(slow.durationMillis()).isLessThan(1_500);

                ExecutionResult fast = execute(engine, base, "fast_conch", "fast");
                assertThat(fast.success()).as(fast.error() == null ? "" : fast.error().message()).isTrue();
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void slow(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            for (int i = 0; i < 40; i++) {
                body.write('x');
                body.flush();
                Thread.sleep(80);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void fast(HttpExchange exchange) throws IOException {
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        } finally {
            exchange.close();
        }
    }

    private static ExecutionResult execute(RuleEngine engine, String base, String conch, String action) {
        return engine.execute(EngineTestSupport.request(
                Origin.OFFICIAL,
                conch,
                action,
                "alice",
                Map.of(),
                EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base)));
    }
}
