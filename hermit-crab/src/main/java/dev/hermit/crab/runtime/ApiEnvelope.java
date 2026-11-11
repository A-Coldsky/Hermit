package dev.hermit.crab.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;

public final class ApiEnvelope {
    public static final String REQUEST_NOTE = "请求编号，不可查追踪";

    private ApiEnvelope() {
    }

    public static Map<String, Object> of(Object data, ErrorCode code, String message, String traceId) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("success", code == null);
        body.put("data", data);
        if (code == null) {
            body.put("error", null);
        } else {
            LinkedHashMap<String, Object> error = new LinkedHashMap<>();
            error.put("code", code.name());
            error.put("message", message == null ? "" : message);
            body.put("error", error);
        }
        body.put("traceId", traceId);
        return body;
    }

    public static String json(Object data, ErrorCode code, String message, String traceId) {
        return json(data, code, message, traceId, true);
    }

    public static String json(Object data, ErrorCode code, String message, String traceId, boolean includeTrace) {
        Map<String, Object> body = of(data, code, message, traceId);
        if (!includeTrace) {
            body.remove("traceId");
        }
        return ApiJson.write(body);
    }

    /** A failure that never stored a trace. {@code requestId} must not be looked up as a trace. */
    public static String requestJson(Object data, ErrorCode code, String message, String requestId) {
        Map<String, Object> body = of(data, code, message, null);
        body.remove("traceId");
        body.put("requestId", requestId);
        body.put("requestNote", REQUEST_NOTE);
        return ApiJson.write(body);
    }

    public static int status(JsonNode envelope) {
        JsonNode error = envelope.get("error");
        if (error == null || error.isNull()) {
            return 200;
        }
        String code = error.path("code").asText(null);
        if (code == null) {
            throw new IllegalArgumentException("invalid json");
        }
        return StatusCodes.of(ErrorCode.valueOf(code));
    }

    public static String traceId(JsonNode envelope) {
        String traceId = envelope.path("traceId").asText(null);
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("invalid json");
        }
        return traceId;
    }
}
