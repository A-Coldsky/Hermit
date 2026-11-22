package dev.hermit.reef.tide;

import dev.hermit.common.report.CountReport;
import dev.hermit.common.report.FailureReport;
import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.RollbackEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportIngestService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReportIngestService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ReportAccepted ingest(ReportBatch batch, Long ownerUserId) {
        Instant receivedAt = clock.instant();
        for (FailureReport failure : batch.failures()) {
            insertFailure(batch, failure, receivedAt, ownerUserId);
        }
        for (CountReport count : batch.counts()) {
            upsertCount(batch, count, ownerUserId);
        }
        for (RollbackEvent event : batch.events()) {
            insertRollback(batch, event, receivedAt, ownerUserId);
        }
        return new ReportAccepted(new ReportAccepted.Counts(
                batch.failures().size(), batch.counts().size(), batch.events().size()));
    }

    private void insertFailure(ReportBatch batch, FailureReport failure, Instant receivedAt, Long ownerUserId) {
        Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_failure_reports WHERE instance_id = ? AND trace_id = ?",
                Long.class,
                batch.instanceId(),
                failure.traceId());
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO hermit_failure_reports (received_at, instance_id, crab_version, trace_id, conch, "
                        + "conch_version, action, step_index, step_type, error_code, summary, occurred_at, "
                        + "owner_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(receivedAt),
                batch.instanceId(),
                batch.crabVersion(),
                failure.traceId(),
                failure.conch(),
                failure.conchVersion(),
                failure.action(),
                failure.stepIndex(),
                failure.stepType(),
                failure.errorCode().name(),
                failure.summary(),
                Timestamp.from(failure.occurredAt()),
                ownerUserId);
    }

    private void upsertCount(ReportBatch batch, CountReport count, Long ownerUserId) {
        Timestamp hour = Timestamp.from(count.hourStart());
        if (existsCount(batch, count, hour) && updateCount(batch, count, ownerUserId, hour) > 0) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO hermit_hourly_counts (conch, conch_version, action, hour_start, instance_id, success, "
                            + "failure, avg_millis, p95_millis, owner_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    count.conch(),
                    count.conchVersion(),
                    count.action(),
                    hour,
                    batch.instanceId(),
                    count.success(),
                    count.failure(),
                    count.avgMillis(),
                    count.p95Millis(),
                    ownerUserId);
        } catch (DuplicateKeyException ex) {
            updateCount(batch, count, ownerUserId, hour);
        }
    }

    private boolean existsCount(ReportBatch batch, CountReport count, Timestamp hour) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT 1 FROM hermit_hourly_counts "
                        + "WHERE conch = ? AND conch_version = ? AND action = ? AND hour_start = ? AND instance_id = ?",
                Integer.class,
                count.conch(),
                count.conchVersion(),
                count.action(),
                hour,
                batch.instanceId());
        return !rows.isEmpty();
    }

    private int updateCount(ReportBatch batch, CountReport count, Long ownerUserId, Timestamp hour) {
        return jdbc.update(
                "UPDATE hermit_hourly_counts SET success = ?, failure = ?, avg_millis = ?, p95_millis = ?, "
                        + "owner_user_id = COALESCE(?, owner_user_id) "
                        + "WHERE conch = ? AND conch_version = ? AND action = ? AND hour_start = ? AND instance_id = ?",
                count.success(),
                count.failure(),
                count.avgMillis(),
                count.p95Millis(),
                ownerUserId,
                count.conch(),
                count.conchVersion(),
                count.action(),
                hour,
                batch.instanceId());
    }

    private void insertRollback(ReportBatch batch, RollbackEvent event, Instant receivedAt, Long ownerUserId) {
        try {
            jdbc.update(
                    "INSERT INTO hermit_rollback_events (received_at, instance_id, conch, from_version, to_version, "
                            + "reason, occurred_at, event_id, owner_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Timestamp.from(receivedAt),
                    batch.instanceId(),
                    event.conch(),
                    event.fromVersion(),
                    event.toVersion(),
                    event.reason(),
                    Timestamp.from(event.occurredAt()),
                    event.eventId(),
                    ownerUserId);
        } catch (DuplicateKeyException ignored) {
            // 同一实例的 eventId 已存在时保留原行，本批其余写入继续提交。
        }
    }
}
