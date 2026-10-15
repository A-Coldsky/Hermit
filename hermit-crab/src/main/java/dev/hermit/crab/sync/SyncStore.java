package dev.hermit.crab.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import dev.hermit.common.version.SemVer;
import dev.hermit.crab.store.Db;

final class SyncStore {

    private final DataSource dataSource;

    SyncStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    Optional<String> etag() {
        return Db.transaction(dataSource, connection -> optionalString(connection,
                "SELECT \"value\" FROM sync_state WHERE name = 'manifest_etag'"));
    }

    void saveEtag(String etag) {
        if (!etagStorable(etag)) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            upsertState(connection, "manifest_etag", etag);
            return null;
        });
    }

    /**
     * Writes the ETag and the expected local versions in one transaction.
     * A rejected ETag writes neither, so a later 304 cannot trust a snapshot that has no tag.
     */
    void saveEtagAndSnapshot(String etag, Map<String, String> versions) {
        if (!etagStorable(etag)) {
            return;
        }
        String snapshot = ManifestSnapshot.encode(versions);
        Db.transaction(dataSource, connection -> {
            upsertState(connection, "manifest_etag", etag);
            upsertState(connection, "manifest_snapshot", snapshot);
            return null;
        });
    }

    void clearEtagAndSnapshot() {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM sync_state WHERE name IN ('manifest_etag', 'manifest_snapshot')")) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    Optional<Map<String, String>> manifestSnapshot() {
        Optional<String> raw = Db.transaction(dataSource, connection -> optionalString(connection,
                "SELECT \"value\" FROM sync_state WHERE name = 'manifest_snapshot'"));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return ManifestSnapshot.decode(raw.get());
    }

    private static boolean etagStorable(String etag) {
        return etag != null && etag.length() <= 512;
    }

    Optional<Pin> pin(String shell) {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT bad_version, rolled_back_to FROM pinned_rollbacks WHERE shell = ?
                    """)) {
                statement.setString(1, shell);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Pin(resultSet.getString(1), resultSet.getString(2)));
                }
            }
        });
    }

    void pin(String shell, String badVersion, String rolledBackTo, Instant at) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE pinned_rollbacks
                    SET bad_version = ?, rolled_back_to = ?, at = ?
                    WHERE shell = ?
                    """)) {
                update.setString(1, badVersion);
                update.setString(2, rolledBackTo);
                Db.setInstant(update, 3, at);
                update.setString(4, shell);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO pinned_rollbacks (shell, bad_version, rolled_back_to, at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setString(1, shell);
                insert.setString(2, badVersion);
                insert.setString(3, rolledBackTo);
                Db.setInstant(insert, 4, at);
                insert.executeUpdate();
            }
            return null;
        });
    }

    void clearPin(String shell) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pinned_rollbacks WHERE shell = ?")) {
                statement.setString(1, shell);
                statement.executeUpdate();
            }
            return null;
        });
    }

    void recordSuccess(String shell, String version, Instant at) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE version_success SET last_success_at = ? WHERE shell = ? AND version = ?
                    """)) {
                Db.setInstant(update, 1, at);
                update.setString(2, shell);
                update.setString(3, version);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO version_success (shell, version, last_success_at) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, shell);
                insert.setString(2, version);
                Db.setInstant(insert, 3, at);
                insert.executeUpdate();
            }
            return null;
        });
    }

    boolean succeeded(String shell, String version) {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM version_success WHERE shell = ? AND version = ?
                    """)) {
                statement.setString(1, shell);
                statement.setString(2, version);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    Optional<String> latestSuccessExcept(String shell, String version) {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT version, last_success_at FROM version_success WHERE shell = ? AND version <> ?
                    """)) {
                statement.setString(1, shell);
                statement.setString(2, version);
                try (ResultSet resultSet = statement.executeQuery()) {
                    String best = null;
                    Instant bestAt = null;
                    while (resultSet.next()) {
                        Instant at = Db.instant(resultSet, "last_success_at");
                        if (bestAt == null || at.isAfter(bestAt)) {
                            bestAt = at;
                            best = resultSet.getString("version");
                        }
                    }
                    return Optional.ofNullable(best);
                }
            }
        });
    }

    Optional<String> previous(String shell) {
        return Db.transaction(dataSource, connection -> optionalString(connection,
                "SELECT version FROM shell_previous WHERE shell = ?", shell));
    }

    void setPrevious(String shell, String version) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE shell_previous SET version = ? WHERE shell = ?")) {
                update.setString(1, version);
                update.setString(2, shell);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO shell_previous (shell, version) VALUES (?, ?)")) {
                insert.setString(1, shell);
                insert.setString(2, version);
                insert.executeUpdate();
            }
            return null;
        });
    }

    void clearPrevious(String shell) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM shell_previous WHERE shell = ?")) {
                statement.setString(1, shell);
                statement.executeUpdate();
            }
            return null;
        });
    }

    int recordMismatch(String shell, String version) {
        return Db.transaction(dataSource, connection -> {
            String stored = null;
            int streak = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT version, streak FROM mismatch_streak WHERE shell = ?")) {
                statement.setString(1, shell);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        stored = resultSet.getString(1);
                        streak = resultSet.getInt(2);
                    }
                }
            }
            int next = version.equals(stored) ? streak + 1 : 1;
            if (stored == null) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO mismatch_streak (shell, version, streak) VALUES (?, ?, ?)")) {
                    insert.setString(1, shell);
                    insert.setString(2, version);
                    insert.setInt(3, next);
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE mismatch_streak SET version = ?, streak = ? WHERE shell = ?")) {
                    update.setString(1, version);
                    update.setInt(2, next);
                    update.setString(3, shell);
                    update.executeUpdate();
                }
            }
            return next;
        });
    }

    void clearStreak(String shell) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM mismatch_streak WHERE shell = ?")) {
                statement.setString(1, shell);
                statement.executeUpdate();
            }
            return null;
        });
    }

    void recordInstalled(String shell, String version, Instant at) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE installed_versions SET installed_at = ? WHERE shell = ? AND version = ?
                    """)) {
                Db.setInstant(update, 1, at);
                update.setString(2, shell);
                update.setString(3, version);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO installed_versions (shell, version, installed_at) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, shell);
                insert.setString(2, version);
                Db.setInstant(insert, 3, at);
                insert.executeUpdate();
            }
            return null;
        });
    }

    List<Installed> installed(String shell) {
        List<Installed> rows = Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT version, installed_at FROM installed_versions WHERE shell = ?
                    """)) {
                statement.setString(1, shell);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Installed> found = new ArrayList<>();
                    while (resultSet.next()) {
                        found.add(new Installed(resultSet.getString(1), Db.instant(resultSet, "installed_at")));
                    }
                    return found;
                }
            }
        });
        rows.sort(Comparator.comparing(Installed::at).reversed().thenComparing(Installed::version, SyncStore::semverDesc));
        return rows;
    }

    Set<String> pendingCredentialFields(String shell) {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT field FROM pending_credential_deletes WHERE shell = ?")) {
                statement.setString(1, shell);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> fields = new TreeSet<>();
                    while (resultSet.next()) {
                        fields.add(resultSet.getString(1));
                    }
                    return fields;
                }
            }
        });
    }

    List<String> shellsWithPendingCredentialDeletes() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT shell FROM pending_credential_deletes");
                 ResultSet resultSet = statement.executeQuery()) {
                List<String> shells = new ArrayList<>();
                while (resultSet.next()) {
                    shells.add(resultSet.getString(1));
                }
                return shells;
            }
        });
    }

    void replacePendingCredentialFields(String shell, Set<String> fields) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM pending_credential_deletes WHERE shell = ?")) {
                delete.setString(1, shell);
                delete.executeUpdate();
            }
            if (fields == null || fields.isEmpty()) {
                return null;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO pending_credential_deletes (shell, field) VALUES (?, ?)")) {
                for (String field : new LinkedHashSet<>(fields)) {
                    if (field == null || field.isBlank()) {
                        continue;
                    }
                    insert.setString(1, shell);
                    insert.setString(2, field);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    private static int semverDesc(String left, String right) {
        try {
            return SemVer.parse(right).compareTo(SemVer.parse(left));
        } catch (IllegalArgumentException ex) {
            return right.compareTo(left);
        }
    }

    private static void upsertState(Connection connection, String name, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE sync_state SET \"value\" = ? WHERE name = ?")) {
            update.setString(1, value);
            update.setString(2, name);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sync_state (name, \"value\") VALUES (?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    private static Optional<String> optionalString(Connection connection, String sql, String... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(resultSet.getString(1));
            }
        }
    }

    record Pin(String badVersion, String rolledBackTo) {
    }

    record Installed(String version, Instant at) {
    }
}
