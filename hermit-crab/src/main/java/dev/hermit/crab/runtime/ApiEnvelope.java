package dev.hermit.crab.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;

public final class ApiEnvelope {
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
        return ApiJson.write(of(data, code, message, traceId));
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
