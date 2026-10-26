package dev.hermit.reef.tide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.error.ErrorCode;
import dev.hermit.reef.tide.AlertList.AlertItem;
import dev.hermit.reef.tide.TideWebhookClient.WebhookNotice;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AlertService {

    private final JdbcTemplate jdbc;
    private final TideProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TideWebhookClient webhook;
    private final TransactionTemplate transactions;

    public AlertService(JdbcTemplate jdbc, TideProperties properties, Clock clock, ObjectMapper objectMapper,
            TideWebhookClient webhook, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.webhook = webhook;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void evaluate() {
        Instant now = clock.instant();
        List<WebhookNotice> created = transactions.execute(status -> detect(now));
        if (created == null) {
            return;
        }
        for (WebhookNotice notice : created) {
            webhook.send(notice);
        }
    }

    public AlertList list(String status) {
        String filter = status == null || status.isBlank() ? "open" : status;
        if (!filter.equals("open") && !filter.equals("all")) {
            throw TideErrors.unprocessable();
        }
        String sql = "SELECT id, created_at, kind, conch, conch_version, detail_json, acknowledged_at, "
                + "acknowledged_by FROM alerts";
        if (filter.equals("open")) {
            sql += " WHERE acknowledged_at IS NULL";
        }
        sql += " ORDER BY created_at DESC, id DESC";
        return new AlertList(jdbc.query(sql, (rs, rowNum) -> mapAlert(rs)));
    }

    @Transactional
    public AlertItem ack(long id, String actor) {
        jdbc.update(
                "UPDATE alerts SET acknowledged_at = ?, acknowledged_by = ? WHERE id = ? AND acknowledged_at IS NULL",
                Timestamp.from(clock.instant()),
                actor,
                id);
        AlertItem item = find(id);
        if (item == null) {
            throw TideErrors.notFound();
        }
        return item;
    }

    private List<WebhookNotice> detect(Instant now) {
        List<WebhookNotice> created = new ArrayList<>();
        for (CurrentVersion current : currentVersions()) {
            considerRegression(current, created, now);
        }
        considerSurge(ErrorCode.RULE_MISMATCH, AlertKind.RULE_MISMATCH_SURGE, true, now, created);
        considerSurge(ErrorCode.UPSTREAM_ERROR, AlertKind.UPSTREAM_ERROR_SURGE, false, now, created);
        return created;
    }

    private void considerRegression(CurrentVersion current, List<WebhookNotice> created, Instant now) {
        Totals calls = totals(current.conch(), current.version(), current.publishedAt(), null);
        if (calls.calls() < properties.getNewVersionMinCalls()) {
            return;
        }
        String previous = previousPublished(current.conch(), current.publishedAt());
        Totals baseline = Totals.ZERO;
        String baselineVersion = null;
        if (previous != null) {
            Instant baselineFrom = current.publishedAt().minus(Duration.ofDays(properties.getBaselineWindowDays()));
            Totals window = totals(current.conch(), previous, baselineFrom, current.publishedAt());
            if (window.calls() >= properties.getNewVersionMinCalls()) {
                baseline = window;
                baselineVersion = previous;
            }
        }
        boolean fire = baselineVersion == null
                ? rateAbove(calls.failure(), calls.calls(), properties.getNoBaselineFailureRate())
                : deltaAtLeast(calls, baseline, properties.getRegressionDelta());
        if (!fire || duplicateWithin(AlertKind.NEW_VERSION_REGRESSION, current.conch(), current.version(), now)) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("currentCalls", calls.calls());
        detail.put("currentFailures", calls.failure());
        detail.put("baselineVersion", baselineVersion);
        detail.put("baselineCalls", baseline.calls());
        detail.put("baselineFailures", baseline.failure());
        created.add(insert(now, AlertKind.NEW_VERSION_REGRESSION, current.conch(), current.version(), detail));
    }

    private void considerSurge(ErrorCode errorCode, AlertKind kind, boolean requireQuiet, Instant now,
            List<WebhookNotice> created) {
        Instant from = now.minus(Duration.ofMinutes(properties.getSurgeWindowMinutes()));
        List<Surge> surges = jdbc.query(
                "SELECT conch, COUNT(*) AS cnt FROM failure_reports "
                        + "WHERE error_code = ? AND occurred_at >= ? AND occurred_at <= ? "
                        + "GROUP BY conch HAVING COUNT(*) >= ? ORDER BY conch",
                (rs, rowNum) -> new Surge(rs.getString("conch"), rs.getLong("cnt")),
                errorCode.name(),
                Timestamp.from(from),
                Timestamp.from(now),
                properties.getSurgeMinCount());
        for (Surge surge : surges) {
            if (requireQuiet && recentlyChanged(surge.conch(), now)) {
                continue;
            }
            if (duplicateWithin(kind, surge.conch(), null, now)) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorCode", errorCode.name());
            detail.put("count", surge.count());
            detail.put("windowMinutes", properties.getSurgeWindowMinutes());
            created.add(insert(now, kind, surge.conch(), null, detail));
        }
    }

    private boolean recentlyChanged(String conch, Instant now) {
        Instant since = now.minus(Duration.ofHours(properties.getQuietHours()));
        Long publishes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conch_versions WHERE conch = ? AND published_at >= ? AND published_at <= ?",
                Long.class,
                conch,
                Timestamp.from(since),
                Timestamp.from(now));
        if (publishes != null && publishes > 0) {
            return true;
        }
        Long rollbacks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE conch = ? AND action = 'ROLLBACK' AND at >= ? AND at <= ?",
                Long.class,
                conch,
                Timestamp.from(since),
                Timestamp.from(now));
        return rollbacks != null && rollbacks > 0;
    }

    // 窗口从 created_at 起算 dedup-hours。确认不重新计时，也不清掉剩余时间；失败次数上涨不提前打破。
    // created_at 早于或等于 now - dedup-hours 后，条件仍成立才新建。
    private boolean duplicateWithin(AlertKind kind, String conch, String version, Instant now) {
        Instant since = now.minus(Duration.ofHours(properties.getDedupHours()));
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE kind = ? AND conch = ? AND created_at > ? "
                        + "AND ((? IS NULL AND conch_version IS NULL) OR conch_version = ?)",
                Long.class,
                kind.name(),
                conch,
                Timestamp.from(since),
                version,
                version);
        return count != null && count > 0;
    }

    private WebhookNotice insert(Instant createdAt, AlertKind kind, String conch, String version,
            Map<String, Object> detail) {
        JsonNode node = objectMapper.valueToTree(detail);
        String json = node.toString();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO alerts (created_at, kind, conch, conch_version, detail_json) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setTimestamp(1, Timestamp.from(createdAt));
            statement.setString(2, kind.name());
            statement.setString(3, conch);
            if (version == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, version);
            }
            statement.setString(5, json);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("alert id was not generated");
        }
        return new WebhookNotice(id.longValue(), kind.name(), conch, version, node, createdAt,
                TideWebhookClient.ADMIN_ALERTS_PATH);
    }

    private List<CurrentVersion> currentVersions() {
        return jdbc.query(
                "SELECT conch, version, published_at FROM conch_versions "
                        + "WHERE is_current = TRUE AND status = 'PUBLISHED' AND published_at IS NOT NULL "
                        + "ORDER BY conch, version",
                (rs, rowNum) -> new CurrentVersion(
                        rs.getString("conch"),
                        rs.getString("version"),
                        rs.getTimestamp("published_at").toInstant()));
    }

    private String previousPublished(String conch, Instant publishedAt) {
        List<String> versions = jdbc.query(
                "SELECT version FROM conch_versions WHERE conch = ? AND status = 'PUBLISHED' AND published_at < ? "
                        + "ORDER BY published_at DESC, id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1),
                conch,
                Timestamp.from(publishedAt));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private Totals totals(String conch, String version, Instant fromInclusive, Instant toExclusive) {
        Totals totals;
        if (toExclusive == null) {
            totals = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(success), 0), COALESCE(SUM(failure), 0) FROM hourly_counts "
                            + "WHERE conch = ? AND conch_version = ? AND hour_start >= ?",
                    (rs, rowNum) -> new Totals(rs.getLong(1), rs.getLong(2)),
                    conch,
                    version,
                    Timestamp.from(fromInclusive));
        } else {
            totals = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(success), 0), COALESCE(SUM(failure), 0) FROM hourly_counts "
                            + "WHERE conch = ? AND conch_version = ? AND hour_start >= ? AND hour_start < ?",
                    (rs, rowNum) -> new Totals(rs.getLong(1), rs.getLong(2)),
                    conch,
                    version,
                    Timestamp.from(fromInclusive),
                    Timestamp.from(toExclusive));
        }
        return totals == null ? Totals.ZERO : totals;
    }

    private AlertItem find(long id) {
        List<AlertItem> items = jdbc.query(
                "SELECT id, created_at, kind, conch, conch_version, detail_json, acknowledged_at, acknowledged_by "
                        + "FROM alerts WHERE id = ?",
                (rs, rowNum) -> mapAlert(rs),
                id);
        return items.isEmpty() ? null : items.get(0);
    }

    private AlertItem mapAlert(java.sql.ResultSet rs) throws java.sql.SQLException {
        String detailJson = rs.getString("detail_json");
        JsonNode detail = null;
        if (detailJson != null && !detailJson.isBlank()) {
            try {
                detail = objectMapper.readTree(detailJson);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                detail = null;
            }
        }
        Timestamp acknowledged = rs.getTimestamp("acknowledged_at");
        return new AlertItem(
                rs.getLong("id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("kind"),
                rs.getString("conch"),
                rs.getString("conch_version"),
                detail,
                acknowledged == null ? null : acknowledged.toInstant(),
                rs.getString("acknowledged_by"));
    }

    static boolean rateAbove(long failures, long calls, BigDecimal threshold) {
        if (calls <= 0) {
            return false;
        }
        return BigDecimal.valueOf(failures).compareTo(threshold.multiply(BigDecimal.valueOf(calls))) > 0;
    }

    static boolean deltaAtLeast(Totals current, Totals baseline, BigDecimal threshold) {
        if (current.calls() <= 0 || baseline.calls() <= 0) {
            return false;
        }
        BigDecimal left = BigDecimal.valueOf(current.failure()).multiply(BigDecimal.valueOf(baseline.calls()))
                .subtract(BigDecimal.valueOf(baseline.failure()).multiply(BigDecimal.valueOf(current.calls())));
        BigDecimal right = threshold.multiply(BigDecimal.valueOf(current.calls()))
                .multiply(BigDecimal.valueOf(baseline.calls()));
        return left.compareTo(right) >= 0;
    }

    private record CurrentVersion(String conch, String version, Instant publishedAt) {
    }

    private record Surge(String conch, long count) {
    }

    record Totals(long success, long failure) {
        static final Totals ZERO = new Totals(0, 0);

        long calls() {
            return success + failure;
        }
    }
}
