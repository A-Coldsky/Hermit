package dev.hermit.crab.engine;

import java.util.List;

import dev.hermit.common.error.ErrorCode;

final class RuleProblem extends RuntimeException {

    final ErrorCode code;
    final String summary;
    final String method;
    final String path;
    final Integer status;

    RuleProblem(ErrorCode code, String summary, String method, String path, Integer status) {
        super(summary);
        this.code = code;
        this.summary = summary;
        this.method = method;
        this.path = path;
        this.status = status;
    }
}

final class StepFailure extends RuntimeException {

    final ErrorCode code;
    final String summary;
    final int index;
    final String type;
    final List<StepLog> logs;

    StepFailure(ErrorCode code, String summary, int index, String type, List<StepLog> logs) {
        super(summary);
        this.code = code;
        this.summary = summary;
        this.index = index;
        this.type = type;
        this.logs = logs == null ? List.of() : List.copyOf(logs);
    }
}

final class SessionLost extends RuntimeException {
}

final class DeadlineExceeded extends RuntimeException {

    final int index;
    final String type;
    final List<StepLog> logs;

    DeadlineExceeded(int index, String type, List<StepLog> logs) {
        this.index = index;
        this.type = type;
        this.logs = logs == null ? List.of() : List.copyOf(logs);
    }
}

final class InvalidInput extends RuntimeException {

    InvalidInput(String message) {
        super(message);
    }
}
