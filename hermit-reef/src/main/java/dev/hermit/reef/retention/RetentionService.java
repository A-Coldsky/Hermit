package dev.hermit.reef.retention;

import dev.hermit.reef.admin.LoginService;
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
        Instant now = clock.instant();
        Timestamp reportCutoff = Timestamp.from(now.minus(Duration.ofDays(properties.getReportRetentionDays())));
        int failures = jdbc.update("DELETE FROM hermit_failure_reports WHERE received_at < ?", reportCutoff);
        int rollbacks = jdbc.update("DELETE FROM hermit_rollback_events WHERE received_at < ?", reportCutoff);
        // 锁定窗口之外的失败记录不再参与计数。不删的话，对不存在用户名的猜测会让表一直涨。
        Timestamp loginCutoff = Timestamp.from(now.minus(LoginService.LOCK_WINDOW));
        int loginFailures = jdbc.update("DELETE FROM hermit_admin_login_failures WHERE failed_at < ?", loginCutoff);
        return new PurgeCounts(failures, rollbacks, loginFailures);
    }
}
