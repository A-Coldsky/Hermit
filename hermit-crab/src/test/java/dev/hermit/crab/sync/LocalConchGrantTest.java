package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.DebugStateStore;
import dev.hermit.crab.testing.MutableClock;

class LocalConchGrantTest {

    @TempDir
    Path temp;

    @Test
    void onlyConsoleCanRememberAndOnlyThatConchAutoApplies() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T03:04:05Z"));
        AtomicBoolean debug = new AtomicBoolean(true);
        try (OpenCrab crab = OpenCrab.open(temp.resolve("grant"))) {
            LocalConches local = new LocalConches(
                    crab.paths, new ConchRegistry(), crab.dataSource, debug::get, clock);
            byte[] original = yaml("kept_ping", "1.0.0", "9");
            local.importBytes(original, Actors.CLI);
            local.importBytes(yaml("other_ping", "1.0.0", "9"), Actors.CLI);
            byte[] widened = yaml("kept_ping", "1.1.0", "10");
            LocalConches.PendingOverwrite staged = local.stagePending(widened, Actors.MCP, "agent-key").orElseThrow();

            assertThatThrownBy(() -> local.confirmPending("kept_ping", staged.sha256(), Actors.MCP, true))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage(LocalConchException.GRANT_CONSOLE_ONLY)
                    .satisfies(error -> {
                        LocalConchException ex = (LocalConchException) error;
                        assertThat(ex.status()).isEqualTo(403);
                        assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN);
                    });
            assertThatThrownBy(() -> local.confirmPending("kept_ping", staged.sha256(), Actors.CLI, true))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage(LocalConchException.GRANT_CONSOLE_ONLY);
            assertThat(Files.readAllBytes(file(crab, "kept_ping"))).isEqualTo(original);
            assertThat(local.pending()).hasSize(1);
            assertThat(local.grants()).isEmpty();

            LocalConches.ImportResult confirmed = local.confirmPending(
                    "kept_ping", staged.sha256(), Actors.CONSOLE, true);
            assertThat(confirmed.version()).isEqualTo("1.1.0");
            assertThat(local.grants()).singleElement().satisfies(grant -> {
                assertThat(grant.conch()).isEqualTo("kept_ping");
                assertThat(grant.since()).isEqualTo(clock.instant());
                assertThat(grant.createdBy()).isEqualTo(Actors.CONSOLE);
            });
            assertThat(local.pending()).isEmpty();

            byte[] other = yaml("other_ping", "1.2.0", "12");
            assertThat(local.applyIfGranted(other, Actors.MCP, "agent-key")).isEmpty();
            assertThat(Files.readAllBytes(file(crab, "other_ping"))).isEqualTo(yaml("other_ping", "1.0.0", "9"));

            byte[] next = yaml("kept_ping", "1.2.0", "11");
            LocalConches.ImportResult applied = local.applyIfGranted(next, Actors.MCP, "agent-key").orElseThrow();
            assertThat(applied.replaced()).isTrue();
            assertThat(applied.version()).isEqualTo("1.2.0");
            assertThat(Files.readAllBytes(file(crab, "kept_ping"))).isEqualTo(next);
            assertThat(local.grants()).extracting(LocalConches.OverrideGrant::conch).containsExactly("kept_ping");

            String detail = auditDetail(crab, AuditKind.LOCAL_CONCH_AUTO_APPROVED.name());
            assertThat(auditActor(crab, AuditKind.LOCAL_CONCH_AUTO_APPROVED.name())).isEqualTo(Actors.MCP);
            assertThat(detail).contains("\"autoApproved\":true")
                    .contains("\"grantedBy\":\"console\"")
                    .contains("\"key\":\"agent-key\"")
                    .contains("\"version\":\"1.2.0\"")
                    .contains("\"backupId\"");
            assertThat(auditCount(crab, AuditKind.LOCAL_CONCH_REPLACED.name())).isEqualTo(1);
        }
    }

    @Test
    void validationAndPathChecksStillRejectUnderAGrant() throws Exception {
        AtomicBoolean debug = new AtomicBoolean(true);
        try (OpenCrab crab = OpenCrab.open(temp.resolve("reject"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, debug::get);
            remember(local, "kept_ping", "1.1.0", "10");
            byte[] current = Files.readAllBytes(file(crab, "kept_ping"));

            assertThatThrownBy(() -> local.applyIfGranted(secret("kept_ping"), Actors.MCP, "agent-key"))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("规则包校验失败");
            assertThat(Files.readAllBytes(file(crab, "kept_ping"))).isEqualTo(current);
            assertThat(local.grants()).hasSize(1);
            assertThat(auditCount(crab, AuditKind.LOCAL_CONCH_AUTO_APPROVED.name())).isZero();

            byte[] huge = new byte[LocalConches.MAX_BYTES + 1];
            assertThatThrownBy(() -> local.applyIfGranted(huge, Actors.MCP, "agent-key"))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("yaml exceeds 1 MiB");
            assertThat(local.grants()).hasSize(1);

            Path live = file(crab, "kept_ping");
            Path aside = temp.resolve("aside.yaml");
            Files.move(live, aside);
            Files.createSymbolicLink(live, aside);
            assertThatThrownBy(() -> local.applyIfGranted(yaml("kept_ping", "1.3.0", "13"), Actors.MCP, "agent-key"))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("local conch path is a symlink");
            assertThat(Files.isSymbolicLink(live)).isTrue();
            assertThat(local.grants()).extracting(LocalConches.OverrideGrant::conch).containsExactly("kept_ping");
        }
    }

    @Test
    void grantClearsWhenDebugTurnsOffAndIsAbsentAfterRestart() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        Path home = temp.resolve("runtime");
        CrabConfig config = CrabConfig.defaults();
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), config, name -> null, clock)) {
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            LocalConches local = runtime.localConches();
            remember(local, "kept_ping", "1.1.0", "10");
            assertThat(local.grants()).hasSize(1);

            runtime.debug().setEnabled(false, Actors.CONSOLE);
            assertThat(local.grants()).isEmpty();
            assertThat(local.applyIfGranted(yaml("kept_ping", "1.4.0", "14"), Actors.MCP, "agent-key")).isEmpty();
            assertThat(Files.readString(runtime.paths().localConches().resolve("kept_ping.yaml"))).contains("1.1.0");

            runtime.debug().setEnabled(true, Actors.CONSOLE);
            remember(local, "kept_ping", "1.2.0", "11");
            assertThat(local.grants()).hasSize(1);
            clock.advance(DebugStateStore.WINDOW);
            runtime.debug().expire();
            assertThat(runtime.debug().get().enabled()).isFalse();
            assertThat(local.grants()).isEmpty();
        }
        try (CrabRuntime restarted = CrabRuntime.open(new CrabPaths(home), config, name -> null, clock)) {
            assertThat(restarted.localConches().grants()).isEmpty();
            restarted.loadInstalledConches();
            assertThat(restarted.localConches().packages()).extracting(pkg -> pkg.version()).contains("1.2.0");
            assertThat(restarted.localConches().grants()).isEmpty();
        }
    }

    @Test
    void explicitConfirmWhileDebugIsOffDoesNotStoreAGrant() throws Exception {
        AtomicBoolean debug = new AtomicBoolean(false);
        try (OpenCrab crab = OpenCrab.open(temp.resolve("off"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, debug::get);
            local.importBytes(yaml("kept_ping", "1.0.0", "9"), Actors.CLI);
            byte[] widened = yaml("kept_ping", "1.1.0", "10");
            LocalConches.PendingOverwrite staged = local.stagePending(widened, Actors.MCP, "agent-key").orElseThrow();
            local.confirmPending("kept_ping", staged.sha256(), Actors.CONSOLE, true);
            assertThat(Files.readAllBytes(file(crab, "kept_ping"))).isEqualTo(widened);
            assertThat(local.grants()).isEmpty();
        }
    }

    @Test
    void revokeBlocksUntilTheGrantedWriteLeavesTheLock() throws Exception {
        AtomicBoolean debug = new AtomicBoolean(true);
        try (OpenCrab crab = OpenCrab.open(temp.resolve("race"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, debug::get);
            remember(local, "kept_ping", "1.1.0", "10");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            local.widenedGap(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            });
            byte[] next = yaml("kept_ping", "1.3.0", "13");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<LocalConches.ImportResult>> apply = pool.submit(
                        () -> local.applyIfGranted(next, Actors.MCP, "agent-key"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Boolean> revoking = pool.submit(() -> local.revokeGrant("kept_ping"));
                assertThatThrownBy(() -> revoking.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(apply.get(5, TimeUnit.SECONDS)).isPresent();
                assertThat(revoking.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
            assertThat(local.grants()).isEmpty();
            assertThat(Files.readAllBytes(file(crab, "kept_ping"))).isEqualTo(next);
            assertThat(ConchParser.parse(Files.readString(file(crab, "kept_ping"))).version().toString()).isEqualTo("1.3.0");
        }
    }

    @Test
    void concurrentGrantedWritesLeaveOneCompletePackage() throws Exception {
        AtomicBoolean debug = new AtomicBoolean(true);
        try (OpenCrab crab = OpenCrab.open(temp.resolve("pair"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource, debug::get);
            remember(local, "kept_ping", "1.1.0", "10");
            AtomicInteger hits = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            local.widenedGap(() -> {
                if (hits.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("release timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
            });
            byte[] left = yaml("kept_ping", "1.4.0", "14");
            byte[] right = yaml("kept_ping", "1.5.0", "15");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<LocalConches.ImportResult>> first = pool.submit(
                        () -> local.applyIfGranted(left, Actors.MCP, "left-key"));
                Future<Optional<LocalConches.ImportResult>> second = pool.submit(
                        () -> local.applyIfGranted(right, Actors.MCP, "right-key"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
                assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
            } catch (ExecutionException ex) {
                throw ex;
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
            byte[] stored = Files.readAllBytes(file(crab, "kept_ping"));
            assertThat(stored).isIn(left, right);
            assertThat(ConchParser.parse(new String(stored, StandardCharsets.UTF_8)).conch()).isEqualTo("kept_ping");
            assertThat(local.history("kept_ping")).hasSize(LocalConches.HISTORY_KEEP);
            assertThat(auditCount(crab, AuditKind.LOCAL_CONCH_AUTO_APPROVED.name())).isEqualTo(2);
        }
    }

    private static void remember(LocalConches local, String name, String version, String port) {
        boolean exists = local.packages().stream().anyMatch(pkg -> name.equals(pkg.conch()));
        if (!exists) {
            local.importBytes(yaml(name, "1.0.0", "9"), Actors.CLI);
        }
        byte[] widened = yaml(name, version, port);
        LocalConches.PendingOverwrite staged = local.stagePending(widened, Actors.MCP, "agent-key").orElseThrow();
        local.confirmPending(name, staged.sha256(), Actors.CONSOLE, true);
    }

    private static byte[] yaml(String name, String version, String port) {
        return LocalConchBehaviorTest.ping(name, version)
                .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:" + port + "/")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] secret(String name) {
        return """
                conch: %s
                version: 9.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 本地探测
                description: 只在本机。
                credentials: {}
                config:
                  access_value: { type: secret, label: Access value }
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                      - set:
                          marker: ok
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                """.formatted(name).getBytes(StandardCharsets.UTF_8);
    }

    private static Path file(OpenCrab crab, String name) {
        return crab.paths.localConches().resolve(name + ".yaml");
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

    private static String auditActor(OpenCrab crab, String kind) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT actor FROM config_audit WHERE kind = ? ORDER BY id DESC")) {
            query.setString(1, kind);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
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
