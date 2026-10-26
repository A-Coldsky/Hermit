package dev.hermit.reef.shell;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class ShellAvailabilityRepository {

    private static final RowMapper<AvailabilityRow> ROWS = (rs, rowNum) -> new AvailabilityRow(
            rs.getString("shell"),
            rs.getBoolean("disabled"),
            instant(rs.getTimestamp("disabled_at")),
            rs.getString("disabled_by"),
            rs.getString("disabled_reason"));

    private final JdbcTemplate jdbc;

    ShellAvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AvailabilityRow lock(String shell) {
        List<AvailabilityRow> rows = jdbc.query(
                "SELECT shell, disabled, disabled_at, disabled_by, disabled_reason "
                        + "FROM shell_availability WHERE shell = ? FOR UPDATE",
                ROWS,
                shell);
        return rows.isEmpty() ? null : rows.get(0);
    }

    AvailabilityRow find(String shell) {
        List<AvailabilityRow> rows = jdbc.query(
                "SELECT shell, disabled, disabled_at, disabled_by, disabled_reason "
                        + "FROM shell_availability WHERE shell = ?",
                ROWS,
                shell);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, AvailabilityRow> all() {
        Map<String, AvailabilityRow> rows = new LinkedHashMap<>();
        for (AvailabilityRow row : jdbc.query(
                "SELECT shell, disabled, disabled_at, disabled_by, disabled_reason FROM shell_availability",
                ROWS)) {
            rows.put(row.shell(), row);
        }
        return rows;
    }

    Set<String> disabledNames() {
        return Set.copyOf(jdbc.query(
                "SELECT shell FROM shell_availability WHERE disabled = TRUE",
                (rs, rowNum) -> rs.getString(1)));
    }

    boolean isDisabled(String shell) {
        Boolean disabled = jdbc.query(
                "SELECT disabled FROM shell_availability WHERE shell = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                shell);
        return Boolean.TRUE.equals(disabled);
    }

    void insertDisabled(String shell, Instant at, String actor, String reason) {
        jdbc.update(
                "INSERT INTO shell_availability (shell, disabled, disabled_at, disabled_by, disabled_reason) "
                        + "VALUES (?, TRUE, ?, ?, ?)",
                shell,
                Timestamp.from(at),
                actor,
                reason);
    }

    int updateDisabled(String shell, Instant at, String actor, String reason) {
        return jdbc.update(
                "UPDATE shell_availability SET disabled = TRUE, disabled_at = ?, disabled_by = ?, disabled_reason = ? "
                        + "WHERE shell = ? AND disabled = FALSE",
                Timestamp.from(at),
                actor,
                reason,
                shell);
    }

    int clearDisabled(String shell) {
        return jdbc.update(
                "UPDATE shell_availability SET disabled = FALSE, disabled_at = NULL, disabled_by = NULL, "
                        + "disabled_reason = NULL WHERE shell = ? AND disabled = TRUE",
                shell);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
