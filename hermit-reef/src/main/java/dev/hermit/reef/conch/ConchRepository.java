package dev.hermit.reef.conch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class ConchRepository {

    private static final RowMapper<VersionRow> ROWS = (rs, rowNum) -> new VersionRow(
            rs.getLong("id"),
            rs.getString("conch"),
            rs.getString("version"),
            rs.getString("sha256"),
            rs.getString("min_crab_version"),
            rs.getString("status"),
            rs.getString("uploaded_by"),
            instant(rs.getTimestamp("uploaded_at")),
            rs.getString("published_by"),
            instant(rs.getTimestamp("published_at")),
            rs.getBoolean("is_current"),
            rs.getString("validation_json"),
            rs.getString("diff_json"),
            rs.getLong("pull_count"));

    private final JdbcTemplate jdbc;

    ConchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<VersionRow> listAll() {
        return jdbc.query(
                "SELECT id, conch, version, sha256, min_crab_version, package_path, status, uploaded_by, uploaded_at, "
                        + "published_by, published_at, is_current, validation_json, diff_json, pull_count "
                        + "FROM hermit_conch_versions ORDER BY conch, id",
                ROWS);
    }

    /**
     * 锁住该 Conch 的全部版本行。调用方必须已经处在事务里，否则锁会立刻放开。
     */
    List<VersionRow> lockConch(String conch) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("conch version lock requires a transaction");
        }
        return jdbc.query(
                "SELECT id, conch, version, sha256, min_crab_version, package_path, status, uploaded_by, uploaded_at, "
                        + "published_by, published_at, is_current, validation_json, diff_json, pull_count "
                        + "FROM hermit_conch_versions WHERE conch = ? ORDER BY id FOR UPDATE",
                ROWS,
                conch);
    }

    void insertUploaded(VersionRow row, String packagePath, String validationJson, String diffJson) {
        jdbc.update(
                "INSERT INTO hermit_conch_versions (conch, version, sha256, min_crab_version, package_path, status, "
                        + "uploaded_by, uploaded_at, published_by, published_at, is_current, validation_json, diff_json) "
                        + "VALUES (?, ?, ?, ?, ?, 'UPLOADED', ?, ?, NULL, NULL, FALSE, ?, ?)",
                row.conch(),
                row.version(),
                row.sha256(),
                row.minCrabVersion(),
                packagePath,
                row.uploadedBy(),
                Timestamp.from(row.uploadedAt()),
                validationJson,
                diffJson);
    }

    int markPublished(String conch, String version, String actor, Instant publishedAt) {
        jdbc.update("UPDATE hermit_conch_versions SET is_current = FALSE WHERE conch = ?", conch);
        return jdbc.update(
                "UPDATE hermit_conch_versions SET status = 'PUBLISHED', is_current = TRUE, published_by = ?, published_at = ? "
                        + "WHERE conch = ? AND version = ? AND status = 'UPLOADED'",
                actor,
                Timestamp.from(publishedAt),
                conch,
                version);
    }

    /**
     * 单条 {@code pull_count = pull_count + 1}。调用方只在验签通过、且该 Conch 未停用时调用。
     */
    int incrementPullCount(String conch, String version) {
        return jdbc.update(
                "UPDATE hermit_conch_versions SET pull_count = pull_count + 1 "
                        + "WHERE conch = ? AND version = ? AND status = 'PUBLISHED'",
                conch,
                version);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
