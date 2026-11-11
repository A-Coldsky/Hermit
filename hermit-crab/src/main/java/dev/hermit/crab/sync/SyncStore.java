package dev.hermit.crab.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    /**
     * Last HTTP 200 manifest. Empty means the list was saved and had no entries.
     * Malformed JSON is treated as absent.
     */
    Optional<List<ReefClient.CatalogEntry>> catalog() {
        Optional<String> raw = Db.transaction(dataSource, connection -> optionalString(connection,
                "SELECT \"value\" FROM sync_state WHERE name = 'catalog_entries'"));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return CatalogCache.decode(raw.get());
    }

    Optional<Instant> catalogSyncedAt() {
        Optional<String> raw = Db.transaction(dataSource, connection -> optionalString(connection,
                "SELECT \"value\" FROM sync_state WHERE name = 'catalog_synced_at'"));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw.get()));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    void saveCatalog(List<ReefClient.CatalogEntry> entries, Instant at) {
        String json = CatalogCache.encode(entries);
        String syncedAt = at.toString();
        Db.transaction(dataSource, connection -> {
            upsertState(connection, "catalog_entries", json);
            upsertState(connection, "catalog_synced_at", syncedAt);
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

    void recordSuccess(String conch, String version, Instant at) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE version_success SET last_success_at = ? WHERE conch = ? AND version = ?
                    """)) {
                Db.setInstant(update, 1, at);
                update.setString(2, conch);
                update.setString(3, version);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO version_success (conch, version, last_success_at) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, conch);
                insert.setString(2, version);
                Db.setInstant(insert, 3, at);
                insert.executeUpdate();
            }
            return null;
        });
    }

    Optional<String> latestSuccessExcept(String conch, String version) {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT version, last_success_at FROM version_success WHERE conch = ? AND version <> ?
                    """)) {
                statement.setString(1, conch);
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

    Optional<String> previous(String conch) {
        return Db.transaction(dataSource, connection -> optionalString(connection,
                "SELECT version FROM conch_previous WHERE conch = ?", conch));
    }

    void setPrevious(String conch, String version) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE conch_previous SET version = ? WHERE conch = ?")) {
                update.setString(1, version);
                update.setString(2, conch);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO conch_previous (conch, version) VALUES (?, ?)")) {
                insert.setString(1, conch);
                insert.setString(2, version);
                insert.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Reason already audited for this conch, so a later sync of the same failure does not
     * append another row. Empty after the package loads or the subscription is removed.
     */
    Optional<String> repairNotice(String conch) {
        if (conch == null || conch.isBlank()) {
            return Optional.empty();
        }
        return Db.transaction(dataSource, connection -> Optional.ofNullable(readRepairNotices(connection).get(conch)));
    }

    void saveRepairNotice(String conch, String reason) {
        if (conch == null || conch.isBlank() || reason == null || reason.isBlank()) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            Map<String, String> notices = readRepairNotices(connection);
            notices.put(conch, reason);
            upsertState(connection, "repair_notices", RepairNotices.encode(notices));
            return null;
        });
    }

    void clearRepairNotice(String conch) {
        if (conch == null || conch.isBlank()) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            Map<String, String> notices = readRepairNotices(connection);
            if (notices.remove(conch) == null) {
                return null;
            }
            writeRepairNotices(connection, notices);
            return null;
        });
    }

    /**
     * Forgets that this subscription installed a package. Unsubscribe calls this so the next
     * subscribe is a first install. A lost pointer does not.
     */
    void clearInstallHistory(String conch) {
        if (conch == null || conch.isBlank()) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement installed = connection.prepareStatement(
                    "DELETE FROM installed_versions WHERE conch = ?")) {
                installed.setString(1, conch);
                installed.executeUpdate();
            }
            try (PreparedStatement previous = connection.prepareStatement(
                    "DELETE FROM conch_previous WHERE conch = ?")) {
                previous.setString(1, conch);
                previous.executeUpdate();
            }
            Map<String, String> notices = readRepairNotices(connection);
            if (notices.remove(conch) != null) {
                writeRepairNotices(connection, notices);
            }
            Map<String, String> rejected = readRejected(connection);
            if (rejected.remove(conch) != null) {
                if (rejected.isEmpty()) {
                    try (PreparedStatement drop = connection.prepareStatement(
                            "DELETE FROM sync_state WHERE name = 'rejected_versions'")) {
                        drop.executeUpdate();
                    }
                } else {
                    upsertState(connection, "rejected_versions", RepairNotices.encode(rejected));
                }
            }
            return null;
        });
    }

    /** Catalog version that failed validation. A later successful install clears it. */
    Optional<String> rejectedVersion(String conch) {
        if (conch == null || conch.isBlank()) {
            return Optional.empty();
        }
        return Db.transaction(dataSource, connection -> Optional.ofNullable(readRejected(connection).get(conch)));
    }

    void rememberRejected(String conch, String version) {
        if (conch == null || conch.isBlank() || version == null || version.isBlank()) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            Map<String, String> rejected = readRejected(connection);
            rejected.put(conch, version);
            upsertState(connection, "rejected_versions", RepairNotices.encode(rejected));
            return null;
        });
    }

    void clearRejected(String conch) {
        if (conch == null || conch.isBlank()) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            Map<String, String> rejected = readRejected(connection);
            if (rejected.remove(conch) == null) {
                return null;
            }
            if (rejected.isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM sync_state WHERE name = 'rejected_versions'")) {
                    statement.executeUpdate();
                }
                return null;
            }
            upsertState(connection, "rejected_versions", RepairNotices.encode(rejected));
            return null;
        });
    }

    void clearPrevious(String conch) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM conch_previous WHERE conch = ?")) {
                statement.setString(1, conch);
                statement.executeUpdate();
            }
            return null;
        });
    }

    void recordInstalled(String conch, String version, Instant at) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE installed_versions SET installed_at = ? WHERE conch = ? AND version = ?
                    """)) {
                Db.setInstant(update, 1, at);
                update.setString(2, conch);
                update.setString(3, version);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO installed_versions (conch, version, installed_at) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, conch);
                insert.setString(2, version);
                Db.setInstant(insert, 3, at);
                insert.executeUpdate();
            }
            return null;
        });
    }

    List<Installed> installed(String conch) {
        List<Installed> rows = Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT version, installed_at FROM installed_versions WHERE conch = ?
                    """)) {
                statement.setString(1, conch);
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

    Set<String> pendingCredentialFields(String conch) {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT field FROM pending_credential_deletes WHERE conch = ?")) {
                statement.setString(1, conch);
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

    List<String> conchesWithPendingCredentialDeletes() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT conch FROM pending_credential_deletes");
                 ResultSet resultSet = statement.executeQuery()) {
                List<String> conches = new ArrayList<>();
                while (resultSet.next()) {
                    conches.add(resultSet.getString(1));
                }
                return conches;
            }
        });
    }

    void replacePendingCredentialFields(String conch, Set<String> fields) {
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM pending_credential_deletes WHERE conch = ?")) {
                delete.setString(1, conch);
                delete.executeUpdate();
            }
            if (fields == null || fields.isEmpty()) {
                return null;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO pending_credential_deletes (conch, field) VALUES (?, ?)")) {
                for (String field : new LinkedHashSet<>(fields)) {
                    if (field == null || field.isBlank()) {
                        continue;
                    }
                    insert.setString(1, conch);
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

    private static Map<String, String> readRejected(Connection connection) throws SQLException {
        return RepairNotices.decode(optionalString(connection,
                "SELECT \"value\" FROM sync_state WHERE name = 'rejected_versions'").orElse(null));
    }

    private static Map<String, String> readRepairNotices(Connection connection) throws SQLException {
        return RepairNotices.decode(optionalString(connection,
                "SELECT \"value\" FROM sync_state WHERE name = 'repair_notices'").orElse(null));
    }

    private static void writeRepairNotices(Connection connection, Map<String, String> notices) throws SQLException {
        if (notices == null || notices.isEmpty()) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM sync_state WHERE name = 'repair_notices'")) {
                delete.executeUpdate();
            }
            return;
        }
        upsertState(connection, "repair_notices", RepairNotices.encode(notices));
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

    record Installed(String version, Instant at) {
    }
}
