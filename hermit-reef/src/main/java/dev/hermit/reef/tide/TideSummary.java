package dev.hermit.reef.tide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TideSummary(
        String bucket,
        Instant from,
        Instant to,
        String p95Note,
        List<TidePoint> series) {

    public static final String P95_NOTE =
            "p95Millis is the maximum of the per-row p95 values in the bucket and is approximate";

    public record TidePoint(
            String conch,
            String action,
            Instant bucketStart,
            long success,
            long failure,
            BigDecimal failureRate,
            BigDecimal avgMillis,
            Long p95Millis) {
    }
}
