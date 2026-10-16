package dev.hermit.crab.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.report.ReportJson;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.StepLog;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.testing.FakeReef;
import dev.hermit.crab.testing.MutableClock;

class ReportSenderTest {

    private static final UUID INSTANCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String ACCOUNT = "HermitAccount-alice";
    private static final String INPUT = "HermitInput-secret";
    private static final String MESSAGE = "HermitErrorMessage-zzzz";
    private static final String QUERY = "HermitQuery-zzzz";
    private static final String TOKEN = "HermitReportToken-should-not-leak";

    @TempDir
    Path temp;

    @Test
    void previewAndBodyOmitSecretsAndSendTheFullHourlyTotal() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:30:00Z"));
        try (FakeReef reef = new FakeReef(); CrabDatabase database = database()) {
            reef.expectToken(TOKEN);
            ReportSettings settings = settings(reef, true, Optional.of(TOKEN));
            ReportQueue queue = new ReportQueue(settings, database.dataSource(), clock);
            ReportSender sender = new ReportSender(settings, queue, clock);

            queue.record("legacy_oa", "1.0.0", "list_todo", success());
            queue.record("legacy_oa", "1.0.0", "list_todo", success());
            queue.record("legacy_oa", "1.0.0", "list_todo", failure("trace-fail"));
            ReportPreview preview = sender.preview();
            String previewJson = ReportJson.write(preview.batch());
            assertThat(reef.reportBodies()).isEmpty();
            assertThat(preview.batch().failures()).singleElement().satisfies(row -> {
                assertThat(row.traceId()).isEqualTo("trace-fail");
                assertThat(row.stepIndex()).isEqualTo(1);
                assertThat(row.stepType()).isEqualTo("assert");
                assertThat(row.summary()).isNull();
                assertThat(row.errorCode()).isEqualTo(ErrorCode.RULE_MISMATCH);
            });
            long[] buckets = LatencyHistogram.empty();
            LatencyHistogram.add(buckets, 5);
            LatencyHistogram.add(buckets, 5);
            LatencyHistogram.add(buckets, 9);
            assertThat(preview.batch().counts()).singleElement().satisfies(count -> {
                assertThat(count.hourStart()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
                assertThat(count.success()).isEqualTo(2);
                assertThat(count.failure()).isEqualTo(1);
                assertThat(count.avgMillis()).isEqualTo(6);
                assertThat(count.p95Millis()).isEqualTo(LatencyHistogram.p95(buckets));
                assertThat(count.p95Millis()).isPositive();
            });
            assertSecretsAbsent(previewJson);
            assertSecretsAbsent(textColumns(database));

            assertThat(sender.flushOnce().accepted()).isTrue();
            assertThat(reef.tokenMatched()).isTrue();
            assertSecretsAbsent(reef.reportBodies().get(0));
            assertThat(ReportJson.readBatch(reef.reportBodies().get(0)).counts().get(0).success()).isEqualTo(2);

            queue.record("legacy_oa", "1.0.0", "list_todo", success());
            assertThat(sender.flushOnce().accepted()).isTrue();
            var counts = ReportJson.readBatch(reef.reportBodies().get(1)).counts();
            assertThat(counts).singleElement().satisfies(count -> {
                assertThat(count.success()).isEqualTo(3);
                assertThat(count.failure()).isEqualTo(1);
            });
            assertThat(sender.preview().batch().failures()).isEmpty();
            assertThat(sender.preview().batch().counts()).isEmpty();
        }
    }

    @Test
    void hourlyLatencyUsesTheAverageAndTheBucketUpperBound() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:30:00Z"));
        try (CrabDatabase database = database()) {
            ReportSettings settings = new ReportSettings(
                    true, URI("http://127.0.0.1:9/"), Optional.empty(), INSTANCE, "1.0.0");
            ReportQueue queue = new ReportQueue(settings, database.dataSource(), clock);
            ReportSender sender = new ReportSender(settings, queue, clock);
            queue.record("legacy_oa", "1.0.0", "list_todo", result(true, 4));
            queue.record("legacy_oa", "1.0.0", "list_todo", result(true, 4));
            queue.record("legacy_oa", "1.0.0", "list_todo", result(false, 40_000));

            long[] bounds = LatencyHistogram.bounds();
            assertThat(bounds).hasSize(LatencyHistogram.BUCKETS);
            assertThat(bounds[0]).isEqualTo(LatencyHistogram.MIN_MS);
            assertThat(bounds[bounds.length - 1]).isEqualTo(LatencyHistogram.MAX_MS);
            long[] buckets = LatencyHistogram.empty();
            LatencyHistogram.add(buckets, 4);
            LatencyHistogram.add(buckets, 4);
            LatencyHistogram.add(buckets, 40_000);
            long upper = LatencyHistogram.upperBound(40_000);
            assertThat(upper).isGreaterThanOrEqualTo(40_000);
            assertThat(upper).isLessThanOrEqualTo(60_000);
            assertThat(sender.preview().batch().counts()).singleElement().satisfies(count -> {
                assertThat(count.success()).isEqualTo(2);
                assertThat(count.failure()).isEqualTo(1);
                assertThat(count.avgMillis()).isEqualTo(40_008L / 3);
                assertThat(count.p95Millis()).isEqualTo(LatencyHistogram.p95(buckets));
                assertThat(count.p95Millis()).isEqualTo(upper);
            });
        }
    }

    @Test
    void serverErrorsBackOffWithoutDroppingAndConfirmSendsImmediately() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        try (FakeReef reef = new FakeReef(); CrabDatabase database = database()) {
            reef.reportStatus(500);
            ReportSettings settings = settings(reef, true, Optional.empty());
            ReportQueue queue = new ReportQueue(settings, database.dataSource(), clock);
            ReportSender sender = new ReportSender(settings, queue, clock);
            queue.record("legacy_oa", "1.0.0", "list_todo", failure("trace-keep"));

            assertThat(sender.flushOnce().accepted()).isFalse();
            assertThat(reef.reportBodies()).hasSize(1);
            assertThat(sender.flushOnce().attempted()).isFalse();
            assertThat(reef.reportBodies()).hasSize(1);
            assertThat(sender.preview().batch().failures()).hasSize(1);

            clock.advance(Duration.ofSeconds(1));
            assertThat(sender.flushOnce().status()).isEqualTo(500);
            assertThat(reef.reportBodies()).hasSize(2);
            assertThat(sender.confirmAndSend().status()).isEqualTo(500);
            assertThat(reef.reportBodies()).hasSize(3);
            assertThat(sender.preview().batch().failures()).hasSize(1);

            reef.reportStatus(200);
            assertThat(sender.confirmAndSend().accepted()).isTrue();
            assertThat(sender.preview().batch().failures()).isEmpty();
            assertThat(sender.preview().batch().counts()).isEmpty();
        }
    }

    @Test
    void theQueueDropsTheOldestRowPastTheCap() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        try (CrabDatabase database = database()) {
            ReportSettings settings = new ReportSettings(
                    true, URI("http://127.0.0.1:9/"), Optional.empty(), INSTANCE, "1.0.0");
            ReportQueue queue = new ReportQueue(settings, database.dataSource(), clock, 3);
            for (int i = 1; i <= 4; i++) {
                clock.advance(Duration.ofSeconds(1));
                queue.record("legacy_oa", "1.0.0", "list_todo", failure("trace-" + i));
            }
            ReportSender sender = new ReportSender(settings, queue, clock);
            assertThat(ReportQueue.MAX_QUEUED).isEqualTo(10_000);
            assertThat(sender.preview().dropped()).isEqualTo(1);
            assertThat(sender.preview().batch().failures())
                    .extracting(dev.hermit.common.report.FailureReport::traceId)
                    .containsExactly("trace-2", "trace-3", "trace-4");
        }
    }

    @Test
    void rollbackEventIdStaysStableWhenUnacked() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        try (FakeReef reef = new FakeReef(); CrabDatabase database = database()) {
            reef.reportStatus(500);
            ReportSettings settings = settings(reef, true, Optional.empty());
            ReportQueue queue = new ReportQueue(settings, database.dataSource(), clock);
            ReportSender sender = new ReportSender(settings, queue, clock);
            queue.enqueueRollback(
                    "legacy_oa", "1.0.1", "1.0.0", "RULE_MISMATCH", Instant.parse("2026-06-01T00:00:00Z"));

            String eventId = sender.preview().batch().events().get(0).eventId();
            assertThat(eventId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(sender.flushOnce().accepted()).isFalse();
            assertThat(ReportJson.readBatch(reef.reportBodies().get(0)).events().get(0).eventId()).isEqualTo(eventId);
            assertThat(sender.preview().batch().events().get(0).eventId()).isEqualTo(eventId);

            clock.advance(Duration.ofSeconds(1));
            assertThat(sender.flushOnce().status()).isEqualTo(500);
            assertThat(ReportJson.readBatch(reef.reportBodies().get(1)).events().get(0).eventId()).isEqualTo(eventId);
            assertThat(queuedEventId(database)).isEqualTo(eventId);
            assertThat(count(database, "rollback_queue")).isEqualTo(1);
        }
    }

    @Test
    void disabledReportingWritesNothing() throws Exception {
        try (FakeReef reef = new FakeReef(); CrabDatabase database = database()) {
            ReportSettings settings = settings(reef, false, Optional.empty());
            ReportQueue queue = new ReportQueue(settings, database.dataSource(), Clock());
            ReportSender sender = new ReportSender(settings, queue, Clock());
            queue.record("legacy_oa", "1.0.0", "list_todo", failure("trace-off"));
            queue.enqueueRollback("legacy_oa", "1.0.1", "1.0.0", "RULE_MISMATCH", Instant.parse("2026-06-01T00:00:00Z"));
            assertThat(sender.flushOnce().attempted()).isFalse();
            assertThat(sender.confirmAndSend().attempted()).isFalse();
            assertThat(sender.preview().batch().failures()).isEmpty();
            assertThat(sender.preview().batch().events()).isEmpty();
            assertThat(reef.reportBodies()).isEmpty();
            assertThat(count(database, "report_queue")).isZero();
            assertThat(count(database, "rollback_queue")).isZero();
        }
    }

    private static void assertSecretsAbsent(String text) {
        assertThat(text).doesNotContain(ACCOUNT, INPUT, MESSAGE, QUERY, TOKEN);
    }

    private static ExecutionResult success() {
        return result(true, 5);
    }

    private static ExecutionResult result(boolean succeeded, long durationMillis) {
        return new ExecutionResult(
                succeeded,
                INPUT,
                succeeded ? null : new ExecutionError(ErrorCode.RULE_MISMATCH, MESSAGE, 1, "assert", MESSAGE),
                durationMillis,
                List.of(),
                "1.0.0",
                succeeded ? "trace-ok" : "trace-slow");
    }

    private static ExecutionResult failure(String traceId) {
        return new ExecutionResult(
                false,
                INPUT,
                new ExecutionError(ErrorCode.RULE_MISMATCH, MESSAGE + " account=" + ACCOUNT, 1, "assert", MESSAGE),
                9,
                List.of(new StepLog(0, "request", 2, "GET", "/todo?q=" + QUERY, 500, null)),
                "1.0.0",
                traceId);
    }

    private static ReportSettings settings(FakeReef reef, boolean enabled, Optional<String> token) {
        return new ReportSettings(enabled, reef.base(), token, INSTANCE, "1.0.0");
    }

    private static java.time.Clock Clock() {
        return new MutableClock(Instant.parse("2026-06-01T00:30:00Z"));
    }

    private static java.net.URI URI(String text) {
        return java.net.URI.create(text);
    }

    private CrabDatabase database() {
        return new CrabDatabase(new CrabPaths(temp));
    }

    private static String queuedEventId(CrabDatabase database) throws Exception {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT event_id FROM rollback_queue")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static int count(CrabDatabase database, String table) throws Exception {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String textColumns(CrabDatabase database) throws Exception {
        StringBuilder text = new StringBuilder();
        try (Connection connection = database.dataSource().getConnection();
             Statement tables = connection.createStatement();
             ResultSet columns = tables.executeQuery("""
                     SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE
                     FROM INFORMATION_SCHEMA.COLUMNS
                     WHERE TABLE_SCHEMA = 'PUBLIC'
                     """)) {
            java.util.List<String[]> selected = new java.util.ArrayList<>();
            while (columns.next()) {
                String type = columns.getString(3);
                if (type != null && (type.contains("CHAR") || type.contains("CLOB"))) {
                    selected.add(new String[] {columns.getString(1), columns.getString(2)});
                }
            }
            for (String[] column : selected) {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT \"" + column[1] + "\" FROM \"" + column[0] + "\"")) {
                    while (rows.next()) {
                        text.append(rows.getString(1)).append('\n');
                    }
                }
            }
        }
        return text.toString();
    }
}
