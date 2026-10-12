package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.util.Objects;

/** Append-only config audit for callers outside the store package. */
public final class AuditLog {
    private AuditLog() {
    }

    public static void record(DataSource dataSource, String actor, AuditKind kind, String target, String detail) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(detail, "detail");
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            ConfigAudit.append(connection, checkedActor, kind, target, detail);
            return null;
        });
    }
}
