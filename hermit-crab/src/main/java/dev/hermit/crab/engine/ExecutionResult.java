package dev.hermit.crab.engine;

import java.util.List;

public record ExecutionResult(
        boolean success,
        Object data,
        ExecutionError error,
        long durationMillis,
        List<StepLog> stepLogs,
        String conchVersion,
        String traceId
) {
    public ExecutionResult {
        stepLogs = stepLogs == null ? List.of() : List.copyOf(stepLogs);
    }
}
