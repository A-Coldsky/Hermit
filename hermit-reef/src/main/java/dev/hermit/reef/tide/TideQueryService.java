package dev.hermit.reef.tide;

import dev.hermit.common.version.SemVer;
import dev.hermit.reef.tide.FailureDrilldown.FailureGroup;
import dev.hermit.reef.tide.FailureDrilldown.FailureSample;
import dev.hermit.reef.tide.RollbackList.RollbackItem;
import dev.hermit.reef.tide.TideSummary.TidePoint;
import dev.hermit.reef.tide.VersionCompare.VersionStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TideQueryService {

    static final Duration DEFAULT_SPAN = Duration.ofDays(7);
    static final Duration MAX_SPAN = Duration.ofDays(400);
    private static final int RECENT_SUMMARIES = 5;
    private static final int RATE_SCALE = 6;
    private static final int AVG_SCALE = 3;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TideQueryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public TideSummary summary(String shell, String from, String to, String bucket) {
        String unit = bucket == null || bucket.isBlank() ? "day" : bucket;
        if (!unit.equals("hour") && !unit.equals("day")) {
            throw TideErrors.unprocessable();
        }
        TimeRange range = range(from, to);
        String shellFilter = blankToNull(shell);
        List<CountRow> rows = jdbc.query(
                "SELECT shell, action, hour_start, success, failure, avg_millis, p95_millis FROM hourly_counts "
                        + "WHERE hour_start >= ? AND hour_start < ? AND (? IS NULL OR shell = ?)",
                (rs, rowNum) -> new CountRow(
                        rs.getString("shell"),
                        rs.getString("action"),
                        rs.getTimestamp("hour_start").toInstant(),
                        rs.getLong("success"),
                        rs.getLong("failure"),
                        asLong(rs.getObject("avg_millis")),
                        asLong(rs.getObject("p95_millis"))),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                shellFilter,
                shellFilter);
        Map<BucketKey, Accumulator> grouped = new LinkedHashMap<>();
        for (CountRow row : rows) {
            Instant bucketStart = "hour".equals(unit)
                    ? row.hourStart()
                    : row.hourStart().truncatedTo(ChronoUnit.DAYS);
            grouped.computeIfAbsent(new BucketKey(row.shell(), row.action(), bucketStart), key -> new Accumulator())
                    .add(row);
        }
        List<TidePoint> series = new ArrayList<>();
        for (Map.Entry<BucketKey, Accumulator> entry : grouped.entrySet()) {
            BucketKey key = entry.getKey();
            series.add(entry.getValue().toPoint(key.shell(), key.action(), key.bucketStart()));
        }
        series.sort(Comparator.comparing(TidePoint::bucketStart)
                .thenComparing(TidePoint::shell)
                .thenComparing(TidePoint::action));
        return new TideSummary(unit, range.from(), range.to(), TideSummary.P95_NOTE, List.copyOf(series));
    }

    public FailureDrilldown failures(String shell, String version, String action, String from, String to) {
        TimeRange range = range(from, to);
        String shellFilter = blankToNull(shell);
        String versionFilter = blankToNull(version);
        String actionFilter = blankToNull(action);
        List<FailureRow> rows = jdbc.query(
                "SELECT id, shell, shell_version, action, step_index, step_type, error_code, summary, trace_id "
                        + "FROM failure_reports WHERE occurred_at >= ? AND occurred_at < ? "
                        + "AND (? IS NULL OR shell = ?) AND (? IS NULL OR shell_version = ?) "
                        + "AND (? IS NULL OR action = ?) ORDER BY occurred_at DESC, id DESC",
                (rs, rowNum) -> new FailureRow(
                        rs.getString("shell"),
                        rs.getString("shell_version"),
                        rs.getString("action"),
                        (Integer) rs.getObject("step_index"),
                        rs.getString("step_type"),
                        rs.getString("error_code"),
                        rs.getString("summary"),
                        rs.getString("trace_id")),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                shellFilter,
                shellFilter,
                versionFilter,
                versionFilter,
                actionFilter,
                actionFilter);
        Map<FailureKey, FailureAccum> grouped = new LinkedHashMap<>();
        for (FailureRow row : rows) {
            FailureAccum accum = grouped.computeIfAbsent(
                    new FailureKey(row.shell(), row.shellVersion(), row.action(), row.stepIndex(), row.stepType(),
                            row.errorCode()),
                    key -> new FailureAccum());
            accum.count++;
            if (accum.recent.size() < RECENT_SUMMARIES) {
                accum.recent.add(new FailureSample(row.summary(), row.traceId()));
            }
        }
        List<FailureGroup> groups = new ArrayList<>();
        for (Map.Entry<FailureKey, FailureAccum> entry : grouped.entrySet()) {
            FailureKey key = entry.getKey();
            FailureAccum accum = entry.getValue();
            groups.add(new FailureGroup(
                    key.shell(),
                    key.shellVersion(),
                    key.action(),
                    key.stepIndex(),
                    key.stepType(),
                    key.errorCode(),
                    accum.count,
                    List.copyOf(accum.recent)));
        }
        groups.sort(Comparator.comparingLong(FailureGroup::count).reversed()
                .thenComparing(FailureGroup::shell, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::shellVersion, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::action, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::errorCode, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new FailureDrilldown(List.copyOf(groups));
    }

    public VersionCompare compare(String shell, String versions) {
        if (shell == null || shell.isBlank() || versions == null || versions.isBlank()) {
            throw TideErrors.unprocessable();
        }
        List<String> requested = new ArrayList<>();
        for (String part : versions.split(",", -1)) {
            String version = part.trim();
            requireCanonicalVersion(version);
            requested.add(version);
        }
        List<VersionStats> stats = new ArrayList<>();
        for (String version : requested) {
            long[] totals = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(success), 0), COALESCE(SUM(failure), 0) FROM hourly_counts "
                            + "WHERE shell = ? AND shell_version = ?",
                    (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)},
                    shell,
                    version);
            long success = totals == null ? 0 : totals[0];
            long failure = totals == null ? 0 : totals[1];
            long calls = success + failure;
            stats.add(new VersionStats(version, calls, failure, rate(failure, calls)));
        }
        return new VersionCompare(shell, List.copyOf(stats));
    }

    public RollbackList rollbacks(String shell, String from, String to) {
        TimeRange range = range(from, to);
        String shellFilter = blankToNull(shell);
        List<RollbackItem> events = jdbc.query(
                "SELECT id, instance_id, shell, from_version, to_version, reason, occurred_at, received_at "
                        + "FROM rollback_events WHERE occurred_at >= ? AND occurred_at < ? "
                        + "AND (? IS NULL OR shell = ?) ORDER BY occurred_at DESC, id DESC",
                (rs, rowNum) -> new RollbackItem(
                        rs.getLong("id"),
                        rs.getString("instance_id"),
                        rs.getString("shell"),
                        rs.getString("from_version"),
                        rs.getString("to_version"),
                        rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("received_at").toInstant()),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                shellFilter,
                shellFilter);
        return new RollbackList(List.copyOf(events));
    }

    private TimeRange range(String from, String to) {
        Instant end = blankToNull(to) == null ? clock.instant() : parseInstant(to);
        Instant start = blankToNull(from) == null ? end.minus(DEFAULT_SPAN) : parseInstant(from);
        if (start.isAfter(end)) {
            throw TideErrors.unprocessable();
        }
        Duration span;
        try {
            span = Duration.between(start, end);
        } catch (ArithmeticException ex) {
            throw TideErrors.unprocessable();
        }
        if (span.compareTo(MAX_SPAN) > 0) {
            throw TideErrors.unprocessable();
        }
        return new TimeRange(start, end);
    }

    private static Instant parseInstant(String text) {
        String value = text.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ex) {
                throw TideErrors.unprocessable();
            }
        }
    }

    private static void requireCanonicalVersion(String version) {
        try {
            if (version.isEmpty() || !SemVer.parse(version).toString().equals(version)) {
                throw TideErrors.unprocessable();
            }
        } catch (IllegalArgumentException ex) {
            throw TideErrors.unprocessable();
        }
    }

    static BigDecimal rate(long failure, long calls) {
        if (calls <= 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(failure).divide(BigDecimal.valueOf(calls), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private record TimeRange(Instant from, Instant to) {
    }

    private record CountRow(
            String shell,
            String action,
            Instant hourStart,
            long success,
            long failure,
            Long avgMillis,
            Long p95Millis) {
    }

    private record BucketKey(String shell, String action, Instant bucketStart) {
    }

    private static final class Accumulator {
        private long success;
        private long failure;
        private BigDecimal weighted = BigDecimal.ZERO;
        private long weight;
        private Long p95;

        void add(CountRow row) {
            success += row.success();
            failure += row.failure();
            long calls = row.success() + row.failure();
            if (row.avgMillis() != null && calls > 0) {
                weighted = weighted.add(BigDecimal.valueOf(row.avgMillis()).multiply(BigDecimal.valueOf(calls)));
                weight += calls;
            }
            if (row.p95Millis() != null) {
                p95 = p95 == null ? row.p95Millis() : Math.max(p95, row.p95Millis());
            }
        }

        TidePoint toPoint(String shell, String action, Instant bucketStart) {
            long calls = success + failure;
            BigDecimal avg = weight == 0
                    ? null
                    : weighted.divide(BigDecimal.valueOf(weight), AVG_SCALE, RoundingMode.HALF_UP);
            return new TidePoint(shell, action, bucketStart, success, failure, rate(failure, calls), avg, p95);
        }
    }

    private record FailureRow(
            String shell,
            String shellVersion,
            String action,
            Integer stepIndex,
            String stepType,
            String errorCode,
            String summary,
            String traceId) {
    }

    private record FailureKey(
            String shell,
            String shellVersion,
            String action,
            Integer stepIndex,
            String stepType,
            String errorCode) {
    }

    private static final class FailureAccum {
        private long count;
        private final List<FailureSample> recent = new ArrayList<>();
    }
}
