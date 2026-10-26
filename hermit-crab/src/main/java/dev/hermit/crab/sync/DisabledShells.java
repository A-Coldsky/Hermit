package dev.hermit.crab.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import dev.hermit.crab.store.Db;

/**
 * Shells Reef has withdrawn from the distribution manifest. The set is checked before a call
 * looks up the registry, so a shell that is still loaded for an in-flight snapshot is not served
 * to new requests. Rows survive restart; {@code current} is cleared separately so startup does
 * not load the package again.
 */
public final class DisabledShells {

    /** Stable call message. The error code is {@code SHELL_DISABLED}. */
    public static final String CALL_MESSAGE = "shell disabled";

    private final DataSource dataSource;
    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private final Map<String, String> versions = new ConcurrentHashMap<>();

    public DisabledShells(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** In-memory only. Tests that do not open a database use this. */
    public static DisabledShells none() {
        return new DisabledShells(null);
    }

    public void load() {
        if (dataSource == null) {
            return;
        }
        Map<String, String> stored = Db.transaction(dataSource, this::readAll);
        names.clear();
        versions.clear();
        names.addAll(stored.keySet());
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (entry.getValue() != null) {
                versions.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public void mark(String shell, String version, Instant at) {
        if (shell == null || shell.isBlank()) {
            return;
        }
        if (dataSource != null) {
            Db.transaction(dataSource, connection -> {
                upsert(connection, shell, version, at);
                return null;
            });
        }
        names.add(shell);
        if (version == null) {
            versions.remove(shell);
        } else {
            versions.put(shell, version);
        }
    }

    public void clear(String shell) {
        if (shell == null) {
            return;
        }
        if (dataSource != null) {
            Db.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM disabled_shells WHERE shell = ?")) {
                    statement.setString(1, shell);
                    statement.executeUpdate();
                }
                return null;
            });
        }
        names.remove(shell);
        versions.remove(shell);
    }

    public boolean contains(String shell) {
        return shell != null && names.contains(shell);
    }

    public Set<String> names() {
        return Set.copyOf(names);
    }

    public String version(String shell) {
        return versions.get(shell);
    }

    private Map<String, String> readAll(Connection connection) throws SQLException {
        Map<String, String> stored = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT shell, version FROM disabled_shells");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                stored.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        return stored;
    }

    private static void upsert(Connection connection, String shell, String version, Instant at) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE disabled_shells SET version = ?, disabled_at = ? WHERE shell = ?")) {
            setVersion(update, 1, version);
            Db.setInstant(update, 2, at);
            update.setString(3, shell);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO disabled_shells (shell, version, disabled_at) VALUES (?, ?, ?)")) {
            insert.setString(1, shell);
            setVersion(insert, 2, version);
            Db.setInstant(insert, 3, at);
            insert.executeUpdate();
        }
    }

    private static void setVersion(PreparedStatement statement, int index, String version) throws SQLException {
        if (version == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, version);
        }
    }
}
