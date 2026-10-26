package dev.hermit.common.report;

import java.time.Instant;
import java.util.Objects;

import dev.hermit.common.error.ErrorCode;

public record FailureReport(
        String traceId,
        String conch,
        String conchVersion,
        String action,
        Integer stepIndex,
        String stepType,
        ErrorCode errorCode,
        String summary,
        Instant occurredAt
) {
    public static final int MAX_SUMMARY_CODE_POINTS = 200;

    public FailureReport {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(conch, "conch");
        Objects.requireNonNull(conchVersion, "conchVersion");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (summary != null && summary.codePointCount(0, summary.length()) > MAX_SUMMARY_CODE_POINTS) {
            summary = summary.substring(0, summary.offsetByCodePoints(0, MAX_SUMMARY_CODE_POINTS));
        }
    }
}
