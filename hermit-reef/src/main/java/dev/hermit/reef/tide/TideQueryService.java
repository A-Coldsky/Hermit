package dev.hermit.reef.tide;

import dev.hermit.common.version.SemVer;
import dev.hermit.reef.tide.FailureDrilldown.FailureGroup;
import dev.hermit.reef.tide.FailureDrilldown.FailureSample;
import dev.hermit.reef.tide.RollbackList.RollbackItem;
import dev.hermit.reef.tide.TideSummary.TidePoint;
import dev.hermit.reef.tide.VersionCompare.VersionStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TideQueryService {

    static final Duration DEFAULT_SPAN = Duration.ofDays(7);
    /** 按天汇总时在哪个时区切日。不传 {@code tz} 时按北京时间，北京时间凌晨的调用不再记到前一天。 */
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    static final Duration MAX_SPAN = Duration.ofDays(400);
    static final int MAX_COMPARE_VERSIONS = 50;
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
        return summary(conch, from, to, bucket, null);
    }

    /** {@code tz} 是 IANA 时区名，例如 {@code Asia/Shanghai}；只影响按天汇总从哪个零点切开。 */
    public TideSummary summary(String conch, String from, String to, String bucket, String tz) {
        String unit = bucket == null || bucket.isBlank() ? "day" : bucket;
        if (!unit.equals("hour") && !unit.equals("day")) {
            throw TideErrors.unprocessable();
        }
        ZoneId zone = zone(tz);
        TimeRange range = range(from, to);
        String conchFilter = blankToNull(conch);
        // 实例和版本在库里先合并到每小时一行，Java 只再按天合并，不把所有实例的明细读进内存。
        List<CountRow> rows = jdbc.query(
                "SELECT conch, action, hour_start, SUM(success) AS success, SUM(failure) AS failure, "
                        + "SUM(CASE WHEN avg_millis IS NOT NULL AND success + failure > 0 "
                        + "THEN avg_millis * (success + failure) ELSE 0 END) AS weighted, "
                        + "SUM(CASE WHEN avg_millis IS NOT NULL AND success + failure > 0 "
                        + "THEN success + failure ELSE 0 END) AS weight, "
                        + "MAX(p95_millis) AS p95_millis FROM hermit_hourly_counts "
                        + "WHERE hour_start >= ? AND hour_start < ? AND (? IS NULL OR conch = ?) "
                        + "GROUP BY conch, action, hour_start",
                (rs, rowNum) -> new CountRow(
                        rs.getString("conch"),
                        rs.getString("action"),
                        rs.getTimestamp("hour_start").toInstant(),
                        rs.getLong("success"),
                        rs.getLong("failure"),
                        decimal(rs.getBigDecimal("weighted")),
                        rs.getLong("weight"),
                        asLong(rs.getObject("p95_millis"))),
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                conchFilter,
                conchFilter);
        Map<BucketKey, Accumulator> grouped = new LinkedHashMap<>();
        for (CountRow row : rows) {
            Instant bucketStart = "hour".equals(unit)
                    ? row.hourStart()
                    : row.hourStart().atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant();
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
        // 计数在库里分组；每组最新 5 条用窗口函数取，不把整个时间段的失败明细读进内存。
        String where = "WHERE f.occurred_at >= ? AND f.occurred_at < ? "
                + "AND (? IS NULL OR f.conch = ?) AND (? IS NULL OR f.conch_version = ?) "
                + "AND (? IS NULL OR f.action = ?) ";
        Object[] args = {
                Timestamp.from(range.from()),
                Timestamp.from(range.to()),
                conchFilter,
                conchFilter,
                versionFilter,
                versionFilter,
                actionFilter,
                actionFilter
        };
        Map<FailureKey, FailureAccum> grouped = new LinkedHashMap<>();
        jdbc.query(
                "SELECT f.conch, f.conch_version, f.action, f.step_index, f.step_type, f.error_code, "
                        + "u.username AS owner, COUNT(*) AS n, MAX(f.occurred_at) AS latest "
                        + "FROM hermit_failure_reports f "
                        + "LEFT JOIN hermit_admin_users u ON u.id = f.owner_user_id "
                        + where
                        + "GROUP BY f.conch, f.conch_version, f.action, f.step_index, f.step_type, f.error_code, u.username",
                rs -> {
                    FailureAccum accum = grouped.computeIfAbsent(failureKey(rs), key -> new FailureAccum());
                    accum.count = rs.getLong("n");
                    accum.latest = rs.getTimestamp("latest").toInstant();
                },
                args);
        jdbc.query(
                "SELECT conch, conch_version, action, step_index, step_type, error_code, owner, summary, trace_id "
                        + "FROM (SELECT f.conch, f.conch_version, f.action, f.step_index, f.step_type, f.error_code, "
                        + "u.username AS owner, f.summary, f.trace_id, ROW_NUMBER() OVER ("
                        + "PARTITION BY f.conch, f.conch_version, f.action, f.step_index, f.step_type, f.error_code, "
                        + "u.username ORDER BY f.occurred_at DESC, f.id DESC) AS rn "
                        + "FROM hermit_failure_reports f "
                        + "LEFT JOIN hermit_admin_users u ON u.id = f.owner_user_id "
                        + where
                        + ") ranked WHERE rn <= " + RECENT_SUMMARIES + " ORDER BY rn",
                rs -> {
                    FailureAccum accum = grouped.get(failureKey(rs));
                    if (accum != null && accum.recent.size() < RECENT_SUMMARIES) {
                        accum.recent.add(new FailureSample(
                                rs.getString("summary"), rs.getString("trace_id"), rs.getString("owner")));
                    }
                },
                args);
        List<FailureGroup> groups = new ArrayList<>();
        Map<FailureGroup, Instant> latest = new IdentityHashMap<>();
        for (Map.Entry<FailureKey, FailureAccum> entry : grouped.entrySet()) {
            FailureKey key = entry.getKey();
            FailureAccum accum = entry.getValue();
            FailureGroup group = new FailureGroup(
                    key.conch(),
                    key.conchVersion(),
                    key.action(),
                    key.stepIndex(),
                    key.stepType(),
                    key.errorCode(),
                    key.owner(),
                    accum.count,
                    List.copyOf(accum.recent));
            groups.add(group);
            latest.put(group, accum.latest);
        }
        groups.sort(Comparator.comparingLong(FailureGroup::count).reversed()
                .thenComparing(FailureGroup::conch, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::conchVersion, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::action, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::errorCode, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::owner, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(latest::get, Comparator.reverseOrder())
                .thenComparing(FailureGroup::stepIndex, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FailureGroup::stepType, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new FailureDrilldown(List.copyOf(groups));
    }

    public VersionCompare compare(String conch, String versions) {
        if (conch == null || conch.isBlank() || versions == null || versions.isBlank()) {
            throw TideErrors.unprocessable();
        }
        String[] parts = versions.split(",", MAX_COMPARE_VERSIONS + 1);
        if (parts.length > MAX_COMPARE_VERSIONS) {
            throw TideErrors.unprocessable();
        }
        List<String> requested = new ArrayList<>(parts.length);
        for (String part : parts) {
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

    private static ZoneId zone(String tz) {
        if (tz == null || tz.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(tz.trim());
        } catch (DateTimeException ex) {
            throw TideErrors.unprocessable();
        }
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

    /** 一小时内所有实例、版本合在一起。{@code weighted} 是平均耗时乘调用数之和，{@code weight} 是对应的调用数。 */
    private record CountRow(
            String conch,
            String action,
            Instant hourStart,
            long success,
            long failure,
            BigDecimal weighted,
            long weight,
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
            weighted = weighted.add(row.weighted());
            weight += row.weight();
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

    private record FailureKey(
            String conch,
            String conchVersion,
            String action,
            Integer stepIndex,
            String stepType,
            String errorCode,
            String owner) {
    }

    private static FailureKey failureKey(ResultSet rs) throws SQLException {
        return new FailureKey(
                rs.getString("conch"),
                rs.getString("conch_version"),
                rs.getString("action"),
                rs.getObject("step_index", Integer.class),
                rs.getString("step_type"),
                rs.getString("error_code"),
                rs.getString("owner"));
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class FailureAccum {
        private long count;
        private Instant latest;
        private final List<FailureSample> recent = new ArrayList<>();
    }
}
