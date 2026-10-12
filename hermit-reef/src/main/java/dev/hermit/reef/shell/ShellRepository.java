package dev.hermit.reef.shell;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class ShellRepository {

    private static final RowMapper<VersionRow> ROWS = (rs, rowNum) -> new VersionRow(
            rs.getLong("id"),
            rs.getString("shell"),
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
            rs.getString("diff_json"));

    private final JdbcTemplate jdbc;

    ShellRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<VersionRow> listAll() {
        return jdbc.query(
                "SELECT id, shell, version, sha256, min_crab_version, package_path, status, uploaded_by, uploaded_at, "
                        + "published_by, published_at, is_current, validation_json, diff_json "
                        + "FROM shell_versions ORDER BY shell, id",
                ROWS);
    }

    /**
     * 锁住该 Shell 的全部版本行。调用方必须已经处在事务里，否则锁会立刻放开。
     */
    List<VersionRow> lockShell(String shell) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("shell version lock requires a transaction");
        }
        return jdbc.query(
                "SELECT id, shell, version, sha256, min_crab_version, package_path, status, uploaded_by, uploaded_at, "
                        + "published_by, published_at, is_current, validation_json, diff_json "
                        + "FROM shell_versions WHERE shell = ? ORDER BY id FOR UPDATE",
                ROWS,
                shell);
    }

    void insertUploaded(VersionRow row, String packagePath, String validationJson, String diffJson) {
        jdbc.update(
                "INSERT INTO shell_versions (shell, version, sha256, min_crab_version, package_path, status, "
                        + "uploaded_by, uploaded_at, published_by, published_at, is_current, validation_json, diff_json) "
                        + "VALUES (?, ?, ?, ?, ?, 'UPLOADED', ?, ?, NULL, NULL, FALSE, ?, ?)",
                row.shell(),
                row.version(),
                row.sha256(),
                row.minCrabVersion(),
                packagePath,
                row.uploadedBy(),
                Timestamp.from(row.uploadedAt()),
                validationJson,
                diffJson);
    }

    int markPublished(String shell, String version, String actor, Instant publishedAt) {
        jdbc.update("UPDATE shell_versions SET is_current = FALSE WHERE shell = ?", shell);
        return jdbc.update(
                "UPDATE shell_versions SET status = 'PUBLISHED', is_current = TRUE, published_by = ?, published_at = ? "
                        + "WHERE shell = ? AND version = ? AND status = 'UPLOADED'",
                actor,
                Timestamp.from(publishedAt),
                shell,
                version);
    }

    int switchCurrent(String shell, String version) {
        jdbc.update("UPDATE shell_versions SET is_current = FALSE WHERE shell = ?", shell);
        return jdbc.update(
                "UPDATE shell_versions SET is_current = TRUE WHERE shell = ? AND version = ? AND status = 'PUBLISHED'",
                shell,
                version);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
