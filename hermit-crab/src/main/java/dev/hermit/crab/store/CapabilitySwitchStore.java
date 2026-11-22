package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Per-capability disable flags. Combined with {@code config.yml} by the runtime. */
public final class CapabilitySwitchStore {
    private final DataSource dataSource;
    private final Clock clock;

    public CapabilitySwitchStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public CapabilitySwitchStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setDisabled(Origin origin, String conch, String action, boolean disabled, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedAction = Checks.field(action);
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            Instant now = clock.instant();
            upsert(connection, checkedOrigin, checkedConch, checkedAction, disabled, now);
            ConfigAudit.append(
                    connection,
                    now,
                    checkedActor,
                    disabled ? AuditKind.CAPABILITY_DISABLED : AuditKind.CAPABILITY_ENABLED,
                    checkedOrigin.name() + "/" + checkedConch + "/" + checkedAction,
                    Json.write(java.util.Map.of("disabled", disabled)));
            return null;
        });
    }

    /** 在单个事务里批量启停一组能力，不新增单独的分组开关表。 */
    public void setDisabledBatch(Origin origin, String conch, java.util.Collection<String> actions, boolean disabled, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedActor = Checks.actor(actor);
        if (actions == null || actions.isEmpty()) {
            return;
        }
        java.util.ArrayList<String> checkedActions = new java.util.ArrayList<>(actions.size());
        for (String action : actions) {
            checkedActions.add(Checks.field(action));
        }
        Instant now = Instant.now();
        AuditKind auditKind = disabled ? AuditKind.CAPABILITY_DISABLED : AuditKind.CAPABILITY_ENABLED;
        String detail = Json.write(java.util.Map.of("disabled", disabled));
        Db.transaction(dataSource, connection -> {
            for (String action : checkedActions) {
                upsert(connection, checkedOrigin, checkedConch, action, disabled, now);
                ConfigAudit.append(
                        connection,
                        checkedActor,
                        auditKind,
                        checkedOrigin.name() + "/" + checkedConch + "/" + action,
                        detail);
            }
            return null;
        });
    }

    /** Keys are {@code conch.action} for rows stored as disabled. */
    public Set<String> disabledKeys(Origin origin) {
        Origin checkedOrigin = Checks.origin(origin);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT conch, action FROM capability_switches
                    WHERE origin = ? AND disabled = TRUE
                    ORDER BY conch, action
                    """)) {
                statement.setString(1, checkedOrigin.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> keys = new LinkedHashSet<>();
                    while (resultSet.next()) {
                        keys.add(resultSet.getString("conch") + "." + resultSet.getString("action"));
                    }
                    return Set.copyOf(keys);
                }
            }
        });
    }

    private static void upsert(
            Connection connection,
            Origin origin,
            String conch,
            String action,
            boolean disabled,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE capability_switches
                SET disabled = ?, updated_at = ?
                WHERE origin = ? AND conch = ? AND action = ?
                """)) {
            update.setBoolean(1, disabled);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, origin.name());
            update.setString(4, conch);
            update.setString(5, action);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO capability_switches (origin, conch, action, disabled, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, origin.name());
            insert.setString(2, conch);
            insert.setString(3, action);
            insert.setBoolean(4, disabled);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }
}
