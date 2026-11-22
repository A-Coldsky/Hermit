package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.store.Origin;
import io.javalin.Javalin;

class McpUpstreamSessionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(15)
    void nextIdIsPositiveMonotonicAndUniqueUnderConcurrency() throws Exception {
        try (SessionManager accounts = new SessionManager();
                McpUpstreamSessions sessions = new McpUpstreamSessions(new McpUpstreamClient(), accounts, 4)) {
            int threads = 8;
            int perThread = 500;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            Set<Long> seen = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        long prev = 0L;
                        for (int j = 0; j < perThread; j++) {
                            long id = sessions.nextId();
                            assertThat(id).isGreaterThan(1L).isGreaterThan(prev);
                            assertThat(seen.add(id)).isTrue();
                            prev = id;
                        }
                        return null;
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(seen).hasSize(threads * perThread);
        }
    }

    @Test
    @Timeout(15)
    void staleGenerationDoesNotDeleteOrOverwriteNewerSharedHandle() throws Exception {
        AtomicInteger seq = new AtomicInteger();
        List<String> deletedSessions = new CopyOnWriteArrayList<>();
        Javalin upstream = Javalin.create(config -> config.showJavalinBanner = false);
        upstream.post("/mcp", ctx -> {
            JsonNode node = JSON.readTree(ctx.body());
            String method = node.path("method").asText("");
            if ("notifications/initialized".equals(method)) {
                ctx.status(202);
                return;
            }
            if ("initialize".equals(method)) {
                String sessionId = "sess-" + seq.incrementAndGet();
                ctx.status(200);
                ctx.header("mcp-session-id", sessionId);
                ctx.contentType("application/json; charset=utf-8");
                ctx.result("""
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}}
                        """.formatted(node.path("id").asText("1")));
                return;
            }
            if ("tools/call".equals(method)) {
                String sessionId = ctx.header("Mcp-Session-Id");
                ctx.status(200);
                ctx.header("mcp-session-id", sessionId);
                ctx.contentType("application/json; charset=utf-8");
                ctx.result("""
                        {"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"ok"}],"isError":false}}
                        """.formatted(node.path("id").asText("2")));
                return;
            }
            ctx.status(400);
        });
        upstream.delete("/mcp", ctx -> {
            String sid = ctx.header("Mcp-Session-Id");
            if (sid != null) {
                deletedSessions.add(sid);
            }
            ctx.status(204);
        });
        upstream.start("127.0.0.1", 0);
        try (SessionManager accounts = new SessionManager();
                McpUpstreamSessions sessions = new McpUpstreamSessions(new McpUpstreamClient(), accounts, 4)) {
            int port = upstream.port();
            SessionKey key = new SessionKey(Origin.LOCAL, "vendor_crm", "alice");
            ConchDoc v1 = ConchParser.parse(yaml(port, "1.0.0"));
            ConchDoc v2 = ConchParser.parse(yaml(port, "1.1.0"));
            ConchDoc v3 = ConchParser.parse(yaml(port, "1.2.0"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

            // 新代数（generation = 10）建立共享会话 sess-1。
            McpUpstreamSessions.CallResult fresh = sessions.call(
                    key, v2, "token-new", 10L, Map.of("Authorization", "Bearer token-new"),
                    "ping", Map.of(), deadline, null, true, null);
            assertThat(fresh.error()).isNull();
            McpUpstreamSessions.Handle shared = sessions.currentHandle(key);
            assertThat(shared).isNotNull();
            String sharedId = shared.sessionId;
            assertThat(sharedId).isEqualTo("sess-1");

            // 过期代数（generation = 5）带着旧指纹到达：不得删除或覆盖 sess-1，仅使用并销毁自己的临时会话。
            McpUpstreamSessions.CallResult stale = sessions.call(
                    key, v1, "token-old", 5L, Map.of("Authorization", "Bearer token-old"),
                    "ping", Map.of(), deadline, null, true, null);
            assertThat(stale.error()).isNull();
            assertThat(sessions.currentHandle(key)).isSameAs(shared);
            assertThat(sessions.currentHandle(key).sessionId).isEqualTo(sharedId);
            assertThat(deletedSessions).doesNotContain(sharedId).containsExactly("sess-2");

            // 更新的代数（generation = 11）到达时，正常删除旧共享会话 sess-1 并替换为 sess-3。
            McpUpstreamSessions.CallResult newer = sessions.call(
                    key, v3, "token-v3", 11L, Map.of("Authorization", "Bearer token-v3"),
                    "ping", Map.of(), deadline, null, true, null);
            assertThat(newer.error()).isNull();
            assertThat(sessions.currentHandle(key).sessionId).isEqualTo("sess-3");
            assertThat(deletedSessions).contains(sharedId);
        } finally {
            upstream.stop();
        }
    }

    @Test
    @Timeout(10)
    void lanesCleanUpAfterReleaseAndPreserveCapAcrossForget() throws Exception {
        try (SessionManager accounts = new SessionManager();
                McpUpstreamSessions sessions = new McpUpstreamSessions(new McpUpstreamClient(), accounts, 2)) {
            SessionKey key = new SessionKey(Origin.LOCAL, "vendor_crm", "alice");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            McpUpstreamSessions.Permit p1 = sessions.acquire(key, deadline);
            McpUpstreamSessions.Permit p2 = sessions.acquire(key, deadline);
            assertThat(p1).isNotNull();
            assertThat(p2).isNotNull();
            assertThat(sessions.laneCount()).isEqualTo(1);

            // 使用期间触发 forget：不能把在用通道移出 map，并发上限保持生效。
            sessions.forget(key);
            assertThat(sessions.laneCount()).isEqualTo(1);
            McpUpstreamSessions.Permit blocked = sessions.acquire(key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60));
            assertThat(blocked).isNull();
            assertThat(sessions.laneCount()).isEqualTo(1);

            p1.close();
            assertThat(sessions.laneCount()).isEqualTo(1);
            p2.close();
            assertThat(sessions.laneCount()).isZero();
            // 重复 close 幂等，不会把引用计数减成负数或破坏状态。
            p2.close();
            assertThat(sessions.laneCount()).isZero();
        }
    }

    private static String yaml(int port, String version) {
        return """
                conch: vendor_crm
                version: %s
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统
                description: 测试会话代数。
                credentials: {}
                upstream:
                  url: http://127.0.0.1:%d/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  ping:
                    tool: ping
                    title: 探测
                    description: 探测。
                    mcp: {read_only: true}
                    input_schema: {type: object}
                """.formatted(version, port);
    }
}
