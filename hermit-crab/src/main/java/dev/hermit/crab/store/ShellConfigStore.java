package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ShellConfigStore {
    private final DataSource dataSource;

    public ShellConfigStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<String> get(Origin origin, String shell, String key) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedKey = Checks.configKey(key);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT "value" FROM shell_config
                    WHERE origin = ? AND shell = ? AND "key" = ?
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
                statement.setString(3, checkedKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(resultSet.getString("value"));
                }
            }
        });
    }

    public void put(Origin origin, String shell, String key, String value, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedKey = Checks.configKey(key);
        String checkedActor = Checks.actor(actor);
        Objects.requireNonNull(value, "value");
        Db.transaction(dataSource, connection -> {
            upsert(connection, checkedOrigin, checkedShell, checkedKey, value, Instant.now());
            ConfigAudit.append(
                    connection,
                    checkedActor,
                    AuditKind.CONFIG_PUT,
                    ConfigAudit.target(checkedOrigin, checkedShell),
                    Json.write(Map.of("key", checkedKey)));
            return null;
        });
    }

    public void delete(Origin origin, String shell, String key, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedKey = Checks.configKey(key);
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            int deleted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM shell_config
                    WHERE origin = ? AND shell = ? AND "key" = ?
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
                statement.setString(3, checkedKey);
                deleted = statement.executeUpdate();
            }
            if (deleted > 0) {
                ConfigAudit.append(
                        connection,
                        checkedActor,
                        AuditKind.CONFIG_DELETED,
                        ConfigAudit.target(checkedOrigin, checkedShell),
                        Json.write(Map.of("key", checkedKey)));
            }
            return null;
        });
    }

    public Map<String, String> all(Origin origin, String shell) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT "key", "value" FROM shell_config
                    WHERE origin = ? AND shell = ?
                    ORDER BY "key"
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString("key"), resultSet.getString("value"));
                    }
                    return Map.copyOf(values);
                }
            }
        });
    }

    private static void upsert(
            Connection connection,
            Origin origin,
            String shell,
            String key,
            String value,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE shell_config
                SET "value" = ?, updated_at = ?
                WHERE origin = ? AND shell = ? AND "key" = ?
                """)) {
            update.setString(1, value);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, origin.name());
            update.setString(4, shell);
            update.setString(5, key);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO shell_config (origin, shell, "key", "value", updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, origin.name());
            insert.setString(2, shell);
            insert.setString(3, key);
            insert.setString(4, value);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }
}
