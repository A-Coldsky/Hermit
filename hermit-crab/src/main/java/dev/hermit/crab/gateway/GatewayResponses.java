package dev.hermit.crab.gateway;

import java.util.UUID;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.InvokeOutcome;
import io.javalin.http.Context;

final class GatewayResponses {
    static final String TRACE = "hermit.traceId";
    private static final String WRITTEN = "hermit.written";

    private GatewayResponses() {
    }

    static String trace(Context ctx) {
        Object value = ctx.attribute(TRACE);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        String created = UUID.randomUUID().toString();
        ctx.attribute(TRACE, created);
        return created;
    }

    static boolean written(Context ctx) {
        return Boolean.TRUE.equals(ctx.attribute(WRITTEN));
    }

    static void writeError(Context ctx, int status, ErrorCode code, String message) {
        String traceId = trace(ctx);
        write(ctx, status, ApiJson.bytes(ApiEnvelope.json(null, code, message, traceId)), traceId, false);
    }

    static void writeData(Context ctx, Object data) {
        String traceId = trace(ctx);
        write(ctx, 200, ApiJson.bytes(ApiEnvelope.json(data, null, null, traceId)), traceId, false);
    }

    static void writeOutcome(Context ctx, InvokeOutcome outcome) {
        write(ctx, outcome.status(), outcome.body(), outcome.traceId(), outcome.replayed());
    }

    static void write(Context ctx, int status, byte[] body, String traceId, boolean replayed) {
        if (written(ctx)) {
            return;
        }
        ctx.attribute(WRITTEN, Boolean.TRUE);
        ctx.status(status);
        ctx.contentType("application/json; charset=utf-8");
        ctx.header("X-Hermit-Trace", traceId);
        if (replayed) {
            ctx.header("Idempotent-Replayed", "true");
        }
        ctx.result(body == null ? ApiJson.bytes("{}") : body);
    }
}
