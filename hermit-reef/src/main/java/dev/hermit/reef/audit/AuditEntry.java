package dev.hermit.reef.audit;

import java.time.Instant;

public record AuditEntry(
        long id,
        Instant at,
        String actor,
        String action,
        String shell,
        String version,
        String detail) {
}
