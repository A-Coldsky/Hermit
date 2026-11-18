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
    /** 这次已经组好的线上请求。校验失败、还没组请求时为 null。 */
    final Map<String, Object> wire;
    final Map<String, Object> transport;

    RuleProblem(ErrorCode code, String summary, String method, String path, Integer status) {
        this(code, summary, method, path, status, null, null);
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire
    ) {
        this(code, summary, method, path, status, wire, null);
    }

    RuleProblem(
            ErrorCode code,
            String summary,
            String method,
            String path,
            Integer status,
            Map<String, Object> wire,
            Map<String, Object> transport
    ) {
        super(summary);
        this.code = code;
        this.summary = summary;
        this.method = method;
        this.path = path;
        this.status = status;
        this.wire = wire;
        this.transport = transport == null || transport.isEmpty() ? null : Map.copyOf(transport);
    }

    RuleProblem withWire(Map<String, Object> captured) {
        if (wire != null || captured == null) {
            return this;
        }
        return new RuleProblem(code, summary, method, path, status, captured, transport);
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
