package dev.hermit.crab.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;

import io.modelcontextprotocol.spec.McpSchema;

class McpSessionTest {

    @Test
    void capsOpenSinksAndQueueCapacity() {
        McpSession session = newSession();
        List<McpSession.Sink> opened = new ArrayList<>();
        for (int i = 0; i < McpSession.MAX_SINKS; i++) {
            McpSession.Sink sink = session.openSink();
            assertThat(sink).as("sink #" + i).isNotNull();
            opened.add(sink);
        }
        assertThat(session.openSink()).isNull();

        session.closeSink(opened.remove(0));
        McpSession.Sink replacement = session.openSink();
        assertThat(replacement).isNotNull();
        opened.add(replacement);
        assertThat(session.openSink()).isNull();

        // 单个 sink 队列有界，满载后不会无限增长，wake 仍可正常唤醒并标记关闭。
        McpSession.Sink target = opened.get(0);
        for (int i = 0; i < McpSession.SINK_QUEUE_CAPACITY + 50; i++) {
            target.queue.offer("{\"n\":" + i + "}");
        }
        assertThat(target.queue).hasSize(McpSession.SINK_QUEUE_CAPACITY);
        target.wake();
        assertThat(target.closed.get()).isTrue();
        assertThat(target.queue).contains(McpSession.WAKE);
    }

    @Test
    @Timeout(10)
    void closingLastSinkUnblocksPendingElicitationImmediately() throws Exception {
        McpSession session = newSession();
        McpSession.Sink first = session.openSink();
        McpSession.Sink second = session.openSink();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<JsonNode> result = pool.submit(() -> {
                started.countDown();
                return session.elicit(confirmRequest());
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            String delivered = first.queue.poll(2, TimeUnit.SECONDS);
            assertThat(delivered).isNotNull().contains("elicitation/create");

            // 关掉第一条流时还有第二条流，挂起的确认继续等待。
            session.closeSink(first);
            Thread.sleep(100);
            assertThat(result.isDone()).isFalse();

            // 关掉最后一条流后，挂起的确认立刻解除阻塞并返回 null，不空等 60 秒。
            long closedAt = System.nanoTime();
            session.closeSink(second);
            assertThat(result.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAt)).isLessThan(2_000L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void wakeUnblocksPendingElicitationAndPreventsFurtherStreams() throws Exception {
        McpSession session = newSession();
        McpSession.Sink sink = session.openSink();
        assertThat(sink).isNotNull();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> result = pool.submit(() -> session.elicit(confirmRequest()));
            assertThat(sink.queue.poll(2, TimeUnit.SECONDS)).isNotNull().contains("elicitation/create");

            long wokeAt = System.nanoTime();
            session.wake();
            assertThat(result.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wokeAt)).isLessThan(2_000L);

            assertThat(session.closed.get()).isTrue();
            assertThat(session.streaming()).isFalse();
            assertThat(session.openSink()).isNull();
            assertThat(session.elicit(confirmRequest())).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void fullSinkQueueReturnsNullFromElicitImmediately() throws Exception {
        McpSession session = newSession();
        McpSession.Sink sink = session.openSink();
        assertThat(sink).isNotNull();
        for (int i = 0; i < McpSession.SINK_QUEUE_CAPACITY; i++) {
            assertThat(sink.queue.offer("{\"fill\":" + i + "}")).isTrue();
        }
        long started = System.nanoTime();
        assertThat(session.elicit(confirmRequest())).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000L);
    }

    private static McpSession newSession() {
        return new McpSession(
                "sess-1",
                "key-1",
                "2025-03-26",
                McpSchema.ClientCapabilities.builder().elicitation().build());
    }

    private static McpSchema.ElicitFormRequest confirmRequest() {
        return McpSchema.ElicitFormRequest.builder(
                "确认执行？",
                Map.of("type", "object", "properties", Map.of("confirm", Map.of("type", "boolean"))))
                .build();
    }
}
