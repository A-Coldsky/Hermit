package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single debug flag for this Crab. Enabling it arms a 24-hour deadline.
 * A read after the deadline turns the flag off and appends {@code DEBUG_CHANGED}
 * with actor {@code retention}. Turning the flag off, including that expiry, deletes
 * every {@code dev_traces} row in the same transaction.
 */
public final class DebugStateStore {
    public static final Duration WINDOW = Duration.ofHours(24);
    private static final int ROW_ID = 1;

    private final DataSource dataSource;
    private final Clock clock;
    private final DebugModeListener listener;

    public DebugStateStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, null);
    }

    public DebugStateStore(DataSource dataSource, Clock clock, DebugModeListener listener) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = listener;
    }

    public DebugSnapshot get() {
        Transition transition = Db.transaction(dataSource, this::readAndExpire);
        fire(transition);
        return transition.snapshot();
    }

    public DebugSnapshot setEnabled(boolean enabled, String actor) {
        String checkedActor = Checks.actor(actor);
        Instant now = clock.instant();
        Instant expiresAt = enabled ? now.plus(WINDOW) : null;
        Transition transition = Db.transaction(dataSource, connection -> {
            Row row = select(connection);
            boolean previous = row != null && row.enabled();
            upsert(connection, enabled, expiresAt);
            List<String> deleted = List.of();
            if (!enabled) {
                if (previous) {
                    deleted = DevTraceStore.ids(connection);
                }
                DevTraceStore.deleteAll(connection);
            }
            ConfigAudit.append(
                    connection,
                    now,
                    checkedActor,
                    AuditKind.DEBUG_CHANGED,
                    "debug",
                    Json.write(Map.of("enabled", enabled)));
            return new Transition(new DebugSnapshot(enabled, expiresAt), previous != enabled, deleted);
        });
        fire(transition);
        return transition.snapshot();
    }

    public void expire() {
        get();
    }

    private Transition readAndExpire(Connection connection) throws SQLException {
        Row row = select(connection);
        if (row == null || !row.enabled()) {
            return new Transition(new DebugSnapshot(false, row == null ? null : row.expiresAt()), false, List.of());
        }
        Instant now = clock.instant();
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            upsert(connection, false, null);
            List<String> deleted = DevTraceStore.ids(connection);
            DevTraceStore.deleteAll(connection);
            ConfigAudit.append(
                    connection,
                    now,
                    Actors.RETENTION,
                    AuditKind.DEBUG_CHANGED,
                    "debug",
                    Json.write(Map.of("enabled", false)));
            return new Transition(new DebugSnapshot(false, null), true, deleted);
        }
        return new Transition(new DebugSnapshot(true, row.expiresAt()), false, List.of());
    }

    private void fire(Transition transition) {
        if (!transition.fire() || listener == null) {
            return;
        }
        listener.changed(transition.snapshot().enabled(), transition.deleted());
    }

    private static Row select(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled, expires_at FROM debug_state WHERE id = ? FOR UPDATE")) {
            statement.setInt(1, ROW_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new Row(resultSet.getBoolean("enabled"), Db.instant(resultSet, "expires_at"));
            }
        }
    }

    private static void upsert(Connection connection, boolean enabled, Instant expiresAt) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE debug_state SET enabled = ?, expires_at = ? WHERE id = ?
                """)) {
            update.setBoolean(1, enabled);
            if (expiresAt == null) {
                update.setObject(2, null);
            } else {
                Db.setInstant(update, 2, expiresAt);
            }
            update.setInt(3, ROW_ID);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO debug_state (id, enabled, expires_at) VALUES (?, ?, ?)
                """)) {
            insert.setInt(1, ROW_ID);
            insert.setBoolean(2, enabled);
            if (expiresAt == null) {
                insert.setObject(3, null);
            } else {
                Db.setInstant(insert, 3, expiresAt);
            }
            insert.executeUpdate();
        }
    }

    public record DebugSnapshot(boolean enabled, Instant expiresAt) {
    }

    private record Row(boolean enabled, Instant expiresAt) {
    }

    private record Transition(DebugSnapshot snapshot, boolean fire, List<String> deleted) {
    }
}
