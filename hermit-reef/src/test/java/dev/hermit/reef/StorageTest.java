package dev.hermit.reef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hermit.reef.admin.LoginService;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.retention.PurgeCounts;
import dev.hermit.reef.retention.RetentionJob;
import dev.hermit.reef.retention.RetentionService;
import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(ReefTestConfig.class)
class StorageTest {

    private static Path dataDir;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        dataDir = TestDataDirs.create("storage");
        registry.add("hermit.reef.data-dir", dataDir::toString);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdjustableClock clock;

    @Autowired
    RetentionService retentionService;

    @Autowired
    RetentionJob retentionJob;

    @Autowired
    ReefProperties reefProperties;

    @Autowired
    List<ScheduledTaskHolder> scheduledTasks;

    @BeforeEach
    void resetRows() {
        clock.setInstant(ReefTestConfig.START);
        jdbc.update("DELETE FROM hermit_failure_reports");
        jdbc.update("DELETE FROM hermit_admin_login_failures");
        jdbc.update("DELETE FROM hermit_rollback_events");
        jdbc.update("DELETE FROM hermit_hourly_counts");
        jdbc.update("DELETE FROM hermit_conch_versions");
    }

    @Test
    void flywayCreatesTheSchemaOnAnEmptyDatabase() {
        assertThat(tableNames()).contains(
                "HERMIT_ADMIN_USERS",
                "HERMIT_ADMIN_LOGIN_FAILURES",
                "HERMIT_CONCH_VERSIONS",
                "HERMIT_AUDIT_LOG",
                "HERMIT_FAILURE_REPORTS",
                "HERMIT_HOURLY_COUNTS",
                "HERMIT_ROLLBACK_EVENTS",
                "HERMIT_ALERTS",
                "HERMIT_CONCH_AVAILABILITY");
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"hermit_flyway_schema_history\" WHERE \"version\" = '1' AND \"success\" = TRUE",
                Integer.class);
        assertThat(applied).isEqualTo(1);
        Integer pullCountMigration = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"hermit_flyway_schema_history\" WHERE \"version\" = '5' AND \"success\" = TRUE",
                Integer.class);
        assertThat(pullCountMigration).isEqualTo(1);
        assertThat(indexNames()).contains(
                "idx_hermit_failure_reports_conch_version_occurred",
                "idx_hermit_failure_reports_received_at",
                "idx_hermit_hourly_counts_conch_hour");
    }

    @Test
    void pullCountDefaultsToZero() {
        insertConchVersion("legacy_oa", "1.0.0", "UPLOADED");
        Long count = jdbc.queryForObject(
                "SELECT pull_count FROM hermit_conch_versions WHERE conch = ? AND version = ?",
                Long.class,
                "legacy_oa",
                "1.0.0");
        assertThat(count).isZero();
    }

    @Test
    void everyTableNameStartsWithHermitAfterFlyway() {
        List<String> names = tableNames();
        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name ->
                assertThat(name.toLowerCase(Locale.ROOT)).startsWith("hermit_"));
        assertThat(names).anyMatch(name -> name.equalsIgnoreCase("hermit_flyway_schema_history"));
    }

    @Test
    void conchVersionRulesAndHourlyPrimaryKeyMatchTheContract() {
        insertConchVersion("legacy_oa", "1.0.0", "UPLOADED");
        insertConchVersion("legacy_oa", "1.0.2", "PUBLISHED");
        assertThatThrownBy(() -> insertConchVersion("legacy_oa", "1.0.0", "PUBLISHED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertConchVersion("legacy_oa", "1.0.1", "DRAFT"))
                .isInstanceOf(DataAccessException.class);

        insertHourly(1, 0);
        assertThatThrownBy(() -> insertHourly(2, 0)).isInstanceOf(DataAccessException.class);
        jdbc.update(
                "UPDATE hermit_hourly_counts SET success = ?, failure = ?, avg_millis = ?, p95_millis = ? "
                        + "WHERE conch = ? AND conch_version = ? AND action = ? AND hour_start = ? AND instance_id = ?",
                9L, 8L, 7L, 6L, "legacy_oa", "1.0.0", "list_todo", Timestamp.from(ReefTestConfig.START),
                "123e4567-e89b-12d3-a456-426614174000");
        Long success = jdbc.queryForObject(
                "SELECT success FROM hermit_hourly_counts WHERE conch = ? AND instance_id = ?",
                Long.class,
                "legacy_oa",
                "123e4567-e89b-12d3-a456-426614174000");
        assertThat(success).isEqualTo(9L);
    }

    @Test
    void createsDataDirectoriesAndAnOwnerOnlyJwtSecret() throws Exception {
        assertThat(Files.isDirectory(dataDir.resolve("db"))).isTrue();
        assertThat(Files.isDirectory(dataDir.resolve("conches"))).isTrue();
        assertThat(Files.isDirectory(dataDir.resolve("keys"))).isTrue();
        Path secret = dataDir.resolve("keys").resolve("jwt.secret");
        assertThat(Files.readAllBytes(secret)).hasSize(32);
        assertThat(Files.getPosixFilePermissions(secret)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void retentionDeletesOnlyExpiredReportsAndRollbackEvents() throws Exception {
        Scheduled scheduled = RetentionJob.class.getMethod("purgeExpiredReports").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${hermit.reef.retention-cron:0 0 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("UTC");
        assertThat(reefProperties.getRetentionCron()).isEqualTo("0 0 3 * * *");
        assertThat(resolvedRetentionCron()).isEqualTo("0 0 3 * * *");

        Instant now = ReefTestConfig.START;
        clock.setInstant(now);
        Instant cutoff = now.minus(Duration.ofDays(90));
        insertFailure(cutoff.minusSeconds(1), "old-failure");
        insertFailure(cutoff, "boundary-failure");
        insertFailure(cutoff.plusSeconds(1), "new-failure");
        insertRollback(cutoff.minusSeconds(1), "old-rollback");
        insertRollback(now, "new-rollback");
        insertHourly(3, 1);

        retentionJob.purgeExpiredReports();

        assertThat(summaries("hermit_failure_reports")).containsExactlyInAnyOrder("boundary-failure", "new-failure");
        assertThat(reasons()).containsExactly("new-rollback");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_hourly_counts", Integer.class)).isEqualTo(1);
        assertThat(retentionService.purge()).isEqualTo(new PurgeCounts(0, 0, 0));
    }

    @Test
    void retentionDeletesLoginFailuresOlderThanTheLockWindow() {
        Instant now = ReefTestConfig.START;
        clock.setInstant(now);
        Instant windowStart = now.minus(LoginService.LOCK_WINDOW);
        insertLoginFailure(windowStart.minusSeconds(1));
        insertLoginFailure(windowStart);
        insertLoginFailure(windowStart.plusSeconds(1));

        assertThat(retentionService.purge().loginFailures()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_admin_login_failures", Integer.class)).isEqualTo(2);
        Timestamp oldest = jdbc.queryForObject("SELECT MIN(failed_at) FROM hermit_admin_login_failures", Timestamp.class);
        assertThat(oldest.toInstant()).isEqualTo(windowStart);
    }

    private String resolvedRetentionCron() {
        for (ScheduledTaskHolder holder : scheduledTasks) {
            for (var scheduledTask : holder.getScheduledTasks()) {
                if (scheduledTask.getTask() instanceof CronTask cron
                        && cron.getRunnable().toString().contains("purgeExpiredReports")) {
                    return cron.getExpression();
                }
            }
        }
        throw new AssertionError("retention cron task was not registered");
    }

    private void insertConchVersion(String conch, String version, String status) {
        jdbc.update(
                "INSERT INTO hermit_conch_versions (conch, version, sha256, min_crab_version, package_path, status, "
                        + "uploaded_by, uploaded_at, is_current) VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)",
                conch, version, "a".repeat(64), "0.1.0", "conches/legacy_oa.zip", status, "reef-admin",
                Timestamp.from(ReefTestConfig.START));
    }

    private void insertHourly(long success, long failure) {
        jdbc.update(
                "INSERT INTO hermit_hourly_counts (conch, conch_version, action, hour_start, instance_id, success, "
                        + "failure, avg_millis, p95_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "legacy_oa", "1.0.0", "list_todo", Timestamp.from(ReefTestConfig.START),
                "123e4567-e89b-12d3-a456-426614174000", success, failure, 10L, 20L);
    }

    private void insertFailure(Instant receivedAt, String summary) {
        // (instance_id, trace_id) 从 V2 起唯一，夹具用各不相同的 summary 当 trace。
        jdbc.update(
                "INSERT INTO hermit_failure_reports (received_at, instance_id, crab_version, trace_id, conch, "
                        + "conch_version, action, step_index, step_type, error_code, summary, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(receivedAt), "123e4567-e89b-12d3-a456-426614174000", "1.0.0", summary,
                "legacy_oa", "1.0.0", "list_todo", 1, "request", "UPSTREAM_ERROR", summary,
                Timestamp.from(receivedAt));
    }

    private void insertLoginFailure(Instant failedAt) {
        jdbc.update(
                "INSERT INTO hermit_admin_login_failures (username, failed_at) VALUES (?, ?)",
                "guessed-user",
                Timestamp.from(failedAt));
    }

    private void insertRollback(Instant receivedAt, String reason) {
        jdbc.update(
                "INSERT INTO hermit_rollback_events (received_at, instance_id, conch, from_version, to_version, reason, "
                        + "occurred_at, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(receivedAt), "123e4567-e89b-12d3-a456-426614174000", "legacy_oa", "1.0.1",
                "1.0.0", reason, Timestamp.from(receivedAt), reason);
    }

    private List<String> summaries(String table) {
        return jdbc.queryForList("SELECT summary FROM " + table, String.class);
    }

    private List<String> reasons() {
        return jdbc.queryForList("SELECT reason FROM hermit_rollback_events", String.class);
    }

    private List<String> tableNames() {
        return jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
                String.class);
    }

    private List<String> indexNames() {
        return jdbc.queryForList(
                        "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'",
                        String.class)
                .stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
