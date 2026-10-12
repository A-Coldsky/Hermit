package dev.hermit.reef.retention;

import dev.hermit.reef.config.ReefProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetentionService {

    private final JdbcTemplate jdbc;
    private final ReefProperties properties;
    private final Clock clock;

    public RetentionService(JdbcTemplate jdbc, ReefProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public PurgeCounts purge() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.getReportRetentionDays()));
        Timestamp cutoffTimestamp = Timestamp.from(cutoff);
        int failures = jdbc.update("DELETE FROM failure_reports WHERE received_at < ?", cutoffTimestamp);
        int rollbacks = jdbc.update("DELETE FROM rollback_events WHERE received_at < ?", cutoffTimestamp);
        return new PurgeCounts(failures, rollbacks);
    }
}
