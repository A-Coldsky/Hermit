package dev.hermit.crab.engine;

import java.util.List;
import java.util.Map;

import dev.hermit.common.error.ErrorCode;

final class RuleProblem extends RuntimeException {

    final ErrorCode code;
    final String summary;
    final String method;
    final String path;
    final Integer status;
    final Map<String, Object> transport;

    RuleProblem(ErrorCode code, String summary, String method, String path, Integer status) {
        this(code, summary, method, path, status, null);
    }

    RuleProblem(ErrorCode code, String summary, String method, String path, Integer status, Map<String, Object> transport) {
        super(summary);
        this.code = code;
        this.summary = summary;
        this.method = method;
        this.path = path;
        this.status = status;
        this.transport = transport == null || transport.isEmpty() ? null : Map.copyOf(transport);
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
