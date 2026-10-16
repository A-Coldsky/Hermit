package dev.hermit.common.report;

import java.time.Instant;
import java.util.Objects;

public record RollbackEvent(
        String shell,
        String fromVersion,
        String toVersion,
        String reason,
        Instant occurredAt,
        String eventId
) {
    public RollbackEvent {
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(fromVersion, "fromVersion");
        Objects.requireNonNull(toVersion, "toVersion");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventId, "eventId");
        eventId = eventId.strip();
        if (eventId.isEmpty() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId 去空白后长度必须在 1 到 64");
        }
    }
}
