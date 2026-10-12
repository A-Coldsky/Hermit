package dev.hermit.crab.store;

import org.h2.jdbcx.JdbcConnectionPool;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H2 file database at {@code data/crab}. Startup applies ordered migrations recorded in
 * {@code schema_version}. Version 1 is the account store. Version 3 is sync and report state.
 * Version 2 belongs to the runtime slice and must be inserted between them at merge time.
 */
public final class CrabDatabase implements AutoCloseable {
    /**
     * H2's default file trace records statement parameters when a statement fails.
     * Keep it off so a later SQL error cannot persist credential or config values.
     */
    private static final String H2_OPTIONS =
            ";LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0";

    private final JdbcConnectionPool pool;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CrabDatabase(CrabPaths paths) {
        this.pool = JdbcConnectionPool.create(jdbcUrl(paths.databaseFile()), "sa", "");
        try {
            migrate();
        } catch (RuntimeException ex) {
            pool.dispose();
            throw ex;
        }
    }

    public DataSource dataSource() {
        return pool;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pool.dispose();
        }
    }

    private void migrate() {
        Db.transaction(pool, connection -> {
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INT PRIMARY KEY,
                        applied_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            int current = currentVersion(connection);
            if (current < 1) {
                applyV1(connection);
                insertVersion(connection, 1);
                current = 1;
            }
            // Slice 2c inserts V2 between these branches:
            // if (current < 2) { applyV2(connection); insertVersion(connection, 2); current = 2; }
            // A database that already recorded version 3 has MAX(version) = 3, so a later
            // `current < 2` check will not see an unapplied V2. Merge V2 as its own applied-version check.
            if (current < 3) {
                applyV3(connection);
                insertVersion(connection, 3);
            }
            return null;
        });
    }

    private static void applyV1(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS accounts (
                    origin VARCHAR(16) NOT NULL,
                    shell VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    is_default BOOLEAN NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, shell, account)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS credentials (
                    origin VARCHAR(16) NOT NULL,
                    shell VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    field VARCHAR(256) NOT NULL,
                    ciphertext BLOB NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, shell, account, field)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS shell_config (
                    origin VARCHAR(16) NOT NULL,
                    shell VARCHAR(256) NOT NULL,
                    "key" VARCHAR(256) NOT NULL,
                    "value" CLOB NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, shell, "key")
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS api_keys (
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    name VARCHAR(256) NOT NULL,
                    prefix VARCHAR(16) NOT NULL,
                    hash VARCHAR(64) NOT NULL,
                    scope_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    revoked_at TIMESTAMP WITH TIME ZONE,
                    last_used_at TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT api_keys_hash_unique UNIQUE (hash)
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS api_keys_prefix_idx ON api_keys (prefix)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS config_audit (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    at TIMESTAMP WITH TIME ZONE NOT NULL,
                    actor VARCHAR(256) NOT NULL,
                    kind VARCHAR(64) NOT NULL,
                    target VARCHAR(512) NOT NULL,
                    detail CLOB NOT NULL
                )
                """);
    }

    private static void applyV3(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS version_success (
                    shell VARCHAR(256) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (shell, version)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS pinned_rollbacks (
                    shell VARCHAR(256) NOT NULL PRIMARY KEY,
                    bad_version VARCHAR(64) NOT NULL,
                    rolled_back_to VARCHAR(64) NOT NULL,
                    at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS mismatch_streak (
                    shell VARCHAR(256) NOT NULL PRIMARY KEY,
                    version VARCHAR(64) NOT NULL,
                    streak INT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS shell_previous (
                    shell VARCHAR(256) NOT NULL PRIMARY KEY,
                    version VARCHAR(64) NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS installed_versions (
                    shell VARCHAR(256) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    installed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (shell, version)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS sync_state (
                    name VARCHAR(64) NOT NULL PRIMARY KEY,
                    "value" VARCHAR(512) NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS report_queue (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    trace_id VARCHAR(128) NOT NULL,
                    at TIMESTAMP WITH TIME ZONE NOT NULL,
                    shell VARCHAR(256) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    action VARCHAR(256) NOT NULL,
                    error_code VARCHAR(64) NOT NULL,
                    step_index INT,
                    step_type VARCHAR(64),
                    duration_millis BIGINT NOT NULL
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS report_queue_at_idx ON report_queue (at, id)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS rollback_queue (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    at TIMESTAMP WITH TIME ZONE NOT NULL,
                    shell VARCHAR(256) NOT NULL,
                    from_version VARCHAR(64) NOT NULL,
                    to_version VARCHAR(64) NOT NULL,
                    reason VARCHAR(1024) NOT NULL
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS rollback_queue_at_idx ON rollback_queue (at, id)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS hourly_counts (
                    "hour" TIMESTAMP WITH TIME ZONE NOT NULL,
                    shell VARCHAR(256) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    action VARCHAR(256) NOT NULL,
                    success_count BIGINT NOT NULL,
                    failure_count_by_code CLOB NOT NULL,
                    sum_ms BIGINT NOT NULL,
                    latency_buckets CLOB NOT NULL,
                    sent_total BIGINT,
                    sent_codes CLOB,
                    sent_sum_ms BIGINT,
                    sent_buckets CLOB,
                    PRIMARY KEY ("hour", shell, version, action)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS report_meta (
                    name VARCHAR(64) NOT NULL PRIMARY KEY,
                    amount BIGINT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS pending_credential_deletes (
                    shell VARCHAR(256) NOT NULL,
                    field VARCHAR(256) NOT NULL,
                    PRIMARY KEY (shell, field)
                )
                """);
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void insertVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
            statement.setInt(1, version);
            Db.setInstant(statement, 2, Instant.now());
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String jdbcUrl(Path databaseFile) {
        String path = databaseFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "jdbc:h2:file:" + path + H2_OPTIONS;
    }
}
