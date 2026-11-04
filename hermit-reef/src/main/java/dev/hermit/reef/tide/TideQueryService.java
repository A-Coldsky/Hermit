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

    public TideSummary summary(String conch, String from, String to, String bucket) {
        String unit = bucket == null || bucket.isBlank() ? "day" : bucket;
        if (!unit.equals("hour") && !unit.equals("day")) {
            throw TideErrors.unprocessable();
        }
        TimeRange range = range(from, to);
        String conchFilter = blankToNull(conch);
        List<CountRow> rows = jdbc.query(
                "SELECT conch, action, hour_start, success, failure, avg_millis, p95_millis FROM hermit_hourly_counts "
                        + "WHERE hour_start >= ? AND hour_start < ? AND (? IS NULL OR conch = ?)",
                (rs, rowNum) -> new CountRow(
                        rs.getString("conch"),
                        rs.getString("action"),
                        rs.getTimestamp("hour_start").toInstant(),
                        rs.getLong("success"),
                        rs.getLong("failure"),
                        asLong(rs.getObject("avg_millis")),
                        asLong(rs.getObject("p95_millis"))),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                conchFilter,
                conchFilter);
        Map<BucketKey, Accumulator> grouped = new LinkedHashMap<>();
        for (CountRow row : rows) {
            Instant bucketStart = "hour".equals(unit)
                    ? row.hourStart()
                    : row.hourStart().truncatedTo(ChronoUnit.DAYS);
            grouped.computeIfAbsent(new BucketKey(row.conch(), row.action(), bucketStart), key -> new Accumulator())
                    .add(row);
        }
        List<TidePoint> series = new ArrayList<>();
        for (Map.Entry<BucketKey, Accumulator> entry : grouped.entrySet()) {
            BucketKey key = entry.getKey();
            series.add(entry.getValue().toPoint(key.conch(), key.action(), key.bucketStart()));
        }
        series.sort(Comparator.comparing(TidePoint::bucketStart)
                .thenComparing(TidePoint::conch)
                .thenComparing(TidePoint::action));
        return new TideSummary(unit, range.from(), range.to(), TideSummary.P95_NOTE, List.copyOf(series));
    }

    public FailureDrilldown failures(String conch, String version, String action, String from, String to) {
        TimeRange range = range(from, to);
        String conchFilter = blankToNull(conch);
        String versionFilter = blankToNull(version);
        String actionFilter = blankToNull(action);
        List<FailureRow> rows = jdbc.query(
                "SELECT f.id, f.conch, f.conch_version, f.action, f.step_index, f.step_type, f.error_code, "
                        + "f.summary, f.trace_id, u.username AS owner "
                        + "FROM hermit_failure_reports f "
                        + "LEFT JOIN hermit_admin_users u ON u.id = f.owner_user_id "
                        + "WHERE f.occurred_at >= ? AND f.occurred_at < ? "
                        + "AND (? IS NULL OR f.conch = ?) AND (? IS NULL OR f.conch_version = ?) "
                        + "AND (? IS NULL OR f.action = ?) ORDER BY f.occurred_at DESC, f.id DESC",
                (rs, rowNum) -> new FailureRow(
                        rs.getString("conch"),
                        rs.getString("conch_version"),
                        rs.getString("action"),
                        (Integer) rs.getObject("step_index"),
                        rs.getString("step_type"),
                        rs.getString("error_code"),
                        rs.getString("summary"),
                        rs.getString("trace_id"),
                        rs.getString("owner")),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                conchFilter,
                conchFilter,
                versionFilter,
                versionFilter,
                actionFilter,
                actionFilter);
        Map<FailureKey, FailureAccum> grouped = new LinkedHashMap<>();
        for (FailureRow row : rows) {
            FailureAccum accum = grouped.computeIfAbsent(
                    new FailureKey(row.conch(), row.conchVersion(), row.action(), row.stepIndex(), row.stepType(),
                            row.errorCode(), row.owner()),
                    key -> new FailureAccum());
            accum.count++;
            if (accum.recent.size() < RECENT_SUMMARIES) {
                accum.recent.add(new FailureSample(row.summary(), row.traceId(), row.owner()));
            }
        }
        List<FailureGroup> groups = new ArrayList<>();
        for (Map.Entry<FailureKey, FailureAccum> entry : grouped.entrySet()) {
            FailureKey key = entry.getKey();
            FailureAccum accum = entry.getValue();
            groups.add(new FailureGroup(
                    key.conch(),
                    key.conchVersion(),
                    key.action(),
                    key.stepIndex(),
                    key.stepType(),
                    key.errorCode(),
                    key.owner(),
                    accum.count,
                    List.copyOf(accum.recent)));
        }
        groups.sort(Comparator.comparingLong(FailureGroup::count).reversed()
                .thenComparing(FailureGroup::conch, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::conchVersion, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::action, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::errorCode, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::owner, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new FailureDrilldown(List.copyOf(groups));
    }

    public VersionCompare compare(String conch, String versions) {
        if (conch == null || conch.isBlank() || versions == null || versions.isBlank()) {
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
                    "SELECT COALESCE(SUM(success), 0), COALESCE(SUM(failure), 0) FROM hermit_hourly_counts "
                            + "WHERE conch = ? AND conch_version = ?",
                    (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)},
                    conch,
                    version);
            long success = totals == null ? 0 : totals[0];
            long failure = totals == null ? 0 : totals[1];
            long calls = success + failure;
            stats.add(new VersionStats(version, calls, failure, rate(failure, calls)));
        }
        return new VersionCompare(conch, List.copyOf(stats));
    }

    public RollbackList rollbacks(String conch, String from, String to) {
        TimeRange range = range(from, to);
        String conchFilter = blankToNull(conch);
        List<RollbackItem> events = jdbc.query(
                "SELECT e.id, e.instance_id, e.conch, e.from_version, e.to_version, e.reason, e.occurred_at, "
                        + "e.received_at, u.username AS owner FROM hermit_rollback_events e "
                        + "LEFT JOIN hermit_admin_users u ON u.id = e.owner_user_id "
                        + "WHERE e.occurred_at >= ? AND e.occurred_at < ? "
                        + "AND (? IS NULL OR e.conch = ?) ORDER BY e.occurred_at DESC, e.id DESC",
                (rs, rowNum) -> new RollbackItem(
                        rs.getLong("id"),
                        rs.getString("instance_id"),
                        rs.getString("conch"),
                        rs.getString("from_version"),
                        rs.getString("to_version"),
                        rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("received_at").toInstant(),
                        rs.getString("owner")),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                conchFilter,
                conchFilter);
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
            String conch,
            String action,
            Instant hourStart,
            long success,
            long failure,
            Long avgMillis,
            Long p95Millis) {
    }

    private record BucketKey(String conch, String action, Instant bucketStart) {
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

        TidePoint toPoint(String conch, String action, Instant bucketStart) {
            long calls = success + failure;
            BigDecimal avg = weight == 0
                    ? null
                    : weighted.divide(BigDecimal.valueOf(weight), AVG_SCALE, RoundingMode.HALF_UP);
            return new TidePoint(conch, action, bucketStart, success, failure, rate(failure, calls), avg, p95);
        }
    }

    private record FailureRow(
            String conch,
            String conchVersion,
            String action,
            Integer stepIndex,
            String stepType,
            String errorCode,
            String summary,
            String traceId,
            String owner) {
    }

    private record FailureKey(
            String conch,
            String conchVersion,
            String action,
            Integer stepIndex,
            String stepType,
            String errorCode,
            String owner) {
    }

    private static final class FailureAccum {
        private long count;
        private final List<FailureSample> recent = new ArrayList<>();
    }
}
