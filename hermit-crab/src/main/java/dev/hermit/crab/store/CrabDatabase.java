package dev.hermit.crab.store;

import org.h2.jdbcx.JdbcConnectionPool;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.hermit.common.validate.ConchValidator;

/**
 * H2 file database at {@code data/crab}. Startup applies migrations recorded in
 * {@code schema_version}. Version 1 is the account store. Version 2 adds call logs, idempotency,
 * encrypted sessions, capability switches, and the debug flag. Version 3 is sync and report state.
 * Version 4 widens {@code sync_state.value} so a manifest snapshot can be stored beside the ETag.
 * Version 5 adds {@code rollback_queue.event_id} and fills a unique id for rows already queued.
 * Version 5 is applied when {@code MAX(version)} is below 5, the same way as version 4.
 * Version 6 indexes {@code config_audit (at, id)} for newest-first reads and does not copy
 * {@code detail}. Version 7 adds {@code disabled_conches}, the marker that Reef dropped a
 * subscribed official conch from the manifest. The installed package stays loaded.
 * Version 6 is left as applied; do not fold the tombstones back into it.
 * Version 8 adds {@code subscriptions}. On that upgrade, conches under {@code conches/} that
 * already have a current pointer are inserted with actor {@code migration} so they stay installed.
 * Version 8 runs when its own row is missing, not when {@code MAX(version)} is below 8, so a
 * database that already recorded version 9 still receives the table. Version 7 is left as applied.
 * Version 9 adds {@code call_logs.source} and only alters the version-2 call log table. It runs
 * when its own row is missing and does not read or create version-8 tables.
 * Version 10 adds nullable {@code idempotency.body_sha256} (lowercase hex SHA-256 of the raw
 * request body). It runs when its own row is missing. Rows from before this version stay null;
 * a later retry cannot prove the body matches, so the caller treats null as a conflict.
 * Version 11 adds {@code call_logs.channel} with default {@code HTTP}. It runs when its own row
 * is missing and does not rewrite earlier call-log columns. Versions 10 and 11 are not folded
 * together: they alter different tables.
 * Version 2 is applied when its own row is missing, not when {@code MAX(version)} is below 2:
 * a database that already recorded version 3 still receives version 2. Version 2 does not read
 * or create later tables. Before the first official release these statements may be edited in
 * place when there is no stored data. After release, add a new version instead of rewriting one.
 */
public final class CrabDatabase implements AutoCloseable {
    /**
     * H2's default file trace records statement parameters when a statement fails.
     * Keep it off so a later SQL error cannot persist credential or config values.
     */
    private static final String H2_OPTIONS =
            ";LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0;FILE_LOCK=FILE";

    private static final Logger LOG = Logger.getLogger(CrabDatabase.class.getName());

    private final CrabPaths paths;
    private final JdbcConnectionPool pool;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CrabDatabase(CrabPaths paths) {
        this.paths = paths;
        this.pool = JdbcConnectionPool.create(jdbcUrl(paths.databaseFile()), "sa", "");
        try {
            migrate();
        } catch (RuntimeException ex) {
            pool.dispose();
            if (fileLocked(ex)) {
                throw new DatabaseInUseException();
            }
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
            // Do not assign current from version 2. A database that already recorded version 3
            // has MAX(version) = 3, and lowering current would make the version-3 branch insert
            // a duplicate schema_version row.
            if (!applied(connection, 2)) {
                applyV2(connection);
                insertVersion(connection, 2);
            }
            if (current < 3) {
                applyV3(connection);
                insertVersion(connection, 3);
            }
            if (current < 4) {
                applyV4(connection);
                insertVersion(connection, 4);
            }
            if (current < 5) {
                applyV5(connection);
                insertVersion(connection, 5);
            }
            if (current < 6) {
                applyV6(connection);
                insertVersion(connection, 6);
            }
            if (current < 7) {
                applyV7(connection);
                insertVersion(connection, 7);
            }
            // Version 9 may already be recorded. Do not gate version 8 on MAX(version).
            if (!applied(connection, 8)) {
                applyV8(connection);
                insertVersion(connection, 8);
            }
            if (!applied(connection, 9)) {
                applyV9(connection);
                insertVersion(connection, 9);
            }
            if (!applied(connection, 10)) {
                applyV10(connection);
                insertVersion(connection, 10);
            }
            if (!applied(connection, 11)) {
                applyV11(connection);
                insertVersion(connection, 11);
            }
            return null;
        });
    }

    /**
     * H2 file lock error 90020. The message is only a fallback so a wrapped exception still
     * becomes {@link DatabaseInUseException} instead of a stack trace that quotes SQL.
     */
    static boolean fileLocked(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) {
                if (sql.getErrorCode() == 90020) {
                    return true;
                }
                String message = sql.getMessage();
                if (message != null && message.toLowerCase(Locale.ROOT).contains("already in use")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static void applyV1(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS accounts (
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    is_default BOOLEAN NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, conch, account)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS credentials (
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    field VARCHAR(256) NOT NULL,
                    ciphertext BLOB NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, conch, account, field)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS conch_config (
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    "key" VARCHAR(256) NOT NULL,
                    "value" CLOB NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, conch, "key")
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

    private static void applyV2(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS call_logs (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    trace_id VARCHAR(64) NOT NULL,
                    at TIMESTAMP WITH TIME ZONE NOT NULL,
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    action VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    conch_version VARCHAR(64),
                    success BOOLEAN NOT NULL,
                    error_code VARCHAR(64),
                    error_message VARCHAR(500),
                    duration_ms BIGINT NOT NULL,
                    api_key_id VARCHAR(64)
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS call_logs_at_idx ON call_logs (at)");
        execute(connection, "CREATE INDEX IF NOT EXISTS call_logs_trace_idx ON call_logs (trace_id)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS step_logs (
                    call_id BIGINT NOT NULL,
                    idx INT NOT NULL,
                    type VARCHAR(32),
                    duration_ms BIGINT NOT NULL,
                    method VARCHAR(16),
                    path VARCHAR(2048),
                    status INT,
                    extract_count INT,
                    PRIMARY KEY (call_id, idx),
                    CONSTRAINT step_logs_call_fk FOREIGN KEY (call_id) REFERENCES call_logs (id) ON DELETE CASCADE
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS idempotency (
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    action VARCHAR(256) NOT NULL,
                    "key" VARCHAR(128) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    response_json BLOB NOT NULL,
                    PRIMARY KEY (origin, conch, account, action, "key")
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS idempotency_created_idx ON idempotency (created_at)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS sessions (
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    account VARCHAR(64) NOT NULL,
                    ciphertext BLOB NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, conch, account)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS capability_switches (
                    origin VARCHAR(16) NOT NULL,
                    conch VARCHAR(256) NOT NULL,
                    action VARCHAR(256) NOT NULL,
                    disabled BOOLEAN NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (origin, conch, action)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS debug_state (
                    id INT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE
                )
                """);
    }

    private static void applyV3(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS version_success (
                    conch VARCHAR(256) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (conch, version)
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS pinned_rollbacks (
                    conch VARCHAR(256) NOT NULL PRIMARY KEY,
                    bad_version VARCHAR(64) NOT NULL,
                    rolled_back_to VARCHAR(64) NOT NULL,
                    at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS mismatch_streak (
                    conch VARCHAR(256) NOT NULL PRIMARY KEY,
                    version VARCHAR(64) NOT NULL,
                    streak INT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS conch_previous (
                    conch VARCHAR(256) NOT NULL PRIMARY KEY,
                    version VARCHAR(64) NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS installed_versions (
                    conch VARCHAR(256) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    installed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (conch, version)
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
                    conch VARCHAR(256) NOT NULL,
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
                    conch VARCHAR(256) NOT NULL,
                    from_version VARCHAR(64) NOT NULL,
                    to_version VARCHAR(64) NOT NULL,
                    reason VARCHAR(1024) NOT NULL
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS rollback_queue_at_idx ON rollback_queue (at, id)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS hourly_counts (
                    "hour" TIMESTAMP WITH TIME ZONE NOT NULL,
                    conch VARCHAR(256) NOT NULL,
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
                    PRIMARY KEY ("hour", conch, version, action)
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
                    conch VARCHAR(256) NOT NULL,
                    field VARCHAR(256) NOT NULL,
                    PRIMARY KEY (conch, field)
                )
                """);
    }

    private static void applyV4(Connection connection) throws SQLException {
        execute(connection, """
                ALTER TABLE sync_state ALTER COLUMN "value" SET DATA TYPE CLOB
                """);
    }

    private static void applyV5(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE rollback_queue ADD COLUMN event_id VARCHAR(64)");
        List<Long> missing = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM rollback_queue WHERE event_id IS NULL");
             ResultSet resultSet = select.executeQuery()) {
            while (resultSet.next()) {
                missing.add(resultSet.getLong(1));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rollback_queue SET event_id = ? WHERE id = ?")) {
            for (Long id : missing) {
                update.setString(1, UUID.randomUUID().toString());
                update.setLong(2, id);
                update.executeUpdate();
            }
        }
        execute(connection, "ALTER TABLE rollback_queue ALTER COLUMN event_id SET NOT NULL");
    }

    private static void applyV6(Connection connection) throws SQLException {
        execute(connection, "CREATE INDEX IF NOT EXISTS config_audit_at_idx ON config_audit (at, id)");
    }

    private static void applyV7(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS disabled_conches (
                    conch VARCHAR(256) NOT NULL PRIMARY KEY,
                    version VARCHAR(64),
                    disabled_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    /**
     * {@code subscribed_at} is {@code TIMESTAMP WITH TIME ZONE} so {@link Db#setInstant} matches
     * every other Crab instant column. The upgrade inserts a row only for a legal conch name whose
     * {@code current} file is a readable regular file. A later startup does not scan again.
     */
    private void applyV8(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS subscriptions (
                    conch VARCHAR(128) PRIMARY KEY,
                    subscribed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    actor VARCHAR(128) NOT NULL
                )
                """);
        seedSubscriptions(connection);
    }

    private void seedSubscriptions(Connection connection) throws SQLException {
        Path root = paths.root().resolve("conches");
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> children;
        try (Stream<Path> listed = Files.list(root)) {
            children = listed.toList();
        } catch (IOException ex) {
            SQLException failure = new SQLException("cannot list installed conches");
            failure.initCause(ex);
            throw failure;
        }
        for (Path child : children) {
            if (Files.isSymbolicLink(child) || !Files.isDirectory(child)) {
                continue;
            }
            String name = child.getFileName().toString();
            if (!ConchValidator.isConchName(name)) {
                continue;
            }
            Path current = child.resolve("current");
            if (Files.isSymbolicLink(current) || !Files.isRegularFile(current)) {
                continue;
            }
            String version;
            try {
                version = Files.readString(current, StandardCharsets.UTF_8).trim();
            } catch (IOException ex) {
                LOG.warning("subscription seed skipped unreadable current conch=" + name);
                continue;
            }
            if (!pointerSegment(version) || subscribed(connection, name)) {
                continue;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO subscriptions (conch, subscribed_at, actor) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, name);
                Db.setInstant(insert, 2, Instant.now());
                insert.setString(3, "migration");
                insert.executeUpdate();
            }
        }
    }

    private static boolean subscribed(Connection connection, String conch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM subscriptions WHERE conch = ?")) {
            statement.setString(1, conch);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /** Same segment rules as {@code ConchFiles.safeSegment}. Kept here so store does not import sync. */
    private static boolean pointerSegment(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return false;
        }
        if (".".equals(value) || "..".equals(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    private static void applyV9(Connection connection) throws SQLException {
        execute(connection, """
                ALTER TABLE call_logs ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'REEF'
                """);
    }

    private static void applyV10(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE idempotency ADD COLUMN body_sha256 VARCHAR(64)");
    }

    private static void applyV11(Connection connection) throws SQLException {
        execute(connection, """
                ALTER TABLE call_logs ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'HTTP'
                """);
    }

    private static boolean applied(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_version WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
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
