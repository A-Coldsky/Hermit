package dev.hermit.crab.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
            assertThat(versions(fixture)).containsExactly(1, 3);
            assertThat(columnType(fixture, "CREDENTIALS", "CIPHERTEXT")).contains("BINARY");
        } finally {
            fixture.close();
        }

        try (StoreFixture reopened = StoreFixture.open(temp)) {
            Account account = reopened.accounts.resolve(Origin.OFFICIAL, "oa", Optional.of("alice"));
            assertThat(account.account()).isEqualTo("alice");
            assertThat(account.defaultAccount()).isTrue();
            assertThat(versions(reopened)).containsExactly(1, 3);
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
