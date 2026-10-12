package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Per-capability disable flags. Combined with {@code config.yml} by the runtime. */
public final class CapabilitySwitchStore {
    private final DataSource dataSource;

    public CapabilitySwitchStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void setDisabled(Origin origin, String shell, String action, boolean disabled, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAction = Checks.field(action);
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            upsert(connection, checkedOrigin, checkedShell, checkedAction, disabled, Instant.now());
            ConfigAudit.append(
                    connection,
                    checkedActor,
                    disabled ? AuditKind.CAPABILITY_DISABLED : AuditKind.CAPABILITY_ENABLED,
                    checkedOrigin.name() + "/" + checkedShell + "/" + checkedAction,
                    Json.write(java.util.Map.of("disabled", disabled)));
            return null;
        });
    }

    /** Keys are {@code shell.action} for rows stored as disabled. */
    public Set<String> disabledKeys(Origin origin) {
        Origin checkedOrigin = Checks.origin(origin);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT shell, action FROM capability_switches
                    WHERE origin = ? AND disabled = TRUE
                    ORDER BY shell, action
                    """)) {
                statement.setString(1, checkedOrigin.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> keys = new LinkedHashSet<>();
                    while (resultSet.next()) {
                        keys.add(resultSet.getString("shell") + "." + resultSet.getString("action"));
                    }
                    return Set.copyOf(keys);
                }
            }
        });
    }

    private static void upsert(
            Connection connection,
            Origin origin,
            String shell,
            String action,
            boolean disabled,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE capability_switches
                SET disabled = ?, updated_at = ?
                WHERE origin = ? AND shell = ? AND action = ?
                """)) {
            update.setBoolean(1, disabled);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, origin.name());
            update.setString(4, shell);
            update.setString(5, action);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO capability_switches (origin, shell, action, disabled, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, origin.name());
            insert.setString(2, shell);
            insert.setString(3, action);
            insert.setBoolean(4, disabled);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }
}
