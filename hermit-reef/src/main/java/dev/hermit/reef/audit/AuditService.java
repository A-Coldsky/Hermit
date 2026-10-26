package dev.hermit.reef.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String actor, AuditAction action, String conch, String version, String detail) {
        jdbc.update(
                "INSERT INTO audit_log (at, actor, action, conch, version, detail) VALUES (?, ?, ?, ?, ?, ?)",
                Timestamp.from(clock.instant()),
                actor,
                action.name(),
                conch,
                version,
                detail);
    }

    public AuditPage page(String conch, int page, int size) {
        int offset = (page - 1) * size;
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE (? IS NULL OR conch = ?)",
                Long.class,
                conch,
                conch);
        List<AuditEntry> items = jdbc.query(
                "SELECT id, at, actor, action, conch, version, detail FROM audit_log "
                        + "WHERE (? IS NULL OR conch = ?) ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getTimestamp("at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("conch"),
                        rs.getString("version"),
                        rs.getString("detail")),
                conch,
                conch,
                size,
                offset);
        return new AuditPage(items, page, size, total == null ? 0 : total);
    }
}
