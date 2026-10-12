package dev.hermit.common.report;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReportBatch(
        String instanceId,
        String crabVersion,
        Instant sentAt,
        List<FailureReport> failures,
        List<CountReport> counts,
        List<RollbackEvent> events
) {
    public ReportBatch {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(crabVersion, "crabVersion");
        Objects.requireNonNull(sentAt, "sentAt");
        failures = failures == null ? List.of() : List.copyOf(failures);
        counts = counts == null ? List.of() : List.copyOf(counts);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
