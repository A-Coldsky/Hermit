package dev.hermit.reef.tide;

import dev.hermit.common.report.CountReport;
import dev.hermit.common.report.FailureReport;
import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.RollbackEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
    public ReportAccepted ingest(ReportBatch batch) {
        Instant receivedAt = clock.instant();
        for (FailureReport failure : batch.failures()) {
            insertFailure(batch, failure, receivedAt);
        }
        for (CountReport count : batch.counts()) {
            upsertCount(batch, count);
        }
        for (RollbackEvent event : batch.events()) {
            insertRollback(batch, event, receivedAt);
        }
        return new ReportAccepted(new ReportAccepted.Counts(
                batch.failures().size(), batch.counts().size(), batch.events().size()));
    }

    private void insertFailure(ReportBatch batch, FailureReport failure, Instant receivedAt) {
        Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM failure_reports WHERE instance_id = ? AND trace_id = ?",
                Long.class,
                batch.instanceId(),
                failure.traceId());
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO failure_reports (received_at, instance_id, crab_version, trace_id, shell, "
                        + "shell_version, action, step_index, step_type, error_code, summary, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(receivedAt),
                batch.instanceId(),
                batch.crabVersion(),
                failure.traceId(),
                failure.shell(),
                failure.shellVersion(),
                failure.action(),
                failure.stepIndex(),
                failure.stepType(),
                failure.errorCode().name(),
                failure.summary(),
                Timestamp.from(failure.occurredAt()));
    }

    private void upsertCount(ReportBatch batch, CountReport count) {
        Timestamp hour = Timestamp.from(count.hourStart());
        int updated = jdbc.update(
                "UPDATE hourly_counts SET success = ?, failure = ?, avg_millis = ?, p95_millis = ? "
                        + "WHERE shell = ? AND shell_version = ? AND action = ? AND hour_start = ? AND instance_id = ?",
                count.success(),
                count.failure(),
                count.avgMillis(),
                count.p95Millis(),
                count.shell(),
                count.shellVersion(),
                count.action(),
                hour,
                batch.instanceId());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO hourly_counts (shell, shell_version, action, hour_start, instance_id, success, "
                            + "failure, avg_millis, p95_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    count.shell(),
                    count.shellVersion(),
                    count.action(),
                    hour,
                    batch.instanceId(),
                    count.success(),
                    count.failure(),
                    count.avgMillis(),
                    count.p95Millis());
        }
    }

    private void insertRollback(ReportBatch batch, RollbackEvent event, Instant receivedAt) {
        try {
            jdbc.update(
                    "INSERT INTO rollback_events (received_at, instance_id, shell, from_version, to_version, reason, "
                            + "occurred_at, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Timestamp.from(receivedAt),
                    batch.instanceId(),
                    event.shell(),
                    event.fromVersion(),
                    event.toVersion(),
                    event.reason(),
                    Timestamp.from(event.occurredAt()),
                    event.eventId());
        } catch (DuplicateKeyException ignored) {
            // 同一实例的 eventId 已存在时保留原行，本批其余写入继续提交。
        }
    }
}
