package dev.hermit.reef.tide;

import java.util.List;

public record FailureDrilldown(List<FailureGroup> groups) {

    public record FailureGroup(
            String conch,
            String conchVersion,
            String action,
            Integer stepIndex,
            String stepType,
            String errorCode,
            long count,
            List<FailureSample> recent) {
    }

    public record FailureSample(String summary, String traceId) {
    }
}
