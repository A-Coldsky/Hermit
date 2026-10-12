package dev.hermit.crab.report;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fixed logarithmic latency histogram. Thirty-two buckets cover 1ms through 60s.
 * A sample at or above the last bound stays in that bucket, and p95 is the upper bound
 * of the bucket that contains the nearest-rank 95th observation.
 */
final class LatencyHistogram {

    static final int BUCKETS = 32;
    static final long MIN_MS = 1L;
    static final long MAX_MS = 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long[] UPPER = bounds();

    private LatencyHistogram() {
    }

    static long[] empty() {
        return new long[BUCKETS];
    }

    static int index(long durationMillis) {
        long duration = Math.max(0L, durationMillis);
        for (int i = 0; i < UPPER.length; i++) {
            if (duration <= UPPER[i]) {
                return i;
            }
        }
        return UPPER.length - 1;
    }

    static long upperBound(long durationMillis) {
        return UPPER[index(durationMillis)];
    }

    static void add(long[] counts, long durationMillis) {
        counts[index(durationMillis)]++;
    }

    static long average(long sumMs, long calls) {
        if (calls <= 0 || sumMs <= 0) {
            return 0L;
        }
        return sumMs / calls;
    }

    static long p95(long[] counts) {
        long total = 0;
        for (long count : counts) {
            if (count > 0) {
                total += count;
            }
        }
        if (total == 0) {
            return 0L;
        }
        long rank = (long) Math.ceil(total * 0.95d);
        if (rank < 1) {
            rank = 1;
        }
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                seen += counts[i];
            }
            if (seen >= rank) {
                return UPPER[i];
            }
        }
        return UPPER[UPPER.length - 1];
    }

    static String write(long[] counts) {
        long[] stored = counts == null ? empty() : counts;
        if (stored.length != BUCKETS) {
            throw new IllegalArgumentException("latency histogram must have 32 buckets");
        }
        try {
            return JSON.writeValueAsString(stored);
        } catch (JacksonException ex) {
            throw new IllegalStateException("latency histogram could not be encoded");
        }
    }

    static long[] read(String json) {
        long[] counts = empty();
        if (json == null || json.isBlank()) {
            return counts;
        }
        try {
            long[] parsed = JSON.readValue(json, long[].class);
            if (parsed == null) {
                return counts;
            }
            System.arraycopy(parsed, 0, counts, 0, Math.min(parsed.length, counts.length));
            return counts;
        } catch (JacksonException ex) {
            return counts;
        }
    }

    static long[] bounds() {
        long[] edges = new long[BUCKETS];
        double logMax = Math.log(MAX_MS);
        for (int i = 0; i < BUCKETS; i++) {
            edges[i] = Math.round(Math.exp(logMax * i / (BUCKETS - 1)));
        }
        edges[0] = MIN_MS;
        edges[BUCKETS - 1] = MAX_MS;
        for (int i = 1; i < BUCKETS - 1; i++) {
            if (edges[i] <= edges[i - 1]) {
                edges[i] = edges[i - 1] + 1;
            }
        }
        return edges;
    }
}
