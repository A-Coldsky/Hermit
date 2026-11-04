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
 * Subscribed official conches Reef dropped from the distribution manifest. The row is a marker:
 * the installed package stays loaded and callable. {@code disabled_at} is the first time Crab
 * saw the absence and is not refreshed on later syncs. Rows survive restart. A call returns
 * {@code CONCH_DISABLED} only when the name is tombstoned and the registry no longer has it.
 * That call, and a manual update in the same state, use {@link #CALL_MESSAGE} and do not download.
 * A manual update while the package is still loaded uses {@link #UPDATE_MESSAGE} and also does not download.
 */
public final class DisabledConches {

    /**
     * HTTP/MCP call, and manual update, when the tombstone remains and the registry has no package.
     * The error code is {@code CONCH_DISABLED}.
     */
    public static final String CALL_MESSAGE = "Reef 已停用该规则包，本机没有可调用的副本。";

    /**
     * Manual update when the tombstone remains and the package is still loaded.
     * The error code is {@code CONCH_DISABLED}. This path does not download.
     */
    public static final String UPDATE_MESSAGE = "Reef 已停用该规则包，不再下载。";

    private final DataSource dataSource;
    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final Map<String, Instant> disabledAt = new ConcurrentHashMap<>();

    public DisabledConches(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** In-memory only. Tests that do not open a database use this. */
    public static DisabledConches none() {
        return new DisabledConches(null);
    }

    public void load() {
        if (dataSource == null) {
            return;
        }
        Map<String, Stored> stored = Db.transaction(dataSource, this::readAll);
        names.clear();
        versions.clear();
        disabledAt.clear();
        for (Map.Entry<String, Stored> entry : stored.entrySet()) {
            names.add(entry.getKey());
            Stored row = entry.getValue();
            if (row.version() != null) {
                versions.put(entry.getKey(), row.version());
            }
            if (row.disabledAt() != null) {
                disabledAt.put(entry.getKey(), row.disabledAt());
            }
        }
    }

    public void mark(String conch, String version, Instant at) {
        if (conch == null || conch.isBlank()) {
            return;
        }
        if (dataSource != null) {
            Db.transaction(dataSource, connection -> {
                upsert(connection, conch, version, at);
                return null;
            });
        }
        names.add(conch);
        if (version == null) {
            versions.remove(conch);
        } else {
            versions.put(conch, version);
        }
        if (at != null) {
            disabledAt.put(conch, at);
        }
    }

    public void clear(String conch) {
        if (conch == null) {
            return;
        }
        if (dataSource != null) {
            Db.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM disabled_conches WHERE conch = ?")) {
                    statement.setString(1, conch);
                    statement.executeUpdate();
                }
                return null;
            });
        }
        names.remove(conch);
        versions.remove(conch);
        disabledAt.remove(conch);
    }

    /** When Crab first recorded the tombstone, or null when the name is not disabled. */
    public Instant disabledAt(String conch) {
        return conch == null ? null : disabledAt.get(conch);
    }

    public boolean contains(String conch) {
        return conch != null && names.contains(conch);
    }

    public Set<String> names() {
        return Set.copyOf(names);
    }

    public String version(String conch) {
        return versions.get(conch);
    }

    private Map<String, Stored> readAll(Connection connection) throws SQLException {
        Map<String, Stored> stored = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT conch, version, disabled_at FROM disabled_conches");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                stored.put(resultSet.getString(1), new Stored(resultSet.getString(2), Db.instant(resultSet, "disabled_at")));
            }
        }
        return stored;
    }

    private record Stored(String version, Instant disabledAt) {
    }

    private static void upsert(Connection connection, String conch, String version, Instant at) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE disabled_conches SET version = ?, disabled_at = ? WHERE conch = ?")) {
            setVersion(update, 1, version);
            Db.setInstant(update, 2, at);
            update.setString(3, conch);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO disabled_conches (conch, version, disabled_at) VALUES (?, ?, ?)")) {
            insert.setString(1, conch);
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
