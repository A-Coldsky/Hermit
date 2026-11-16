package dev.hermit.crab.gateway;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.runtime.ApiEnvelope;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.runtime.Invoker;
import io.javalin.http.Context;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Response;

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

    /**
     * 网关和控制台自己写出的报错都发生在执行之前，没有调用记录可查：只给请求编号和
     * 「请求编号，不可查追踪」，不写 X-Hermit-Trace。执行过的结果走 {@link #writeOutcome}。
     */
    static void writeError(Context ctx, int status, ErrorCode code, String message) {
        writeRequestError(ctx, status, code, message);
    }

    /** 已经执行过、记了调用或追踪的失败：给能查的追踪编号，也写 X-Hermit-Trace。 */
    static void writeTracedError(Context ctx, int status, ErrorCode code, String message) {
        String traceId = trace(ctx);
        write(ctx, status, ApiJson.bytes(ApiEnvelope.json(null, code, message, traceId)), traceId, false);
    }

    /** Same id space as a trace, but the body says it cannot be opened and the header is omitted. */
    static void writeRequestError(Context ctx, int status, ErrorCode code, String message) {
        writeRequestError(ctx, status, code, message, null);
    }

    static void writeRequestError(Context ctx, int status, ErrorCode code, String message, Object data) {
        write(ctx, status, ApiJson.bytes(ApiEnvelope.requestJson(data, code, UserMessages.zh(message), trace(ctx))), null, false);
    }

    static void writeData(Context ctx, Object data) {
        String traceId = trace(ctx);
        write(ctx, 200, ApiJson.bytes(ApiEnvelope.json(data, null, null, traceId)), traceId, false);
    }

    static void writeOutcome(Context ctx, InvokeOutcome outcome) {
        write(ctx, outcome.status(), outcome.body(), outcome.traceId(), outcome.replayed(), confirmToken(outcome.body()));
    }

    static void writeBytes(Context ctx, int status, String contentType, String cacheControl, byte[] body) {
        if (written(ctx)) {
            return;
        }
        ctx.attribute(WRITTEN, Boolean.TRUE);
        ctx.status(status);
        ctx.header("Content-Type", contentType);
        // Jetty 把已知的 text/html;charset=UTF-8 收成预编码的 utf-8。页面约定要原样的 charset 名。
        if (ctx.res() instanceof Response jetty) {
            jetty.getHttpFields().put(new HttpField(HttpHeader.CONTENT_TYPE, contentType));
        }
        ctx.header("Cache-Control", cacheControl);
        ctx.header("X-Hermit-Trace", trace(ctx));
        ctx.result(body);
    }

    static void write(Context ctx, int status, byte[] body, String traceId, boolean replayed) {
        write(ctx, status, body, traceId, replayed, null);
    }

    static void write(Context ctx, int status, byte[] body, String traceId, boolean replayed, String confirmToken) {
        if (written(ctx)) {
            return;
        }
        ctx.attribute(WRITTEN, Boolean.TRUE);
        ctx.status(status);
        ctx.contentType("application/json; charset=utf-8");
        if (traceId != null) {
            ctx.header("X-Hermit-Trace", traceId);
        }
        if (replayed) {
            ctx.header("Idempotent-Replayed", "true");
        }
        if (confirmToken != null) {
            ctx.header(Invoker.CONFIRM_TOKEN_HEADER, confirmToken);
        }
        ctx.result(body == null ? ApiJson.bytes("{}") : body);
    }

    /** 确认挑战的 message 里那枚令牌。其它响应不带头。 */
    private static String confirmToken(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode message = ApiJson.tree(body).path("error").path("message");
        if (!message.isTextual()) {
            return null;
        }
        return Invoker.issuedConfirmToken(message.asText());
    }
}
