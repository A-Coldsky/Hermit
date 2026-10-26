package dev.hermit.crab.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.report.CountReport;
import dev.hermit.common.report.FailureReport;
import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.RollbackEvent;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.store.Db;

public final class ReportQueue {

    public static final int MAX_QUEUED = 10_000;
    static final int MAX_BATCH = 500;
    static final int MAX_EVENTS = 50;

    private final ReportSettings settings;
    private final DataSource dataSource;
    private final Clock clock;
    private final int cap;
    private final Object lock = new Object();

    public ReportQueue(ReportSettings settings, DataSource dataSource) {
        this(settings, dataSource, Clock.systemUTC(), MAX_QUEUED);
    }

    public ReportQueue(ReportSettings settings, DataSource dataSource, Clock clock) {
        this(settings, dataSource, clock, MAX_QUEUED);
    }

    ReportQueue(ReportSettings settings, DataSource dataSource, Clock clock, int cap) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cap < 1) {
            throw new IllegalArgumentException("cap must be positive");
        }
        this.cap = cap;
    }

    public void record(String conch, String version, String action, ExecutionResult result) {
        if (!settings.enabled()) {
            return;
        }
        requireText(conch, 256, "conch");
        requireText(version, 64, "version");
        requireText(action, 256, "action");
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        synchronized (lock) {
            Db.transaction(dataSource, connection -> {
                if (!result.success()) {
                    makeRoom(connection, 1);
                    insertFailure(connection, conch, version, action, result);
                }
                bumpHour(connection, conch, version, action, result);
                return null;
            });
        }
    }

    public void enqueueRollback(String conch, String fromVersion, String toVersion, String reason, Instant at) {
        if (!settings.enabled()) {
            return;
        }
        requireText(conch, 256, "conch");
        requireText(fromVersion, 64, "fromVersion");
        requireText(toVersion, 64, "toVersion");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (at == null) {
            throw new IllegalArgumentException("at is required");
        }
        String storedReason = reason.length() > 1024 ? reason.substring(0, 1024) : reason;
        String eventId = UUID.randomUUID().toString();
        synchronized (lock) {
            Db.transaction(dataSource, connection -> {
                makeRoom(connection, 1);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO rollback_queue (at, conch, from_version, to_version, reason, event_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    Db.setInstant(statement, 1, at);
                    statement.setString(2, conch);
                    statement.setString(3, fromVersion);
                    statement.setString(4, toVersion);
                    statement.setString(5, storedReason);
                    statement.setString(6, eventId);
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    public long dropped() {
        synchronized (lock) {
            return Db.transaction(dataSource, this::droppedAmount);
        }
    }

    Pending peek() {
        synchronized (lock) {
            return Db.transaction(dataSource, this::readPending);
        }
    }

    void ack(Pending pending) {
        synchronized (lock) {
            Db.transaction(dataSource, connection -> {
                for (Long id : pending.failureIds()) {
                    deleteId(connection, "report_queue", id);
                }
                for (Long id : pending.eventIds()) {
                    deleteId(connection, "rollback_queue", id);
                }
                for (CountSnapshot count : pending.counts()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE hourly_counts
                            SET sent_total = ?, sent_codes = ?, sent_sum_ms = ?, sent_buckets = ?
                            WHERE "hour" = ? AND conch = ? AND version = ? AND action = ?
                              AND success_count = ? AND failure_count_by_code = ?
                              AND sum_ms = ? AND latency_buckets = ?
                            """)) {
                        statement.setLong(1, count.success() + count.failure());
                        statement.setString(2, count.failureJson());
                        statement.setLong(3, count.sumMs());
                        statement.setString(4, count.bucketsJson());
                        Db.setInstant(statement, 5, count.hour());
                        statement.setString(6, count.conch());
                        statement.setString(7, count.version());
                        statement.setString(8, count.action());
                        statement.setLong(9, count.success());
                        statement.setString(10, count.failureJson());
                        statement.setLong(11, count.sumMs());
                        statement.setString(12, count.bucketsJson());
                        statement.executeUpdate();
                    }
                }
                return null;
            });
        }
    }

    private Pending readPending(Connection connection) throws SQLException {
        int eventRoom = Math.min(MAX_EVENTS, MAX_BATCH);
        List<RollbackEvent> events = new ArrayList<>();
        List<Long> eventIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, conch, from_version, to_version, reason, at, event_id
                FROM rollback_queue
                ORDER BY at, id
                LIMIT ?
                """)) {
            statement.setInt(1, eventRoom);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    eventIds.add(resultSet.getLong("id"));
                    events.add(new RollbackEvent(
                            resultSet.getString("conch"),
                            resultSet.getString("from_version"),
                            resultSet.getString("to_version"),
                            resultSet.getString("reason"),
                            Db.instant(resultSet, "at"),
                            resultSet.getString("event_id")));
                }
            }
        }
        int failureRoom = MAX_BATCH - events.size();
        List<FailureReport> failures = new ArrayList<>();
        List<Long> failureIds = new ArrayList<>();
        if (failureRoom > 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, trace_id, conch, version, action, error_code, step_index, step_type, at
                    FROM report_queue
                    ORDER BY at, id
                    LIMIT ?
                    """)) {
                statement.setInt(1, failureRoom);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        failureIds.add(resultSet.getLong("id"));
                        Integer stepIndex = resultSet.getObject("step_index") == null
                                ? null
                                : resultSet.getInt("step_index");
                        failures.add(new FailureReport(
                                resultSet.getString("trace_id"),
                                resultSet.getString("conch"),
                                resultSet.getString("version"),
                                resultSet.getString("action"),
                                stepIndex,
                                resultSet.getString("step_type"),
                                ErrorCode.valueOf(resultSet.getString("error_code")),
                                null,
                                Db.instant(resultSet, "at")));
                    }
                }
            }
        }
        int countRoom = MAX_BATCH - events.size() - failures.size();
        List<CountReport> counts = new ArrayList<>();
        List<CountSnapshot> snapshots = new ArrayList<>();
        if (countRoom > 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT "hour", conch, version, action, success_count, failure_count_by_code,
                           sum_ms, latency_buckets, sent_total, sent_codes, sent_sum_ms, sent_buckets
                    FROM hourly_counts
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next() && counts.size() < countRoom) {
                    long success = resultSet.getLong("success_count");
                    String json = resultSet.getString("failure_count_by_code");
                    long failure = FailureCodes.total(FailureCodes.read(json));
                    long sumMs = resultSet.getLong("sum_ms");
                    String bucketsJson = resultSet.getString("latency_buckets");
                    long calls = success + failure;
                    Long sentTotal = resultSet.getObject("sent_total") == null ? null : resultSet.getLong("sent_total");
                    String sentCodes = resultSet.getString("sent_codes");
                    Long sentSum = resultSet.getObject("sent_sum_ms") == null ? null : resultSet.getLong("sent_sum_ms");
                    String sentBuckets = resultSet.getString("sent_buckets");
                    if (sentTotal != null && sentTotal == calls && Objects.equals(json, sentCodes)
                            && sentSum != null && sentSum == sumMs && Objects.equals(bucketsJson, sentBuckets)) {
                        continue;
                    }
                    Instant hour = Db.instant(resultSet, "hour");
                    String conch = resultSet.getString("conch");
                    String version = resultSet.getString("version");
                    String action = resultSet.getString("action");
                    long[] buckets = LatencyHistogram.read(bucketsJson);
                    counts.add(new CountReport(
                            conch,
                            version,
                            action,
                            hour,
                            success,
                            failure,
                            LatencyHistogram.average(sumMs, calls),
                            LatencyHistogram.p95(buckets)));
                    snapshots.add(new CountSnapshot(
                            hour, conch, version, action, success, failure, json, sumMs, bucketsJson));
                }
            }
        }
        ReportBatch batch = new ReportBatch(
                settings.instanceId().toString(),
                settings.crabVersion(),
                clock.instant(),
                failures,
                counts,
                events);
        return new Pending(batch, failureIds, snapshots, eventIds);
    }

    private void insertFailure(
            Connection connection,
            String conch,
            String version,
            String action,
            ExecutionResult result
    ) throws SQLException {
        ExecutionError error = result.error();
        ErrorCode code = error == null || error.code() == null ? ErrorCode.INTERNAL : error.code();
        Integer stepIndex = error == null ? null : error.failedStepIndex();
        String stepType = error == null || error.failedStepType() == null ? null : truncate(error.failedStepType(), 64);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_queue (
                    trace_id, at, conch, version, action, error_code, step_index, step_type, duration_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, traceId(result.traceId()));
            Db.setInstant(statement, 2, clock.instant());
            statement.setString(3, conch);
            statement.setString(4, version);
            statement.setString(5, action);
            statement.setString(6, code.name());
            if (stepIndex == null) {
                statement.setNull(7, Types.INTEGER);
            } else {
                statement.setInt(7, stepIndex);
            }
            if (stepType == null) {
                statement.setNull(8, Types.VARCHAR);
            } else {
                statement.setString(8, stepType);
            }
            statement.setLong(9, Math.max(0L, result.durationMillis()));
            statement.executeUpdate();
        }
    }

    private void bumpHour(
            Connection connection,
            String conch,
            String version,
            String action,
            ExecutionResult result
    ) throws SQLException {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        long success = 0;
        long sumMs = 0;
        String json = FailureCodes.write(Map.of());
        long[] buckets = LatencyHistogram.empty();
        boolean found = false;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT success_count, failure_count_by_code, sum_ms, latency_buckets
                FROM hourly_counts
                WHERE "hour" = ? AND conch = ? AND version = ? AND action = ?
                """)) {
            Db.setInstant(statement, 1, hour);
            statement.setString(2, conch);
            statement.setString(3, version);
            statement.setString(4, action);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    found = true;
                    success = resultSet.getLong("success_count");
                    json = resultSet.getString("failure_count_by_code");
                    sumMs = resultSet.getLong("sum_ms");
                    buckets = LatencyHistogram.read(resultSet.getString("latency_buckets"));
                }
            }
        }
        Map<String, Long> codes = new LinkedHashMap<>(FailureCodes.read(json));
        if (result.success()) {
            success++;
        } else {
            ErrorCode code = result.error() == null || result.error().code() == null
                    ? ErrorCode.INTERNAL
                    : result.error().code();
            codes.merge(code.name(), 1L, Long::sum);
        }
        long duration = Math.max(0L, result.durationMillis());
        sumMs = saturatingAdd(sumMs, duration);
        LatencyHistogram.add(buckets, duration);
        String stored = FailureCodes.write(codes);
        String bucketJson = LatencyHistogram.write(buckets);
        if (found) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE hourly_counts
                    SET success_count = ?, failure_count_by_code = ?, sum_ms = ?, latency_buckets = ?
                    WHERE "hour" = ? AND conch = ? AND version = ? AND action = ?
                    """)) {
                statement.setLong(1, success);
                statement.setString(2, stored);
                statement.setLong(3, sumMs);
                statement.setString(4, bucketJson);
                Db.setInstant(statement, 5, hour);
                statement.setString(6, conch);
                statement.setString(7, version);
                statement.setString(8, action);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO hourly_counts (
                    "hour", conch, version, action, success_count, failure_count_by_code, sum_ms, latency_buckets
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            Db.setInstant(statement, 1, hour);
            statement.setString(2, conch);
            statement.setString(3, version);
            statement.setString(4, action);
            statement.setLong(5, success);
            statement.setString(6, stored);
            statement.setLong(7, sumMs);
            statement.setString(8, bucketJson);
            statement.executeUpdate();
        }
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private void makeRoom(Connection connection, int incoming) throws SQLException {
        int overflow = count(connection, "report_queue") + count(connection, "rollback_queue") + incoming - cap;
        while (overflow > 0) {
            RowRef failure = oldest(connection, "report_queue");
            RowRef event = oldest(connection, "rollback_queue");
            if (failure == null && event == null) {
                return;
            }
            boolean dropFailure = event == null || (failure != null && !failure.at().isAfter(event.at()));
            deleteId(connection, dropFailure ? "report_queue" : "rollback_queue", dropFailure ? failure.id() : event.id());
            addDropped(connection, 1);
            overflow--;
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static RowRef oldest(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, at FROM " + table + " ORDER BY at, id LIMIT 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            return new RowRef(resultSet.getLong("id"), Db.instant(resultSet, "at"));
        }
    }

    private static void deleteId(Connection connection, String table, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private static void addDropped(Connection connection, int amount) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE report_meta SET amount = amount + ? WHERE name = 'dropped'")) {
            update.setInt(1, amount);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO report_meta (name, amount) VALUES ('dropped', ?)")) {
            insert.setInt(1, amount);
            insert.executeUpdate();
        }
    }

    private long droppedAmount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amount FROM report_meta WHERE name = 'dropped'");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getLong(1);
        }
    }

    private static void requireText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String traceId(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return truncate(raw, 128);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    record Pending(
            ReportBatch batch,
            List<Long> failureIds,
            List<CountSnapshot> counts,
            List<Long> eventIds
    ) {
        boolean isEmpty() {
            return batch.failures().isEmpty() && batch.counts().isEmpty() && batch.events().isEmpty();
        }
    }

    record CountSnapshot(
            Instant hour,
            String conch,
            String version,
            String action,
            long success,
            long failure,
            String failureJson,
            long sumMs,
            String bucketsJson
    ) {
    }

    private record RowRef(long id, Instant at) {
    }
}
