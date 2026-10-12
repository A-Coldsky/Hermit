package dev.hermit.crab.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

final class ConfigAudit {
    private ConfigAudit() {
    }

    static void append(
            Connection connection,
            String actor,
            AuditKind kind,
            String target,
            String detail
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO config_audit (at, actor, kind, target, detail)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            Db.setInstant(statement, 1, Instant.now());
            statement.setString(2, actor);
            statement.setString(3, kind.name());
            statement.setString(4, target);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    static String target(Origin origin, String shell, String account) {
        return origin.name() + "/" + shell + "/" + account;
    }

    static String target(Origin origin, String shell) {
        return origin.name() + "/" + shell;
    }
}
