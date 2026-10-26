package dev.hermit.common.report;

import java.time.Instant;
import java.util.Objects;

public record CountReport(
        String conch,
        String conchVersion,
        String action,
        Instant hourStart,
        long success,
        long failure,
        long avgMillis,
        long p95Millis
) {
    public CountReport {
        Objects.requireNonNull(conch, "conch");
        Objects.requireNonNull(conchVersion, "conchVersion");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(hourStart, "hourStart");
        if (hourStart.getNano() != 0 || hourStart.getEpochSecond() % 3600 != 0) {
            throw new IllegalArgumentException("hourStart 必须是 UTC 整点");
        }
    }
}
