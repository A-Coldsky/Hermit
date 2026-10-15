package dev.hermit.crab.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
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
                    "SHELL_CONFIG",
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
                    "PINNED_ROLLBACKS",
                    "MISMATCH_STREAK",
                    "SHELL_PREVIOUS",
                    "INSTALLED_VERSIONS",
                    "SYNC_STATE",
                    "REPORT_QUEUE",
                    "ROLLBACK_QUEUE",
                    "HOURLY_COUNTS",
                    "REPORT_META",
                    "PENDING_CREDENTIAL_DELETES");
            assertThat(versions(fixture)).containsExactly(1, 2, 3, 4);
            assertThat(columnType(fixture, "CREDENTIALS", "CIPHERTEXT")).contains("BINARY");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            Account account = reopened.accounts.resolve(Origin.OFFICIAL, "oa", Optional.of("alice"));
            assertThat(account.account()).isEqualTo("alice");
            assertThat(account.defaultAccount()).isTrue();
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4);
        }
    }

    @Test
    void widensSyncStateValueWhenUpgradingFromVersionThree() throws Exception {
        StoreFixture fixture = StoreFixture.open(temp);
        try {
            try (Connection connection = fixture.dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version WHERE version = 4");
                statement.execute("ALTER TABLE sync_state ALTER COLUMN \"value\" SET DATA TYPE VARCHAR(512)");
            }
            assertThat(versions(fixture)).containsExactly(1, 2, 3);
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4);
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
            assertThat(versions(fixture)).containsExactly(1, 3, 4);
            assertThat(tableNames(fixture)).doesNotContain("CALL_LOGS", "SESSIONS", "IDEMPOTENCY");
            assertThat(tableNames(fixture)).contains("REPORT_QUEUE", "SYNC_STATE");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            assertThat(versions(reopened)).containsExactly(1, 2, 3, 4);
            assertThat(tableNames(reopened)).contains(
                    "CALL_LOGS", "STEP_LOGS", "IDEMPOTENCY", "SESSIONS", "CAPABILITY_SWITCHES", "DEBUG_STATE",
                    "REPORT_QUEUE", "SYNC_STATE");
        }
    }

    /**
     * Leaves a database that recorded versions 1, 3, and 4 and has no version-2 tables.
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
