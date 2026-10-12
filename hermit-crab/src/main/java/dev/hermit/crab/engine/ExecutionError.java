package dev.hermit.crab.engine;

import java.util.Objects;

import dev.hermit.common.error.ErrorCode;

public record ExecutionError(
        ErrorCode code,
        String message,
        Integer failedStepIndex,
        String failedStepType,
        String summary
) {
    public ExecutionError {
        Objects.requireNonNull(code, "code");
    }
}
