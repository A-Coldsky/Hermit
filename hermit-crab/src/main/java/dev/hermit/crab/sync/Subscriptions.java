package dev.hermit.crab.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import dev.hermit.crab.store.Db;

/**
 * Conch names this Crab will install from Reef. Rows live only in the local database.
 * {@code actor} is not an audit actor: schema upgrade writes {@code migration}.
 */
public final class Subscriptions {
    /** Written by schema v8 for conches that already had a current pointer. Not an audit actor. */
    public static final String MIGRATION_ACTOR = "migration";

    private final DataSource dataSource;

    public Subscriptions(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public boolean contains(String conch) {
        if (conch == null || conch.isBlank()) {
            return false;
        }
        return Db.transaction(dataSource, connection -> exists(connection, conch));
    }

    /**
     * Inserts the row when absent. A repeat call does not change {@code subscribed_at} or {@code actor}.
     *
     * @return true when a new row was written
     */
    public boolean record(String conch, String actor, Instant at) {
        String checkedConch = required(conch, "conch");
        String checkedActor = required(actor, "actor");
        Objects.requireNonNull(at, "at");
        return Db.transaction(dataSource, connection -> {
            if (exists(connection, checkedConch)) {
                return false;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO subscriptions (conch, subscribed_at, actor) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, checkedConch);
                Db.setInstant(insert, 2, at);
                insert.setString(3, checkedActor);
                insert.executeUpdate();
            }
            return true;
        });
    }

    public List<String> names() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT conch FROM subscriptions ORDER BY conch");
                 ResultSet resultSet = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                return names;
            }
        });
    }

    /** @return true when a row was deleted */
    public boolean delete(String conch) {
        if (conch == null || conch.isBlank()) {
            return false;
        }
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM subscriptions WHERE conch = ?")) {
                statement.setString(1, conch);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static boolean exists(Connection connection, String conch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM subscriptions WHERE conch = ?")) {
            statement.setString(1, conch);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }
}
