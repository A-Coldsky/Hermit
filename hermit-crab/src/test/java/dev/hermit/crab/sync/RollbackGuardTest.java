package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.report.ReportJson;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.StepLog;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.report.ReportSender;
import dev.hermit.crab.report.ReportSettings;
import dev.hermit.crab.testing.FakeReef;

class RollbackGuardTest {

    private static final UUID INSTANCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String MESSAGE = "HermitRollbackMessage-zzzz";

    @TempDir
    Path temp;

    @Test
    void fiveMismatchesRollBackPinAndRefuseTheBadVersionUntilANewerOneArrives() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            ReportSettings settings = new ReportSettings(true, reef.base(), Optional.empty(), INSTANCE, "1.0.0");
            ReportQueue queue = new ReportQueue(settings, crab.dataSource);
            RollbackGuard guard = guard(reef, crab, registry, queue);

            install(reef, sync, "1.0.0");
            guard.onResult("legacy_oa", "1.0.0", success("1.0.0"));
            install(reef, sync, "1.0.1");
            for (int i = 0; i < 4; i++) {
                guard.onResult("legacy_oa", "1.0.1", mismatch());
            }
            assertThat(current(crab)).isEqualTo("1.0.1");
            guard.onResult("legacy_oa", "1.0.1", mismatch());

            assertThat(current(crab)).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(pinCount(crab)).isEqualTo(1);
            String preview = ReportJson.write(new ReportSender(settings, queue).preview().batch());
            assertThat(preview).contains("1.0.1").contains("1.0.0").contains(RollbackGuard.REASON);
            assertThat(preview).doesNotContain(MESSAGE);

            reef.bustEtag();
            SyncReport held = sync.syncOnce();
            assertThat(outcome(held).kind()).isEqualTo(OutcomeKind.SKIPPED_PINNED);
            assertThat(current(crab)).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.2"));
            SyncReport upgraded = sync.syncOnce();
            assertThat(outcome(upgraded).kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(current(crab)).isEqualTo("1.0.2");
            assertThat(pinCount(crab)).isZero();
        }
    }

    @Test
    void upstreamRejectionAndSessionExpiryDoNotRollBackButRuleMismatchStillDoes() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            RollbackGuard guard = guard(reef, crab, registry, queue(reef, crab, false));
            install(reef, sync, "1.0.0");
            guard.onResult("legacy_oa", "1.0.0", success("1.0.0"));
            install(reef, sync, "1.0.1");
            for (int i = 0; i < RollbackGuard.MISMATCH_LIMIT; i++) {
                guard.onResult("legacy_oa", "1.0.1", result(false, ErrorCode.INVALID_INPUT));
                guard.onResult("legacy_oa", "1.0.1", result(false, ErrorCode.SESSION_EXPIRED));
            }
            assertThat(current(crab)).isEqualTo("1.0.1");
            assertThat(pinCount(crab)).isZero();
            for (int i = 0; i < RollbackGuard.MISMATCH_LIMIT; i++) {
                guard.onResult("legacy_oa", "1.0.1", mismatch());
            }
            assertThat(current(crab)).isEqualTo("1.0.0");
            assertThat(pinCount(crab)).isEqualTo(1);
        }
    }

    @Test
    void doesNotRollBackWhenThePreviousVersionNeverSucceeded() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            RollbackGuard guard = guard(reef, crab, registry, queue(reef, crab, true));
            install(reef, sync, "1.0.0");
            install(reef, sync, "1.0.1");
            for (int i = 0; i < 5; i++) {
                guard.onResult("legacy_oa", "1.0.1", mismatch());
            }
            assertThat(current(crab)).isEqualTo("1.0.1");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(pinCount(crab)).isZero();
        }
    }

    @Test
    void otherErrorsDoNotResetTheMismatchStreakAndSuccessDoes() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            RollbackGuard guard = guard(reef, crab, registry, queue(reef, crab, false));
            install(reef, sync, "1.0.0");
            guard.onResult("legacy_oa", "1.0.0", success("1.0.0"));
            install(reef, sync, "1.0.1");
            for (int i = 0; i < 3; i++) {
                guard.onResult("legacy_oa", "1.0.1", mismatch());
            }
            guard.onResult("legacy_oa", "1.0.1", result(false, ErrorCode.UPSTREAM_ERROR));
            guard.onResult("legacy_oa", "1.0.1", mismatch());
            guard.onResult("legacy_oa", "1.0.1", mismatch());
            assertThat(current(crab)).isEqualTo("1.0.0");

            install(reef, sync, "1.0.2");
            for (int i = 0; i < 4; i++) {
                guard.onResult("legacy_oa", "1.0.2", mismatch());
            }
            guard.onResult("legacy_oa", "1.0.2", success("1.0.2"));
            for (int i = 0; i < 4; i++) {
                guard.onResult("legacy_oa", "1.0.2", mismatch());
            }
            assertThat(current(crab)).isEqualTo("1.0.2");
        }
    }

    private static ConchSync sync(FakeReef reef, OpenCrab crab, ConchRegistry registry) {
        TestSubscriptions.grantKnown(crab.dataSource);
        return new ConchSync(
                new SyncSettings(reef.base(), Optional.empty(), reef.keys().getPublic(), Duration.ofMinutes(5),
                        INSTANCE),
                crab.paths,
                registry,
                crab.vault,
                crab.dataSource);
    }

    private static ReportQueue queue(FakeReef reef, OpenCrab crab, boolean enabled) {
        return new ReportQueue(new ReportSettings(enabled, reef.base(), Optional.empty(), INSTANCE, "1.0.0"),
                crab.dataSource);
    }

    private static RollbackGuard guard(FakeReef reef, OpenCrab crab, ConchRegistry registry, ReportQueue queue) {
        return new RollbackGuard(crab.paths, reef.keys().getPublic(), registry, crab.dataSource, queue);
    }

    private static void install(FakeReef reef, ConchSync sync, String version) {
        reef.publish(ConchTexts.standard("legacy_oa", version));
        assertThat(sync.syncOnce().conches())
                .anyMatch(item -> "legacy_oa".equals(item.conch()) && item.kind() == OutcomeKind.INSTALLED);
    }

    private static ConchOutcome outcome(SyncReport report) {
        return report.conches().stream().filter(item -> "legacy_oa".equals(item.conch())).findFirst().orElseThrow();
    }

    private static String current(OpenCrab crab) throws Exception {
        return Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim();
    }

    private static int pinCount(OpenCrab crab) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM pinned_rollbacks")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static ExecutionResult success(String version) {
        return new ExecutionResult(true, null, null, 1, List.of(), version, "trace-ok");
    }

    private static ExecutionResult mismatch() {
        return result(false, ErrorCode.RULE_MISMATCH);
    }

    private static ExecutionResult result(boolean success, ErrorCode code) {
        return new ExecutionResult(
                success,
                null,
                success ? null : new ExecutionError(code, MESSAGE, 0, "assert", MESSAGE),
                4,
                List.of(new StepLog(0, "request", 1, "GET", "/todo?x=" + MESSAGE, 500, null)),
                "1.0.1",
                "trace-mismatch");
    }
}
