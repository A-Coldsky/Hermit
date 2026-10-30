package dev.hermit.crab.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import dev.hermit.crab.store.Db;

/**
 * Names in {@code subscriptions} when that table exists. Schema v8 creates it; until then the
 * lookup is empty. The table name is taken from the catalog so a quoted identifier still matches.
 */
final class SubscriptionNames {
    private SubscriptionNames() {
    }

    static boolean contains(DataSource dataSource, String conch) {
        if (dataSource == null || conch == null) {
            return false;
        }
        return Db.transaction(dataSource, connection -> {
            String table = tableName(connection);
            if (table == null) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM \"" + table + "\" WHERE conch = ?")) {
                statement.setString(1, conch);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    private static String tableName(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC' AND UPPER(TABLE_NAME) = 'SUBSCRIPTIONS'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String name = resultSet.getString(1);
                if (name == null || !name.equalsIgnoreCase("subscriptions")) {
                    return null;
                }
                for (int i = 0; i < name.length(); i++) {
                    char c = name.charAt(i);
                    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_')) {
                        return null;
                    }
                }
                return name;
            }
        }
    }
}
