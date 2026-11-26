package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.testing.MutableClock;

class LocalConchHistoryTest {

    @TempDir
    java.nio.file.Path temp;

    @Test
    void historyKeepsTwoPreviousVersionsAndSurvivesDelete() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("keep"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            local.importBytes(LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            for (int i = 1; i <= 11; i++) {
                clock.advance(Duration.ofMillis(1));
                local.importBytes(
                        LocalConchBehaviorTest.ping("hist_ping", "1.0." + i).getBytes(StandardCharsets.UTF_8),
                        Actors.CLI,
                        true);
            }
            List<LocalConches.HistoryEntry> history = local.history("hist_ping");
            assertThat(history).hasSize(LocalConches.HISTORY_KEEP);
            assertThat(LocalConches.HISTORY_KEEP).isEqualTo(2);
            assertThat(history.get(0).version()).isEqualTo("1.0.10");
            assertThat(history.get(history.size() - 1).version()).isEqualTo("1.0.9");
            assertThat(history).extracting(LocalConches.HistoryEntry::version).doesNotContain("1.0.0");
            assertThat(history.get(0).id()).startsWith("20261118").doesNotContain(".yaml");
            assertThat(history.get(0).savedAt()).isNotNull();

            local.delete("hist_ping", Actors.CLI);
            assertThat(local.history("hist_ping")).hasSize(LocalConches.HISTORY_KEEP);
            LocalConches.HistoryEntry oldest = history.get(history.size() - 1);
            LocalConches.ImportResult restored = local.restore("hist_ping", oldest.id(), Actors.CLI);
            assertThat(restored.replaced()).isFalse();
            assertThat(restored.version()).isEqualTo(oldest.version());
            assertThat(local.packages()).extracting(pkg -> pkg.version()).containsExactly(oldest.version());
            assertThat(auditDetail(crab, AuditKind.LOCAL_CONCH_RESTORED.name())).contains("\"boundary\":\"widened\"");
        }
    }

    @Test
    void startupPrunesExtraHistoryFromOlderInstalls() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("seeded"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            local.importBytes(LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            java.nio.file.Path dir = crab.paths.localConches().resolve(".history").resolve("hist_ping");
            Files.createDirectories(dir);
            for (int i = 1; i <= 5; i++) {
                Files.writeString(
                        dir.resolve("2026111800000000" + i + "-0." + i + ".0.yaml"),
                        LocalConchBehaviorTest.ping("hist_ping", "0." + i + ".0"));
            }
            local.loadStartup();
            assertThat(local.history("hist_ping")).extracting(LocalConches.HistoryEntry::version)
                    .containsExactly("0.5.0", "0.4.0");
            try (var left = Files.list(dir)) {
                assertThat(left.count()).isEqualTo(2);
            }

            local.importBytes(
                    LocalConchBehaviorTest.ping("hist_ping", "1.0.1").getBytes(StandardCharsets.UTF_8),
                    Actors.CLI,
                    true);
            List<LocalConches.HistoryEntry> history = local.history("hist_ping");
            assertThat(history).extracting(LocalConches.HistoryEntry::version)
                    .containsExactly("1.0.0", "0.5.0");
            LocalConches.HistoryEntry oldest = history.get(history.size() - 1);
            LocalConches.ImportResult restored = local.restore("hist_ping", oldest.id(), Actors.CONSOLE);
            assertThat(restored.version()).isEqualTo("0.5.0");
            assertThat(local.packages()).extracting(pkg -> pkg.version()).containsExactly("0.5.0");
            assertThat(local.history("hist_ping")).extracting(LocalConches.HistoryEntry::version)
                    .containsExactly("1.0.1", "1.0.0");
        }
    }

    @Test
    void startupDropsALegacyCopyOfTheCurrentBeforePruning() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("legacy-dup"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            String current = LocalConchBehaviorTest.ping("hist_ping", "1.0.0");
            local.importBytes(current.getBytes(StandardCharsets.UTF_8), Actors.CLI);
            java.nio.file.Path dir = crab.paths.localConches().resolve(".history").resolve("hist_ping");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("20261122000000003-1.0.0.yaml"), current);
            Files.writeString(dir.resolve("20261122000000002-0.2.0.yaml"), LocalConchBehaviorTest.ping("hist_ping", "0.2.0"));
            Files.writeString(dir.resolve("20261122000000001-0.1.0.yaml"), LocalConchBehaviorTest.ping("hist_ping", "0.1.0"));
            local.loadStartup();
            assertThat(local.history("hist_ping")).extracting(LocalConches.HistoryEntry::version)
                    .containsExactly("0.2.0", "0.1.0");
        }
    }

    @Test
    void sameContentAndRestoreDoNotTakeAHistorySlot() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("dedupe"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            byte[] first = LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            local.importBytes(first, Actors.CLI);
            clock.advance(Duration.ofMillis(1));
            LocalConches.ImportResult same = local.importBytes(first, Actors.CLI, true);
            assertThat(same.replaced()).isTrue();
            assertThat(local.history("hist_ping")).isEmpty();

            clock.advance(Duration.ofMillis(1));
            local.importBytes(
                    LocalConchBehaviorTest.ping("hist_ping", "1.0.1").getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            List<LocalConches.HistoryEntry> history = local.history("hist_ping");
            assertThat(history).extracting(LocalConches.HistoryEntry::version).containsExactly("1.0.0");

            clock.advance(Duration.ofMillis(1));
            local.restore("hist_ping", history.get(0).id(), Actors.CONSOLE);
            assertThat(local.packages()).extracting(pkg -> pkg.version()).containsExactly("1.0.0");
            assertThat(local.history("hist_ping")).extracting(LocalConches.HistoryEntry::version)
                    .containsExactly("1.0.1");
        }
    }

    @Test
    void restoringTheNewestBackupKeepsTheOlderOne() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("newest"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            local.importBytes(LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            for (int i = 1; i <= 2; i++) {
                clock.advance(Duration.ofMillis(1));
                local.importBytes(
                        LocalConchBehaviorTest.ping("hist_ping", "1.0." + i).getBytes(StandardCharsets.UTF_8),
                        Actors.CLI,
                        true);
            }
            List<LocalConches.HistoryEntry> history = local.history("hist_ping");
            assertThat(history).extracting(LocalConches.HistoryEntry::version).containsExactly("1.0.1", "1.0.0");
            clock.advance(Duration.ofMillis(1));
            local.restore("hist_ping", history.get(0).id(), Actors.CONSOLE);
            assertThat(local.history("hist_ping")).extracting(LocalConches.HistoryEntry::version)
                    .containsExactly("1.0.2", "1.0.0");
        }
    }

    @Test
    void identicalSaveDoesNotTouchALinkedHistoryRoot() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("linked"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            byte[] raw = LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            local.importBytes(raw, Actors.CLI);
            java.nio.file.Path outside = temp.resolve("outside").resolve("hist_ping");
            Files.createDirectories(outside);
            Files.write(outside.resolve("keep.yaml"), raw);
            Files.createSymbolicLink(crab.paths.localConches().resolve(".history"), outside.getParent());
            local.importBytes(raw, Actors.CLI, true);
            assertThat(outside.resolve("keep.yaml")).exists();
        }
    }

    @Test
    void sameMillisecondBackupGetsASuffix() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("collide"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            String first = LocalConchBehaviorTest.ping("hist_ping", "1.0.0");
            local.importBytes(first.getBytes(StandardCharsets.UTF_8), Actors.CLI);
            local.importBytes(first.replace("本地探测", "第二份").getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            local.importBytes(first.replace("本地探测", "第三份").getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(local.history("hist_ping")).extracting(LocalConches.HistoryEntry::id)
                    .anySatisfy(id -> assertThat(id).endsWith("-2"));
        }
    }

    @Test
    void pendingExpiresAndAChangedDiskDropsIt() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        try (OpenCrab crab = OpenCrab.open(temp.resolve("pending"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, () -> false, clock);
            byte[] original = LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            local.importBytes(original, Actors.CLI);
            byte[] widened = LocalConchBehaviorTest.ping("hist_ping", "1.1.0")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/")
                    .getBytes(StandardCharsets.UTF_8);
            LocalConches.PendingOverwrite staged = local.stagePending(widened, Actors.MCP, "agent-key").orElseThrow();
            assertThat(staged.addedDestinations()).containsExactly("http://127.0.0.1:10");
            assertThat(auditCount(crab, AuditKind.LOCAL_CONCH_REPLACE_PENDING.name())).isEqualTo(1);

            clock.advance(Duration.ofMinutes(30));
            assertThat(local.pending()).isEmpty();
            assertThatThrownBy(() -> local.confirmPending("hist_ping", staged.sha256(), Actors.CONSOLE))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage(LocalConchException.NO_PENDING);
            assertThat(auditCount(crab, AuditKind.LOCAL_CONCH_REPLACE_PENDING.name())).isEqualTo(1);

            LocalConches.PendingOverwrite again = local.stagePending(widened, Actors.MCP, "agent-key").orElseThrow();
            byte[] newer = LocalConchBehaviorTest.ping("hist_ping", "1.2.0")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:11/")
                    .getBytes(StandardCharsets.UTF_8);
            local.stagePending(newer, Actors.MCP, "later-key");
            assertThatThrownBy(() -> local.confirmPending("hist_ping", again.sha256(), Actors.CONSOLE))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage(LocalConchException.PENDING_CHANGED)
                    .satisfies(ex -> assertThat(((LocalConchException) ex).code()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(local.pending()).extracting(LocalConches.PendingOverwrite::submittedBy).containsExactly("later-key");

            Files.write(crab.paths.localConches().resolve("hist_ping.yaml"), widened);
            assertThatThrownBy(() -> local.confirmPending("hist_ping", local.pending().get(0).sha256(), Actors.CONSOLE))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage(LocalConchException.STALE);
            assertThat(local.pending()).isEmpty();
            assertThat(Files.readAllBytes(crab.paths.localConches().resolve("hist_ping.yaml"))).isEqualTo(widened);
        }
    }

    /** 登录步骤会把账号密码发到认证地址，所以本地包新增认证地址和改 base_url 一样要确认。 */
    @Test
    void addingAnAuthOriginWidensTheBoundary() throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp.resolve("auth-origin"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource);
            local.importBytes(LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            String withOrigin = LocalConchBehaviorTest.ping("hist_ping", "1.1.0")
                    .replace("min_crab_version: 1.0.0", "min_crab_version: 1.11.0")
                    .replace("credentials: {}", """
                            credentials: {}
                            auth:
                              origins:
                                - http://localhost:19
                              steps:
                                - request: { method: GET, url: "http://localhost:19/login" }
                            """.stripTrailing());
            LocalConches.PendingOverwrite staged =
                    local.stagePending(withOrigin.getBytes(StandardCharsets.UTF_8), Actors.MCP, "agent-key").orElseThrow();
            assertThat(staged.addedDestinations()).containsExactly("http://localhost:19");
            assertThat(local.packages()).extracting(pkg -> pkg.version()).containsExactly("1.0.0");

            local.confirmPending("hist_ping", staged.sha256(), Actors.CONSOLE);
            assertThat(local.packages()).extracting(pkg -> pkg.version()).containsExactly("1.1.0");

            // 认证地址没变的新版本不用再确认。
            String same = withOrigin.replace("version: 1.1.0", "version: 1.2.0");
            assertThat(local.stagePending(same.getBytes(StandardCharsets.UTF_8), Actors.MCP, "agent-key")).isEmpty();
        }
    }

    @Test
    void dotfilesAreNotLoaded() throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp.resolve("dot"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource);
            Files.createDirectories(crab.paths.localConches());
            Files.writeString(crab.paths.localConches().resolve(".hidden.yaml"), LocalConchBehaviorTest.ping("hidden_ping", "1.0.0"));
            local.loadStartup();
            assertThat(local.packages()).isEmpty();
            assertThat(local.history("hidden_ping")).isEmpty();
        }
    }

    @Test
    void replaceAuditsTheBoundaryAndBackup() throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp.resolve("audit"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource);
            local.importBytes(LocalConchBehaviorTest.ping("hist_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            LocalConches.ImportResult replaced = local.importBytes(
                    LocalConchBehaviorTest.ping("hist_ping", "1.0.1").getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(replaced.previousVersion()).isEqualTo("1.0.0");
            assertThat(replaced.backupId()).isNotBlank();
            String detail = auditDetail(crab, AuditKind.LOCAL_CONCH_REPLACED.name());
            assertThat(detail).contains("\"boundary\":\"same\"")
                    .contains("\"previousVersion\":\"1.0.0\"")
                    .contains("\"backupId\":\"" + replaced.backupId() + "\"");
            assertThat(auditDetail(crab, AuditKind.LOCAL_CONCH_IMPORTED.name()))
                    .isEqualTo("{\"conch\":\"hist_ping\",\"version\":\"1.0.0\"}");
        }
    }

    private static int auditCount(OpenCrab crab, String kind) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM config_audit WHERE kind = ?")) {
            query.setString(1, kind);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static String auditDetail(OpenCrab crab, String kind) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT detail FROM config_audit WHERE kind = ? ORDER BY id DESC")) {
            query.setString(1, kind);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
