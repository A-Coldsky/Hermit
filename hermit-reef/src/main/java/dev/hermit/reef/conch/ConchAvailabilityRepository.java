package dev.hermit.reef.conch;

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
class ConchAvailabilityRepository {

    private static final RowMapper<AvailabilityRow> ROWS = (rs, rowNum) -> new AvailabilityRow(
            rs.getString("conch"),
            rs.getBoolean("disabled"),
            instant(rs.getTimestamp("disabled_at")),
            rs.getString("disabled_by"),
            rs.getString("disabled_reason"));

    private final JdbcTemplate jdbc;

    ConchAvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AvailabilityRow lock(String conch) {
        List<AvailabilityRow> rows = jdbc.query(
                "SELECT conch, disabled, disabled_at, disabled_by, disabled_reason "
                        + "FROM conch_availability WHERE conch = ? FOR UPDATE",
                ROWS,
                conch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    AvailabilityRow find(String conch) {
        List<AvailabilityRow> rows = jdbc.query(
                "SELECT conch, disabled, disabled_at, disabled_by, disabled_reason "
                        + "FROM conch_availability WHERE conch = ?",
                ROWS,
                conch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, AvailabilityRow> all() {
        Map<String, AvailabilityRow> rows = new LinkedHashMap<>();
        for (AvailabilityRow row : jdbc.query(
                "SELECT conch, disabled, disabled_at, disabled_by, disabled_reason FROM conch_availability",
                ROWS)) {
            rows.put(row.conch(), row);
        }
        return rows;
    }

    Set<String> disabledNames() {
        return Set.copyOf(jdbc.query(
                "SELECT conch FROM conch_availability WHERE disabled = TRUE",
                (rs, rowNum) -> rs.getString(1)));
    }

    boolean isDisabled(String conch) {
        Boolean disabled = jdbc.query(
                "SELECT disabled FROM conch_availability WHERE conch = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                conch);
        return Boolean.TRUE.equals(disabled);
    }

    void insertDisabled(String conch, Instant at, String actor, String reason) {
        jdbc.update(
                "INSERT INTO conch_availability (conch, disabled, disabled_at, disabled_by, disabled_reason) "
                        + "VALUES (?, TRUE, ?, ?, ?)",
                conch,
                Timestamp.from(at),
                actor,
                reason);
    }

    int updateDisabled(String conch, Instant at, String actor, String reason) {
        return jdbc.update(
                "UPDATE conch_availability SET disabled = TRUE, disabled_at = ?, disabled_by = ?, disabled_reason = ? "
                        + "WHERE conch = ? AND disabled = FALSE",
                Timestamp.from(at),
                actor,
                reason,
                conch);
    }

    int clearDisabled(String conch) {
        return jdbc.update(
                "UPDATE conch_availability SET disabled = FALSE, disabled_at = NULL, disabled_by = NULL, "
                        + "disabled_reason = NULL WHERE conch = ? AND disabled = TRUE",
                conch);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
