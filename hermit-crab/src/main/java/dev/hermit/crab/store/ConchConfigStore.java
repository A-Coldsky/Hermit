package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ConchConfigStore {
    private final DataSource dataSource;
    private final Clock clock;

    public ConchConfigStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public ConchConfigStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            putOne(connection, checkedOrigin, checkedConch, checkedKey, value, checkedActor, clock.instant());
            return null;
        });
    }

    public void delete(Origin origin, String conch, String key, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedKey = Checks.configKey(key);
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            deleteOne(connection, checkedOrigin, checkedConch, checkedKey, checkedActor, clock.instant());
            return null;
        });
    }

    /**
     * 一次事务写完：空串删除该项，其余写入。任何一项失败都不留下前面已经写的值。
     * 调用方先把每一项校验完再调这里。
     */
    public void putAll(Origin origin, String conch, Map<String, String> values, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedActor = Checks.actor(actor);
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, String> checked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            checked.put(Checks.configKey(entry.getKey()), Objects.requireNonNull(entry.getValue(), "value"));
        }
        Db.transaction(dataSource, connection -> {
            Instant now = clock.instant();
            for (Map.Entry<String, String> entry : checked.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    deleteOne(connection, checkedOrigin, checkedConch, entry.getKey(), checkedActor, now);
                } else {
                    putOne(connection, checkedOrigin, checkedConch, entry.getKey(), entry.getValue(), checkedActor, now);
                }
            }
            return null;
        });
    }

    private static void putOne(
            Connection connection,
            Origin origin,
            String conch,
            String key,
            String value,
            String actor,
            Instant now
    ) throws SQLException {
        upsert(connection, origin, conch, key, value, now);
        ConfigAudit.append(
                connection,
                now,
                actor,
                AuditKind.CONFIG_PUT,
                ConfigAudit.target(origin, conch),
                Json.write(Map.of("key", key)));
    }

    private static void deleteOne(
            Connection connection,
            Origin origin,
            String conch,
            String key,
            String actor,
            Instant now
    ) throws SQLException {
        int deleted;
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM conch_config
                WHERE origin = ? AND conch = ? AND "key" = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, key);
            deleted = statement.executeUpdate();
        }
        if (deleted > 0) {
            ConfigAudit.append(
                    connection,
                    now,
                    actor,
                    AuditKind.CONFIG_DELETED,
                    ConfigAudit.target(origin, conch),
                    Json.write(Map.of("key", key)));
        }
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
