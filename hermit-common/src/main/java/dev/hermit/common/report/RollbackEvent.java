package dev.hermit.common.report;

import java.time.Instant;
import java.util.Objects;

public record RollbackEvent(
        String shell,
        String fromVersion,
        String toVersion,
        String reason,
        Instant occurredAt
) {
    public RollbackEvent {
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(fromVersion, "fromVersion");
        Objects.requireNonNull(toVersion, "toVersion");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
