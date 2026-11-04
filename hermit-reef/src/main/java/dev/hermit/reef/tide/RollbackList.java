package dev.hermit.reef.tide;

import java.time.Instant;
import java.util.List;

public record RollbackList(List<RollbackItem> events) {

    public record RollbackItem(
            long id,
            String instanceId,
            String conch,
            String fromVersion,
            String toVersion,
            String reason,
            Instant occurredAt,
            Instant receivedAt,
            String owner) {
    }
}
