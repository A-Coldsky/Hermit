package dev.hermit.crab.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevTraceStoreTest {
    @TempDir
    Path temp;

    @Test
    void turningTheSwitchOffDeletesTracesAndKeepsCallLogs() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("off"))) {
            DebugStateStore debug = new DebugStateStore(fixture.dataSource, clock);
            DevTraceStore traces = new DevTraceStore(fixture.dataSource, clock, debug);
            CallLogStore calls = new CallLogStore(fixture.dataSource, clock);
            debug.setEnabled(true, Actors.CLI);
            traces.save(new DevTraceStore.NewTrace(
                    "trace-off", Origin.LOCAL, "local_echo", "alice", "echo",
                    debug.get().expiresAt(), "{\"ok\":true}"));
            calls.append(new CallLogStore.NewCall(
                    "trace-off", Origin.LOCAL, "local_echo", "echo", "alice", "1.0.0",
                    true, null, null, 1, null, CallChannel.CONSOLE), List.of());
            assertThat(traces.count()).isEqualTo(1);

            debug.setEnabled(false, Actors.CLI);
            assertThat(traces.count()).isZero();
            assertThat(calls.findByTraceId("trace-off")).isPresent();
            assertThat(calls.findByTraceId("trace-off").orElseThrow().call().channel()).isEqualTo("CONSOLE");
        }
    }

    @Test
    void expiryDeletesEveryTrace() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("expire"))) {
            DebugStateStore debug = new DebugStateStore(fixture.dataSource, clock);
            DevTraceStore traces = new DevTraceStore(fixture.dataSource, clock, debug);
            debug.setEnabled(true, Actors.CLI);
            traces.save(new DevTraceStore.NewTrace(
                    "trace-expire", Origin.LOCAL, "local_echo", "alice", "echo",
                    debug.get().expiresAt(), "{\"ok\":true}"));
            clock.advance(Duration.ofHours(25));
            debug.expire();
            assertThat(debug.get().enabled()).isFalse();
            assertThat(traces.count()).isZero();
        }
    }

    @Test
    void retentionDeletesOnlyRowsWhoseExpiryHasPassed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("sweep"))) {
            DebugStateStore debug = new DebugStateStore(fixture.dataSource, clock);
            DevTraceStore traces = new DevTraceStore(fixture.dataSource, clock, debug);
            debug.setEnabled(true, Actors.CLI);
            traces.save(new DevTraceStore.NewTrace(
                    "old", Origin.LOCAL, "local_echo", "alice", "echo",
                    clock.instant().minusSeconds(1), "{\"ok\":true}"));
            traces.save(new DevTraceStore.NewTrace(
                    "fresh", Origin.LOCAL, "local_echo", "alice", "echo",
                    debug.get().expiresAt(), "{\"ok\":true}"));
            assertThat(traces.deleteExpired(clock.instant())).isEqualTo(1);
            assertThat(traces.find("old")).isEmpty();
            assertThat(traces.find("fresh")).isPresent();
            assertThat(debug.get().enabled()).isTrue();
        }
    }

    @Test
    void jsonRpcHookStoresOnlyLocalTracesWhileDebugIsOn() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("rpc"))) {
            DebugStateStore debug = new DebugStateStore(fixture.dataSource, clock);
            DevTraceStore traces = new DevTraceStore(fixture.dataSource, clock, debug);
            CallLogStore calls = new CallLogStore(fixture.dataSource, clock);
            DevTraceStore.JsonRpcTrace sample = new DevTraceStore.JsonRpcTrace(
                    "rpc-1", Origin.LOCAL, "local_mcp", "", null, "tools/list", 1, 200, 4L,
                    Map.of("Mcp-Session-Id", "sess-secret-value-77", "token", "super-secret-value"),
                    Map.of("ok", true), null, List.of("super-secret-value"));
            traces.recordJsonRpc(sample);
            assertThat(traces.count()).isZero();

            traces.recordJsonRpc(new DevTraceStore.JsonRpcTrace(
                    "rpc-official", Origin.OFFICIAL, "signed", "alice", "tools_list", "tools/list",
                    1, 200, 1L, Map.of(), Map.of(), null, List.of()));
            debug.setEnabled(true, Actors.CLI);
            traces.recordJsonRpc(sample);
            DevTraceStore.Trace stored = traces.find("rpc-1").orElseThrow();
            assertThat(stored.action()).isEqualTo(DevTraceStore.MCP_REFRESH_ACTION);
            assertThat(stored.origin()).isEqualTo(Origin.LOCAL);
            assertThat(stored.body()).doesNotContain("super-secret-value").doesNotContain("sess-secret-value-77");
            assertThat(stored.body()).contains("***").contains("\"kind\":\"jsonrpc\"");
            assertThat(traces.find("rpc-official")).isEmpty();
            assertThat(calls.findByTraceId("rpc-1")).isEmpty();

            traces.recordJsonRpc(new DevTraceStore.JsonRpcTrace(
                    "rpc-1", Origin.LOCAL, "local_mcp", "", "mcp_refresh", "tools/call", 2, 200, 5L,
                    Map.of("name", "search"), Map.of("token", "other-secret-value"), null,
                    List.of("other-secret-value")));
            assertThat(traces.count()).isEqualTo(1);
            String merged = traces.find("rpc-1").orElseThrow().body();
            assertThat(merged).contains("tools/list").contains("tools/call");
            assertThat(merged).doesNotContain("super-secret-value").doesNotContain("other-secret-value")
                    .doesNotContain("sess-secret-value-77");
            assertThat(calls.findByTraceId("rpc-1")).isEmpty();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
