package dev.hermit.reef.conch;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class SubmissionRepository {

    private static final String COLUMNS =
            "SELECT id, conch, version, sha256, min_crab_version, driver, title, yaml_path, "
                    + "validation_json, diff_json, submit_note, status, submitted_by, submitted_at, "
                    + "reviewed_by, reviewed_at, review_comment "
                    + "FROM hermit_conch_submissions ";

    private static final RowMapper<SubmissionRow> ROWS = (rs, rowNum) -> new SubmissionRow(
            rs.getLong("id"),
            rs.getString("conch"),
            rs.getString("version"),
            rs.getString("sha256"),
            rs.getString("min_crab_version"),
            rs.getString("driver"),
            rs.getString("title"),
            rs.getString("yaml_path"),
            rs.getString("validation_json"),
            rs.getString("diff_json"),
            rs.getString("submit_note"),
            SubmissionStatus.valueOf(rs.getString("status")),
            rs.getString("submitted_by"),
            instant(rs.getTimestamp("submitted_at")),
            rs.getString("reviewed_by"),
            instant(rs.getTimestamp("reviewed_at")),
            rs.getString("review_comment"));

    private final JdbcTemplate jdbc;

    SubmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long insertPending(SubmissionRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO hermit_conch_submissions "
                            + "(conch, version, sha256, min_crab_version, driver, title, yaml_path, "
                            + "validation_json, diff_json, submit_note, status, submitted_by, submitted_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.conch());
            ps.setString(2, row.version());
            ps.setString(3, row.sha256());
            ps.setString(4, row.minCrabVersion());
            ps.setString(5, row.driver());
            ps.setString(6, row.title());
            ps.setString(7, row.yamlPath());
            ps.setString(8, row.validationJson());
            ps.setString(9, row.diffJson());
            ps.setString(10, row.submitNote());
            ps.setString(11, row.submittedBy());
            ps.setTimestamp(12, Timestamp.from(row.submittedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("submission id missing");
        }
        return key.longValue();
    }

    void updateYamlPath(long id, String yamlPath) {
        jdbc.update("UPDATE hermit_conch_submissions SET yaml_path = ? WHERE id = ?", yamlPath, id);
    }

    Optional<SubmissionRow> findById(long id) {
        List<SubmissionRow> rows = jdbc.query(COLUMNS + "WHERE id = ?", ROWS, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    Optional<SubmissionRow> lockById(long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("submission lock requires a transaction");
        }
        List<SubmissionRow> rows = jdbc.query(COLUMNS + "WHERE id = ? FOR UPDATE", ROWS, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    List<SubmissionRow> list(String submittedByOrNull, SubmissionStatus statusOrNull) {
        StringBuilder sql = new StringBuilder(COLUMNS);
        List<Object> params = new ArrayList<>();
        if (submittedByOrNull != null || statusOrNull != null) {
            sql.append("WHERE ");
            boolean first = true;
            if (submittedByOrNull != null) {
                sql.append("submitted_by = ?");
                params.add(submittedByOrNull);
                first = false;
            }
            if (statusOrNull != null) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append("status = ?");
                params.add(statusOrNull.name());
            }
            sql.append(' ');
        }
        sql.append("ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, submitted_at DESC, id DESC");
        List<SubmissionRow> rows = jdbc.query(sql.toString(), ROWS, params.toArray());
        if (submittedByOrNull != null) {
            return rows.stream().filter(r -> submittedByOrNull.equals(r.submittedBy())).toList();
        }
        return rows;
    }

    List<SubmissionRow> findPendingByConch(String conch) {
        if (conch == null || conch.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                COLUMNS + "WHERE conch = ? AND status = 'PENDING' ORDER BY submitted_at DESC, id DESC",
                ROWS,
                conch);
    }

    long countPending(String submittedByOrNull) {
        Long count;
        if (submittedByOrNull == null) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hermit_conch_submissions WHERE status = 'PENDING'",
                    Long.class);
        } else {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hermit_conch_submissions WHERE status = 'PENDING' AND submitted_by = ?",
                    Long.class,
                    submittedByOrNull);
        }
        return count == null ? 0 : count;
    }

    int markReviewed(long id, SubmissionStatus targetStatus, String reviewer, Instant at, String comment) {
        return jdbc.update(
                "UPDATE hermit_conch_submissions "
                        + "SET status = ?, reviewed_by = ?, reviewed_at = ?, review_comment = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                targetStatus.name(),
                reviewer,
                Timestamp.from(at),
                comment,
                id);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
