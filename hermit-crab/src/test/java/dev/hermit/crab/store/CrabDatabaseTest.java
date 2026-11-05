package dev.hermit.crab.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrabDatabaseTest {
    @TempDir
    Path temp;

    @Test
    void appliesVersionOneOnceAndReopensTheSameAccounts() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try {
            fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
            assertThat(Files.exists(temp.resolve("data").resolve("crab.mv.db"))).isTrue();
            assertThat(tableNames(fixture)).contains(
                    "ACCOUNTS",
                    "CREDENTIALS",
                    "CONCH_CONFIG",
                    "API_KEYS",
                    "CONFIG_AUDIT",
                    "SCHEMA_VERSION",
                    "CALL_LOGS",
                    "STEP_LOGS",
                    "IDEMPOTENCY",
                    "SESSIONS",
                    "CAPABILITY_SWITCHES",
                    "DEBUG_STATE",
                    "VERSION_SUCCESS",
                    "MISMATCH_STREAK",
                    "CONCH_PREVIOUS",
                    "INSTALLED_VERSIONS",
                    "SYNC_STATE",
                    "REPORT_QUEUE",
                    "ROLLBACK_QUEUE",
                    "HOURLY_COUNTS",
                    "REPORT_META",
                    "PENDING_CREDENTIAL_DELETES",
                    "DISABLED_CONCHES",
                    "SUBSCRIPTIONS",
                    "REPORT_SETTINGS",
                    "CONSOLE_PASSWORD",
                    "CONSOLE_LOGIN_FAILURES");
            assertThat(tableNames(fixture)).doesNotContain("PINNED_ROLLBACKS");
            assertThat(versions(fixture)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            assertThat(indexNames(fixture)).contains("CONFIG_AUDIT_AT_IDX");
            assertThat(columnType(fixture, "CREDENTIALS", "CIPHERTEXT")).contains("BINARY");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            Account account = reopened.accounts.resolve(Origin.OFFICIAL, "oa", Optional.of("alice"));
            assertThat(account.account()).isEqualTo("alice");
            assertThat(account.defaultAccount()).isTrue();
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        }
    }

    @Test
    void widensSyncStateValueWhenUpgradingFromVersionThree() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try {
            try (Connection connection = fixture.dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version WHERE version IN (4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)");
                statement.execute("ALTER TABLE call_logs DROP COLUMN source");
                statement.execute("ALTER TABLE call_logs DROP COLUMN channel");
                statement.execute("ALTER TABLE idempotency DROP COLUMN body_sha256");
                statement.execute("ALTER TABLE sync_state ALTER COLUMN \"value\" SET DATA TYPE VARCHAR(512)");
                statement.execute("ALTER TABLE rollback_queue DROP COLUMN event_id");
            }
            assertThat(versions(fixture)).containsExactly(1, 2, 3);
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            String value = "v".repeat(600);
            try (Connection connection = reopened.dataSource.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO sync_state (name, \"value\") VALUES (?, ?)")) {
                insert.setString(1, "manifest_snapshot");
                insert.setString(2, value);
                assertThat(insert.executeUpdate()).isEqualTo(1);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT \"value\" FROM sync_state WHERE name = ?")) {
                    query.setString(1, "manifest_snapshot");
                    try (ResultSet resultSet = query.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString(1)).hasSize(600);
                    }
                }
            }
        }
    }

    @Test
    void appliesVersionTwoWhenVersionThreeIsAlreadyRecorded() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try {
            forgetVersionTwo(fixture);
            assertThat(versions(fixture)).containsExactly(1, 3, 4, 5, 6, 7, 8);
            assertThat(tableNames(fixture)).doesNotContain("CALL_LOGS", "SESSIONS", "IDEMPOTENCY");
            assertThat(tableNames(fixture)).contains("REPORT_QUEUE", "SYNC_STATE");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            assertThat(tableNames(reopened)).contains(
                    "CALL_LOGS", "STEP_LOGS", "IDEMPOTENCY", "SESSIONS", "CAPABILITY_SWITCHES", "DEBUG_STATE",
                    "REPORT_QUEUE", "SYNC_STATE");
            assertThat(columnType(reopened, "CALL_LOGS", "SOURCE")).contains("CHAR");
        }
    }

    @Test
    void versionNineAddsCallSourceWithoutRewritingEarlierVersions() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE call_logs DROP COLUMN source");
            statement.execute("ALTER TABLE call_logs DROP COLUMN channel");
            statement.execute("ALTER TABLE idempotency DROP COLUMN body_sha256");
            statement.execute("DELETE FROM schema_version WHERE version IN (9, 10, 11, 12, 13, 14)");
            statement.execute("""
                    INSERT INTO call_logs (trace_id, at, origin, conch, action, account, success, duration_ms)
                    VALUES ('trace-old', CURRENT_TIMESTAMP, 'OFFICIAL', 'legacy_oa', 'ping', 'alice', TRUE, 3)
                    """);
            assertThat(versions(fixture)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(tableNames(fixture)).contains("DISABLED_CONCHES");
            assertThat(indexNames(fixture)).contains("CONFIG_AUDIT_AT_IDX");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            assertThat(tableNames(reopened)).contains("DISABLED_CONCHES");
            assertThat(indexNames(reopened)).contains("CONFIG_AUDIT_AT_IDX");
            try (Connection connection = reopened.dataSource.getConnection();
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT source, channel FROM call_logs WHERE trace_id = ?")) {
                query.setString(1, "trace-old");
                try (ResultSet resultSet = query.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualTo("REEF");
                    assertThat(resultSet.getString(2)).isEqualTo("HTTP");
                }
            }
        }
    }

    @Test
    void versionTenAndElevenAddDigestAndChannel() throws Exception {
        Path home = temp.resolve("v10");
        StoreFixture fixture = StoreFixture.open(home);
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE call_logs DROP COLUMN channel");
            statement.execute("ALTER TABLE idempotency DROP COLUMN body_sha256");
            statement.execute("DELETE FROM schema_version WHERE version IN (10, 11, 12, 13, 14)");
            statement.execute("""
                    INSERT INTO call_logs (trace_id, at, origin, conch, action, account, success, duration_ms, source)
                    VALUES ('trace-old', CURRENT_TIMESTAMP, 'OFFICIAL', 'legacy_oa', 'ping', 'alice', TRUE, 3, 'REEF')
                    """);
            statement.execute("""
                    INSERT INTO idempotency (origin, conch, account, action, "key", created_at, response_json)
                    VALUES ('OFFICIAL', 'legacy_oa', 'alice', 'ping', 'old-key', CURRENT_TIMESTAMP, X'0102')
                    """);
            assertThat(versions(fixture)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(home)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            try (Connection connection = reopened.dataSource.getConnection();
                 PreparedStatement calls = connection.prepareStatement(
                         "SELECT channel FROM call_logs WHERE trace_id = ?")) {
                calls.setString(1, "trace-old");
                try (ResultSet resultSet = calls.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualTo("HTTP");
                }
            }
            try (Connection connection = reopened.dataSource.getConnection();
                 PreparedStatement rows = connection.prepareStatement(
                         "SELECT body_sha256 FROM idempotency WHERE \"key\" = ?")) {
                rows.setString(1, "old-key");
                try (ResultSet resultSet = rows.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isNull();
                }
            }
        }
    }

    @Test
    void versionTwelveDropsPinnedRollbacksWhenThatVersionRowIsMissing() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(tableNames(fixture)).doesNotContain("PINNED_ROLLBACKS");
            statement.execute("DELETE FROM schema_version WHERE version = 12");
            statement.execute("""
                    CREATE TABLE pinned_rollbacks (
                        conch VARCHAR(256) PRIMARY KEY,
                        bad_version VARCHAR(64) NOT NULL,
                        rolled_back_to VARCHAR(64) NOT NULL
                    )
                    """);
            assertThat(tableNames(fixture)).contains("PINNED_ROLLBACKS");
            assertThat(versions(fixture)).doesNotContain(12);
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(tableNames(reopened)).doesNotContain("PINNED_ROLLBACKS");
            assertThat(versions(reopened)).contains(12);
        }
    }

    @Test
    void versionFiveAssignsUniqueEventIdsToExistingRollbackRows() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM schema_version WHERE version IN (5, 6, 7, 8, 9, 10, 11, 12, 13, 14)");
            statement.execute("ALTER TABLE call_logs DROP COLUMN source");
            statement.execute("ALTER TABLE call_logs DROP COLUMN channel");
            statement.execute("ALTER TABLE idempotency DROP COLUMN body_sha256");
            statement.execute("ALTER TABLE rollback_queue DROP COLUMN event_id");
            insertQueuedRollback(connection, Instant.parse("2026-06-01T00:00:00Z"), "RULE_MISMATCH");
            insertQueuedRollback(connection, Instant.parse("2026-06-01T00:00:01Z"), "AGAIN");
        } finally {
            fixture.close();
        }

        List<String> first;
        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            first = eventIds(reopened);
            assertThat(first).hasSize(2);
            assertThat(first).doesNotHaveDuplicates();
            assertThat(first).allMatch(id -> id.matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }

        try (StoreFixture again = StoreFixture.open(temp)) {
            assertThat(eventIds(again)).containsExactlyElementsOf(first);
        }
    }

    @Test
    void versionSevenAddsDisabledConchesWithoutReapplyingVersionSix() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try {
            try (Connection connection = fixture.dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE disabled_conches");
                statement.execute("DROP INDEX config_audit_at_idx");
                statement.execute("DELETE FROM schema_version WHERE version IN (7, 8, 9, 10, 11, 12, 13, 14)");
                statement.execute("ALTER TABLE call_logs DROP COLUMN source");
                statement.execute("ALTER TABLE call_logs DROP COLUMN channel");
                statement.execute("ALTER TABLE idempotency DROP COLUMN body_sha256");
            }
            assertThat(versions(fixture)).containsExactly(1, 2, 3, 4, 5, 6);
            assertThat(tableNames(fixture)).doesNotContain("DISABLED_CONCHES");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            assertThat(tableNames(reopened)).contains("DISABLED_CONCHES");
            assertThat(indexNames(reopened)).doesNotContain("CONFIG_AUDIT_AT_IDX");
        }
    }

    @Test
    void versionEightSubscribesInstalledConchesOnce() throws Exception {
        Path home = temp.resolve("seed");
        Files.createDirectories(home.resolve("conches/legacy_oa"));
        Files.writeString(home.resolve("conches/legacy_oa/current"), "1.0.0\n", StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("conches/other_sys"));
        Files.createDirectories(home.resolve("conches/Legacy"));
        Files.writeString(home.resolve("conches/Legacy/current"), "1.0.0\n", StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("conches/link_oa"));
        Files.writeString(home.resolve("version.txt"), "1.0.0\n", StandardCharsets.UTF_8);
        Files.createSymbolicLink(home.resolve("conches/link_oa/current"), home.resolve("version.txt"));

        try (StoreFixture fixture = StoreFixture.open(home)) {
            assertThat(subscriptions(fixture)).containsExactly("legacy_oa migration");
        }

        Files.createDirectories(home.resolve("conches/future_oa"));
        Files.writeString(home.resolve("conches/future_oa/current"), "1.0.0\n", StandardCharsets.UTF_8);
        try (StoreFixture again = StoreFixture.open(home)) {
            assertThat(subscriptions(again)).containsExactly("legacy_oa migration");
        }

        try (StoreFixture fixture = StoreFixture.open(home)) {
            try (Connection connection = fixture.dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE subscriptions");
                statement.execute("DELETE FROM schema_version WHERE version = 8");
            }
        }
        try (StoreFixture reopened = StoreFixture.open(home)) {
            assertThat(subscriptions(reopened)).containsExactly("future_oa migration", "legacy_oa migration");
        }
    }

    @Test
    void versionFourteenAddsConsolePasswordWhenThatRowIsMissing() throws Exception {
        Path home = temp.resolve("v14");
        StoreFixture fixture = StoreFixture.open(home);
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(tableNames(fixture)).contains("CONSOLE_PASSWORD", "CONSOLE_LOGIN_FAILURES", "REPORT_SETTINGS");
            statement.execute("DROP TABLE console_password");
            statement.execute("DROP TABLE console_login_failures");
            statement.execute("DELETE FROM schema_version WHERE version = 14");
            assertThat(tableNames(fixture)).doesNotContain("CONSOLE_PASSWORD", "CONSOLE_LOGIN_FAILURES");
            assertThat(versions(fixture)).contains(13).doesNotContain(14);
        } finally {
            fixture.close();
        }
        try (StoreFixture reopened = StoreFixture.open(home)) {
            assertThat(versions(reopened)).contains(13, 14);
            assertThat(tableNames(reopened)).contains(
                    "CONSOLE_PASSWORD", "CONSOLE_LOGIN_FAILURES", "REPORT_SETTINGS", "DEBUG_STATE");
        }
    }

    /**
     * Leaves a database that recorded versions 1, 3, 4, 5, 6, 7, and 8 and has no version-2 tables.
     * Version 3 tables stay; version 2 must not need them.
     */
    private static void forgetVersionTwo(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS step_logs");
            statement.execute("DROP TABLE IF EXISTS call_logs");
            statement.execute("DROP TABLE IF EXISTS idempotency");
            statement.execute("DROP TABLE IF EXISTS sessions");
            statement.execute("DROP TABLE IF EXISTS capability_switches");
            statement.execute("DROP TABLE IF EXISTS debug_state");
            statement.execute("DELETE FROM schema_version WHERE version = 2");
            statement.execute("DELETE FROM schema_version WHERE version IN (9, 10, 11, 12, 13, 14)");
        }
    }

    private static List<String> subscriptions(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT conch, actor FROM subscriptions ORDER BY conch")) {
            List<String> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(resultSet.getString(1) + " " + resultSet.getString(2));
            }
            return rows;
        }
    }

    private static Set<String> indexNames(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'
                     """)) {
            Set<String> names = new HashSet<>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names;
        }
    }

    private static Set<String> tableNames(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'
                     """)) {
            Set<String> names = new HashSet<>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names;
        }
    }

    private static void insertQueuedRollback(Connection connection, Instant at, String reason) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rollback_queue (at, conch, from_version, to_version, reason)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            Db.setInstant(insert, 1, at);
            insert.setString(2, "legacy_oa");
            insert.setString(3, "1.0.1");
            insert.setString(4, "1.0.0");
            insert.setString(5, reason);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static List<String> eventIds(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT event_id FROM rollback_queue ORDER BY id")) {
            List<String> ids = new ArrayList<>();
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
            return ids;
        }
    }

    private static java.util.List<Integer> versions(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
            java.util.List<Integer> versions = new java.util.ArrayList<>();
            while (resultSet.next()) {
                versions.add(resultSet.getInt(1));
            }
            return versions;
        }
    }

    private static String columnType(StoreFixture fixture, String table, String column) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
                     WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '%s' AND COLUMN_NAME = '%s'
                     """.formatted(table, column))) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
