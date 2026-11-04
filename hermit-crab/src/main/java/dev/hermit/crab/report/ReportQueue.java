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
    /** Hourly counts and queued failures are written together on this interval, before a send, and on close. */
    public static final java.time.Duration MEMORY_FLUSH_INTERVAL = java.time.Duration.ofSeconds(30);
    static final int MAX_BATCH = 500;
    static final int MAX_EVENTS = 50;

    private final LiveReportSettings live;
    private final DataSource dataSource;
    private final Clock clock;
    private final int cap;
    private final Object lock = new Object();
    private final List<QueuedFailure> memoryFailures = new ArrayList<>();
    private final LinkedHashMap<CountKey, MutableHour> memoryCounts = new LinkedHashMap<>();
    private int memoryDropped;
    private int persistedQueued;

    public ReportQueue(ReportSettings settings, DataSource dataSource) {
        this(new LiveReportSettings(settings), dataSource, Clock.systemUTC(), MAX_QUEUED);
    }

    public ReportQueue(ReportSettings settings, DataSource dataSource, Clock clock) {
        this(new LiveReportSettings(settings), dataSource, clock, MAX_QUEUED);
    }

    public ReportQueue(LiveReportSettings settings, DataSource dataSource, Clock clock) {
        this(settings, dataSource, clock, MAX_QUEUED);
    }

    ReportQueue(ReportSettings settings, DataSource dataSource, Clock clock, int cap) {
        this(new LiveReportSettings(settings), dataSource, clock, cap);
    }

    private ReportQueue(LiveReportSettings settings, DataSource dataSource, Clock clock, int cap) {
        this.live = Objects.requireNonNull(settings, "settings");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cap < 1) {
            throw new IllegalArgumentException("cap must be positive");
        }
        this.cap = cap;
        this.persistedQueued = Db.transaction(
                dataSource, connection -> count(connection, "report_queue") + count(connection, "rollback_queue"));
    }

    private ReportSettings settings() {
        return live.get();
    }

    public void record(String conch, String version, String action, ExecutionResult result) {
        if (!settings().enabled()) {
            return;
        }
        requireText(conch, 256, "conch");
        requireText(version, 64, "version");
        requireText(action, 256, "action");
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        synchronized (lock) {
            if (!result.success()) {
                reserveSlot();
                memoryFailures.add(queuedFailure(conch, version, action, result));
            }
            bumpMemory(conch, version, action, result);
        }
    }

    public static final String MANUAL_UPDATE = "MANUAL_UPDATE";

    /**
     * Queues a version-change event on the existing rollback channel. Callers pass
     * {@link #MANUAL_UPDATE} after a user installs a higher version. Disabled reporting
     * returns without writing a row.
     */
    public void enqueueVersionChange(String conch, String fromVersion, String toVersion, String reason, Instant at) {
        enqueueRollback(conch, fromVersion, toVersion, reason, at);
    }

    public void enqueueRollback(String conch, String fromVersion, String toVersion, String reason, Instant at) {
        if (!settings().enabled()) {
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
            reserveSlot();
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
                refreshPersisted(connection);
                return null;
            });
        }
    }

    public long dropped() {
        synchronized (lock) {
            return Db.transaction(dataSource, this::droppedAmount) + memoryDropped;
        }
    }

    /**
     * Writes in-memory hourly counts and queued failures in one transaction.
     * An abnormal exit can lose counts and failures that have not reached this method.
     * The background flush runs every {@link #MEMORY_FLUSH_INTERVAL}.
     */
    public void flushMemory() {
        synchronized (lock) {
            flushMemoryLocked();
        }
    }

    /** Flushes memory and reads the pending batch while holding the queue lock. */
    Pending flushAndPeek() {
        synchronized (lock) {
            flushMemoryLocked();
            return Db.transaction(dataSource, this::readPending);
        }
    }

    private void flushMemoryLocked() {
        if (memoryFailures.isEmpty() && memoryCounts.isEmpty() && memoryDropped == 0) {
            return;
        }
        List<QueuedFailure> failures = List.copyOf(memoryFailures);
        LinkedHashMap<CountKey, MutableHour> counts = new LinkedHashMap<>(memoryCounts);
        int dropped = memoryDropped;
        Db.transaction(dataSource, connection -> {
            if (dropped > 0) {
                addDropped(connection, dropped);
            }
            for (QueuedFailure failure : failures) {
                makeRoom(connection, 1);
                insertQueued(connection, failure);
            }
            for (Map.Entry<CountKey, MutableHour> entry : counts.entrySet()) {
                mergeHour(connection, entry.getKey(), entry.getValue());
            }
            refreshPersisted(connection);
            return null;
        });
        memoryFailures.clear();
        memoryCounts.clear();
        memoryDropped = 0;
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
                refreshPersisted(connection);
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
                settings().instanceId().toString(),
                settings().crabVersion(),
                clock.instant(),
                failures,
                counts,
                events);
        return includeMemory(connection, new Pending(batch, failureIds, snapshots, eventIds), events.size(), failures.size());
    }

    private void reserveSlot() {
        while (memoryFailures.size() + persistedQueued + 1 > cap) {
            int before = memoryFailures.size() + persistedQueued;
            if (!dropOneOldest() || memoryFailures.size() + persistedQueued >= before) {
                return;
            }
        }
    }

    private boolean dropOneOldest() {
        if (memoryFailures.isEmpty() && persistedQueued <= 0) {
            return false;
        }
        if (persistedQueued <= 0) {
            memoryFailures.remove(0);
            memoryDropped++;
            return true;
        }
        if (memoryFailures.isEmpty()) {
            Db.transaction(dataSource, connection -> {
                makeRoom(connection, 1);
                refreshPersisted(connection);
                return null;
            });
            return true;
        }
        Instant memoryAt = memoryFailures.get(0).at();
        boolean dropMemory = Db.transaction(dataSource, connection -> {
            RowRef failure = oldest(connection, "report_queue");
            RowRef event = oldest(connection, "rollback_queue");
            RowRef db = event == null || (failure != null && !failure.at().isAfter(event.at())) ? failure : event;
            if (db == null || !memoryAt.isAfter(db.at())) {
                return Boolean.TRUE;
            }
            deleteId(connection, db == failure ? "report_queue" : "rollback_queue", db.id());
            addDropped(connection, 1);
            refreshPersisted(connection);
            return Boolean.FALSE;
        });
        if (dropMemory) {
            memoryFailures.remove(0);
            memoryDropped++;
        }
        return true;
    }

    private void refreshPersisted(Connection connection) throws SQLException {
        persistedQueued = count(connection, "report_queue") + count(connection, "rollback_queue");
    }

    private QueuedFailure queuedFailure(String conch, String version, String action, ExecutionResult result) {
        ExecutionError error = result.error();
        ErrorCode code = error == null || error.code() == null ? ErrorCode.INTERNAL : error.code();
        Integer stepIndex = error == null ? null : error.failedStepIndex();
        String stepType = error == null || error.failedStepType() == null ? null : truncate(error.failedStepType(), 64);
        return new QueuedFailure(
                traceId(result.traceId()),
                clock.instant(),
                conch,
                version,
                action,
                code.name(),
                stepIndex,
                stepType,
                Math.max(0L, result.durationMillis()));
    }

    private void bumpMemory(String conch, String version, String action, ExecutionResult result) {
        CountKey key = new CountKey(clock.instant().truncatedTo(ChronoUnit.HOURS), conch, version, action);
        MutableHour hour = memoryCounts.computeIfAbsent(key, ignored -> new MutableHour());
        long duration = Math.max(0L, result.durationMillis());
        hour.sumMs = saturatingAdd(hour.sumMs, duration);
        LatencyHistogram.add(hour.buckets, duration);
        if (result.success()) {
            hour.success++;
            return;
        }
        ErrorCode code = result.error() == null || result.error().code() == null
                ? ErrorCode.INTERNAL
                : result.error().code();
        hour.codes.merge(code.name(), 1L, Long::sum);
    }

    private Pending includeMemory(Connection connection, Pending pending, int eventCount, int dbFailures)
            throws SQLException {
        if (memoryFailures.isEmpty() && memoryCounts.isEmpty()) {
            return pending;
        }
        int failureRoom = Math.max(0, MAX_BATCH - eventCount - dbFailures);
        List<FailureReport> failures = new ArrayList<>(pending.batch().failures());
        int added = 0;
        for (QueuedFailure failure : memoryFailures) {
            if (added >= failureRoom) {
                break;
            }
            failures.add(failureReport(failure));
            added++;
        }
        int countRoom = Math.max(0, MAX_BATCH - eventCount - failures.size());
        List<CountReport> counts;
        List<CountSnapshot> snapshots;
        if (memoryCounts.isEmpty()) {
            counts = pending.batch().counts();
            snapshots = pending.counts();
            if (counts.size() > countRoom) {
                counts = List.copyOf(counts.subList(0, countRoom));
                snapshots = List.copyOf(snapshots.subList(0, countRoom));
            }
        } else {
            counts = new ArrayList<>();
            snapshots = new ArrayList<>();
            overlayCounts(connection, counts, snapshots, countRoom);
        }
        ReportBatch batch = new ReportBatch(
                pending.batch().instanceId(),
                pending.batch().crabVersion(),
                pending.batch().sentAt(),
                failures,
                counts,
                pending.batch().events());
        return new Pending(batch, pending.failureIds(), snapshots, pending.eventIds());
    }

    private void overlayCounts(
            Connection connection,
            List<CountReport> counts,
            List<CountSnapshot> snapshots,
            int room
    ) throws SQLException {
        LinkedHashMap<CountKey, DbHour> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT "hour", conch, version, action, success_count, failure_count_by_code,
                       sum_ms, latency_buckets, sent_total, sent_codes, sent_sum_ms, sent_buckets
                FROM hourly_counts
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                CountKey key = new CountKey(
                        Db.instant(resultSet, "hour"),
                        resultSet.getString("conch"),
                        resultSet.getString("version"),
                        resultSet.getString("action"));
                rows.put(key, DbHour.from(resultSet));
            }
        }
        LinkedHashMap<CountKey, MutableHour> memory = new LinkedHashMap<>(memoryCounts);
        for (CountKey key : rows.keySet()) {
            memory.putIfAbsent(key, null);
        }
        for (Map.Entry<CountKey, MutableHour> entry : memory.entrySet()) {
            if (counts.size() >= room) {
                return;
            }
            DbHour row = rows.get(entry.getKey());
            MutableHour extra = entry.getValue();
            long success = (row == null ? 0 : row.success) + (extra == null ? 0 : extra.success);
            Map<String, Long> codes = new LinkedHashMap<>();
            if (row != null) {
                codes.putAll(FailureCodes.read(row.failureJson));
            }
            if (extra != null) {
                extra.codes.forEach((code, amount) -> codes.merge(code, amount, Long::sum));
            }
            long failure = FailureCodes.total(codes);
            long sumMs = saturatingAdd(row == null ? 0 : row.sumMs, extra == null ? 0 : extra.sumMs);
            long[] buckets = LatencyHistogram.empty();
            if (row != null) {
                long[] stored = LatencyHistogram.read(row.bucketsJson);
                System.arraycopy(stored, 0, buckets, 0, Math.min(stored.length, buckets.length));
            }
            if (extra != null) {
                for (int i = 0; i < buckets.length && i < extra.buckets.length; i++) {
                    buckets[i] += extra.buckets[i];
                }
            }
            String failureJson = FailureCodes.write(codes);
            String bucketsJson = LatencyHistogram.write(buckets);
            if (fullySent(row, success, failure, failureJson, sumMs, bucketsJson)) {
                continue;
            }
            long calls = success + failure;
            counts.add(new CountReport(
                    entry.getKey().conch(),
                    entry.getKey().version(),
                    entry.getKey().action(),
                    entry.getKey().hour(),
                    success,
                    failure,
                    LatencyHistogram.average(sumMs, calls),
                    LatencyHistogram.p95(buckets)));
            snapshots.add(new CountSnapshot(
                    entry.getKey().hour(),
                    entry.getKey().conch(),
                    entry.getKey().version(),
                    entry.getKey().action(),
                    success,
                    failure,
                    failureJson,
                    sumMs,
                    bucketsJson));
        }
    }

    private static boolean fullySent(
            DbHour row,
            long success,
            long failure,
            String failureJson,
            long sumMs,
            String bucketsJson
    ) {
        if (row == null || row.sentTotal == null || row.sentSum == null) {
            return false;
        }
        return row.sentTotal == success + failure
                && Objects.equals(failureJson, row.sentCodes)
                && row.sentSum == sumMs
                && Objects.equals(bucketsJson, row.sentBuckets);
    }

    private static FailureReport failureReport(QueuedFailure failure) {
        return new FailureReport(
                failure.traceId(),
                failure.conch(),
                failure.version(),
                failure.action(),
                failure.stepIndex(),
                failure.stepType(),
                ErrorCode.valueOf(failure.errorCode()),
                null,
                failure.at());
    }

    private void insertQueued(Connection connection, QueuedFailure failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_queue (
                    trace_id, at, conch, version, action, error_code, step_index, step_type, duration_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, failure.traceId());
            Db.setInstant(statement, 2, failure.at());
            statement.setString(3, failure.conch());
            statement.setString(4, failure.version());
            statement.setString(5, failure.action());
            statement.setString(6, failure.errorCode());
            if (failure.stepIndex() == null) {
                statement.setNull(7, Types.INTEGER);
            } else {
                statement.setInt(7, failure.stepIndex());
            }
            if (failure.stepType() == null) {
                statement.setNull(8, Types.VARCHAR);
            } else {
                statement.setString(8, failure.stepType());
            }
            statement.setLong(9, failure.durationMillis());
            statement.executeUpdate();
        }
    }

    private void mergeHour(Connection connection, CountKey key, MutableHour extra) throws SQLException {
        long success = extra.success;
        Map<String, Long> codes = new LinkedHashMap<>(extra.codes);
        long sumMs = extra.sumMs;
        long[] buckets = LatencyHistogram.empty();
        System.arraycopy(extra.buckets, 0, buckets, 0, extra.buckets.length);
        boolean found = false;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT success_count, failure_count_by_code, sum_ms, latency_buckets
                FROM hourly_counts
                WHERE "hour" = ? AND conch = ? AND version = ? AND action = ?
                """)) {
            Db.setInstant(statement, 1, key.hour());
            statement.setString(2, key.conch());
            statement.setString(3, key.version());
            statement.setString(4, key.action());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    found = true;
                    success += resultSet.getLong("success_count");
                    FailureCodes.read(resultSet.getString("failure_count_by_code"))
                            .forEach((code, amount) -> codes.merge(code, amount, Long::sum));
                    sumMs = saturatingAdd(sumMs, resultSet.getLong("sum_ms"));
                    long[] stored = LatencyHistogram.read(resultSet.getString("latency_buckets"));
                    for (int i = 0; i < buckets.length && i < stored.length; i++) {
                        buckets[i] += stored[i];
                    }
                }
            }
        }
        String failureJson = FailureCodes.write(codes);
        String bucketsJson = LatencyHistogram.write(buckets);
        if (found) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE hourly_counts
                    SET success_count = ?, failure_count_by_code = ?, sum_ms = ?, latency_buckets = ?
                    WHERE "hour" = ? AND conch = ? AND version = ? AND action = ?
                    """)) {
                statement.setLong(1, success);
                statement.setString(2, failureJson);
                statement.setLong(3, sumMs);
                statement.setString(4, bucketsJson);
                Db.setInstant(statement, 5, key.hour());
                statement.setString(6, key.conch());
                statement.setString(7, key.version());
                statement.setString(8, key.action());
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO hourly_counts (
                    "hour", conch, version, action, success_count, failure_count_by_code, sum_ms, latency_buckets
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            Db.setInstant(statement, 1, key.hour());
            statement.setString(2, key.conch());
            statement.setString(3, key.version());
            statement.setString(4, key.action());
            statement.setLong(5, success);
            statement.setString(6, failureJson);
            statement.setLong(7, sumMs);
            statement.setString(8, bucketsJson);
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

    private record CountKey(Instant hour, String conch, String version, String action) {
    }

    private static final class MutableHour {
        private long success;
        private final Map<String, Long> codes = new LinkedHashMap<>();
        private long sumMs;
        private final long[] buckets = LatencyHistogram.empty();
    }

    private record QueuedFailure(
            String traceId,
            Instant at,
            String conch,
            String version,
            String action,
            String errorCode,
            Integer stepIndex,
            String stepType,
            long durationMillis
    ) {
    }

    private static final class DbHour {
        private final long success;
        private final String failureJson;
        private final long sumMs;
        private final String bucketsJson;
        private final Long sentTotal;
        private final String sentCodes;
        private final Long sentSum;
        private final String sentBuckets;

        private DbHour(
                long success,
                String failureJson,
                long sumMs,
                String bucketsJson,
                Long sentTotal,
                String sentCodes,
                Long sentSum,
                String sentBuckets
        ) {
            this.success = success;
            this.failureJson = failureJson;
            this.sumMs = sumMs;
            this.bucketsJson = bucketsJson;
            this.sentTotal = sentTotal;
            this.sentCodes = sentCodes;
            this.sentSum = sentSum;
            this.sentBuckets = sentBuckets;
        }

        private static DbHour from(ResultSet resultSet) throws SQLException {
            return new DbHour(
                    resultSet.getLong("success_count"),
                    resultSet.getString("failure_count_by_code"),
                    resultSet.getLong("sum_ms"),
                    resultSet.getString("latency_buckets"),
                    resultSet.getObject("sent_total") == null ? null : resultSet.getLong("sent_total"),
                    resultSet.getString("sent_codes"),
                    resultSet.getObject("sent_sum_ms") == null ? null : resultSet.getLong("sent_sum_ms"),
                    resultSet.getString("sent_buckets"));
        }
    }
}
