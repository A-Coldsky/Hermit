package dev.hermit.crab.store;

import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.StepLog;
import dev.hermit.crab.engine.StoredCookie;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.MasterKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeStoreTest {
    @TempDir
    Path temp;

    @Test
    void callLogsExpireAndDoNotKeepPayloads() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp)) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
            CallLogStore logs = new CallLogStore(fixture.dataSource, clock);
            String longMessage = "x".repeat(600);
            logs.append(new CallLogStore.NewCall(
                    "trace-1", Origin.OFFICIAL, "legacy_oa", "list_todo", "alice", "1.0.0",
                    false, "UPSTREAM_ERROR", longMessage, 12, "key-1"),
                    List.of(new StepLog(0, "request", 4, "GET", "/todo/list.aspx", 200, 1)));
            CallLogStore.CallDetail detail = logs.findByTraceId("trace-1").orElseThrow();
            assertThat(detail.call().errorMessage()).hasSize(CallLogStore.ERROR_MESSAGE_LIMIT);
            assertThat(detail.steps()).hasSize(1);
            clock.advance(Duration.ofDays(31));
            assertThat(logs.purgeBefore(clock.instant().minus(Duration.ofDays(30)))).isEqualTo(1);
            assertThat(logs.findByTraceId("trace-1")).isEmpty();
        }
    }

    @Test
    void callLogActionFilterAcceptsQualifiedNamesAndRejectsConflicts() {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("calls"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
            CallLogStore logs = new CallLogStore(fixture.dataSource, clock);
            logs.append(call("trace-todo", "legacy_oa", "list_todo"), List.of());
            logs.append(call("trace-note", "legacy_oa", "save_note"), List.of());
            logs.append(call("trace-other", "other_oa", "list_todo"), List.of());
            logs.append(call("trace-dotted", "pkg", "do.thing"), List.of());

            assertThat(traceIds(logs.list(null, "list_todo", 20)))
                    .containsExactlyInAnyOrder("trace-todo", "trace-other");
            assertThat(traceIds(logs.list(null, "legacy_oa.list_todo", 20))).containsExactly("trace-todo");
            assertThat(traceIds(logs.list("legacy_oa", "legacy_oa.list_todo", 20))).containsExactly("trace-todo");
            assertThat(traceIds(logs.list("legacy_oa", "list_todo", 20))).containsExactly("trace-todo");
            assertThat(traceIds(logs.list(null, "pkg.do.thing", 20))).containsExactly("trace-dotted");
            assertThat(traceIds(logs.list(" ", "  ", 20))).hasSize(4);

            String secret = "sid-secret-cookie-91";
            assertThatThrownBy(() -> logs.list("other_oa", "legacy_oa.list_todo", 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("action")
                    .hasMessageContaining("conch")
                    .hasMessageNotContaining("legacy_oa")
                    .hasMessageNotContaining("other_oa");
            assertThatThrownBy(() -> logs.list("kept", secret + ".list_todo", 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(secret);
            assertThatThrownBy(() -> logs.list(null, "." + secret, 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(secret);
            assertThatThrownBy(() -> logs.list(null, secret + ".", 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(secret);
        }
    }

    @Test
    void idempotencyExpiresAndDropsUndecryptableRows() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("idem"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
            CredentialCipher cipher = cipher(fixture.paths);
            IdempotencyStore store = new IdempotencyStore(fixture.dataSource, cipher, clock);
            store.save(Origin.OFFICIAL, "legacy_oa", "alice", "save_note", "k1", "{\"success\":true}");
            assertThat(store.findFresh(Origin.OFFICIAL, "legacy_oa", "alice", "save_note", "k1"))
                    .contains("{\"success\":true}");
            clock.advance(Duration.ofHours(24));
            assertThat(store.findFresh(Origin.OFFICIAL, "legacy_oa", "alice", "save_note", "k1")).isEmpty();
            clock.advance(Duration.ofHours(-24));
            store.save(Origin.OFFICIAL, "legacy_oa", "alice", "save_note", "k2", "{\"success\":true}");
            try (var connection = fixture.dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "UPDATE idempotency SET response_json = ? WHERE \"key\" = ?")) {
                statement.setBytes(1, new byte[] {1, 2, 3, 4});
                statement.setString(2, "k2");
                statement.executeUpdate();
            }
            assertThat(store.findFresh(Origin.OFFICIAL, "legacy_oa", "alice", "save_note", "k2")).isEmpty();
        }
    }

    @Test
    void corruptSessionRowIsDeleted() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("session"))) {
            CredentialCipher cipher = cipher(fixture.paths);
            EncryptedSessionPersistence sessions = new EncryptedSessionPersistence(fixture.dataSource, cipher);
            SessionKey key = new SessionKey(Origin.OFFICIAL, "legacy_oa", "alice");
            sessions.save(key, List.of(new StoredCookie(
                    "ASP.NET_SessionId", "sid-alice-1", "127.0.0.1", "/", null, false, true)));
            assertThat(sessions.load(key)).hasSize(1);
            try (var connection = fixture.dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "UPDATE sessions SET ciphertext = ? WHERE account = ?")) {
                statement.setBytes(1, new byte[] {9, 9, 9});
                statement.setString(2, "alice");
                statement.executeUpdate();
            }
            assertThat(sessions.load(key)).isEmpty();
            assertThat(sessions.load(key)).isEmpty();
        }
    }

    @Test
    void debugFlagTurnsOffAfterTwentyFourHours() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("debug"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
            DebugStateStore debug = new DebugStateStore(fixture.dataSource, clock);
            assertThat(debug.setEnabled(true, "console").enabled()).isTrue();
            clock.advance(Duration.ofHours(24));
            assertThat(debug.get().enabled()).isFalse();
            assertThat(debug.get().expiresAt()).isNull();
        }
    }

    private static CallLogStore.NewCall call(String traceId, String conch, String action) {
        return new CallLogStore.NewCall(
                traceId, Origin.OFFICIAL, conch, action, "alice", "1.0.0", true, null, null, 4, null);
    }

    private static java.util.List<String> traceIds(java.util.List<CallLogStore.CallSummary> rows) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (CallLogStore.CallSummary row : rows) {
            ids.add(row.traceId());
        }
        return ids;
    }

    private static CredentialCipher cipher(CrabPaths paths) {
        byte[] key = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
        try {
            return new CredentialCipher(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
