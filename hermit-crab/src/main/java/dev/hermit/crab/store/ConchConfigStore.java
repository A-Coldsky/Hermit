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

public final class ConchConfigStore {
    private final DataSource dataSource;

    public ConchConfigStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<String> get(Origin origin, String conch, String key) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedKey = Checks.configKey(key);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT "value" FROM conch_config
                    WHERE origin = ? AND conch = ? AND "key" = ?
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
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

    public void put(Origin origin, String conch, String key, String value, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedKey = Checks.configKey(key);
        String checkedActor = Checks.actor(actor);
        Objects.requireNonNull(value, "value");
        Db.transaction(dataSource, connection -> {
            upsert(connection, checkedOrigin, checkedConch, checkedKey, value, Instant.now());
            ConfigAudit.append(
                    connection,
                    checkedActor,
                    AuditKind.CONFIG_PUT,
                    ConfigAudit.target(checkedOrigin, checkedConch),
                    Json.write(Map.of("key", checkedKey)));
            return null;
        });
    }

    public void delete(Origin origin, String conch, String key, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedKey = Checks.configKey(key);
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            int deleted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM conch_config
                    WHERE origin = ? AND conch = ? AND "key" = ?
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
                statement.setString(3, checkedKey);
                deleted = statement.executeUpdate();
            }
            if (deleted > 0) {
                ConfigAudit.append(
                        connection,
                        checkedActor,
                        AuditKind.CONFIG_DELETED,
                        ConfigAudit.target(checkedOrigin, checkedConch),
                        Json.write(Map.of("key", checkedKey)));
            }
            return null;
        });
    }

    public Map<String, String> all(Origin origin, String conch) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT "key", "value" FROM conch_config
                    WHERE origin = ? AND conch = ?
                    ORDER BY "key"
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
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
            String conch,
            String key,
            String value,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE conch_config
                SET "value" = ?, updated_at = ?
                WHERE origin = ? AND conch = ? AND "key" = ?
                """)) {
            update.setString(1, value);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, origin.name());
            update.setString(4, conch);
            update.setString(5, key);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO conch_config (origin, conch, "key", "value", updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, origin.name());
            insert.setString(2, conch);
            insert.setString(3, key);
            insert.setString(4, value);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }
}
