package dev.hermit.crab.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.Redactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevTraceDraftTest {
    private static final String PASSWORD = "p@ss word-91";
    private static final String COOKIE = "super-secret-cookie-91";

    @Test
    void masksKnownSecretsAndDropsSensitiveHeaders() {
        String encoded = URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        String encodedSpace = encoded.replace("+", "%20");
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + PASSWORD);
        headers.put("Cookie", "n=" + PASSWORD);
        headers.put("X-Note", "hello");
        LinkedHashMap<String, Object> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("Set-Cookie", "SID=" + COOKIE);
        responseHeaders.put("Authorization", "Bearer " + PASSWORD);
        responseHeaders.put("Mcp-Session-Id", "sess-secret-value-77");
        responseHeaders.put("X-Note", "kept");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("method", "POST");
        spec.put("path", "/echo");
        spec.put("query", Map.of("token", PASSWORD));
        spec.put("headers", headers);
        spec.put("json", Map.of("password", PASSWORD, "pin", "abc"));
        DevTraceDraft draft = new DevTraceDraft(null);
        draft.login(true, true);
        draft.add("action", 0, 4, DevTraceDraft.requestPiece(
                spec, "POST", "/echo", 200, responseHeaders,
                PASSWORD + " " + encoded + " " + encodedSpace + " abc " + COOKIE));
        String json = draft.render(Redactor.of(Set.of(PASSWORD, COOKIE)), List.of("SID"), true);

        assertThat(json).doesNotContain(PASSWORD).doesNotContain(encoded).doesNotContain(encodedSpace);
        assertThat(json).doesNotContain(COOKIE).doesNotContain("sess-secret-value-77");
        assertThat(json).contains("abc").contains("***").contains("SID").contains("hello").contains("kept");
        assertThat(json).contains(DevTraceDraft.UNTRUSTED_NOTE);
        JsonNode root = ApiJson.tree(json);
        assertThat(headerNames(root.path("steps").get(0).path("requestHeaders")))
                .doesNotContain("authorization", "cookie", "Authorization", "Cookie");
        assertThat(headerNames(root.path("steps").get(0).path("responseHeaders")))
                .doesNotContain("set-cookie", "authorization", "mcp-session-id", "Set-Cookie", "Authorization", "Mcp-Session-Id");
        assertThat(root.path("session").path("cookieNames").get(0).asText()).isEqualTo("SID");
        assertThat(root.path("session").path("cookieCount").asInt()).isEqualTo(1);
        assertThat(root.path("session").path("stored").asBoolean()).isTrue();
        assertThat(root.path("session").path("loginRan").asBoolean()).isTrue();
        assertThat(root.path("session").path("loginOk").asBoolean()).isTrue();
    }

    @Test
    void keepsSecretsShorterThanFourCharacters() {
        DevTraceDraft draft = new DevTraceDraft(null);
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("json", Map.of("pin", "abc"));
        draft.add("action", 0, 1, DevTraceDraft.requestPiece(
                spec, "POST", "/echo", 200, Map.of(), "pin=abc"));
        String json = draft.render(Redactor.of(Set.of("abc", "long-enough")), List.of(), false);
        assertThat(json).contains("abc").doesNotContain("long-enough");
    }

    @Test
    void redactionFailureDropsTheBody() {
        Map<String, Object> bomb = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("secret-in-the-exception");
            }
        };
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "request");
        fields.put("responseBody", "visible-secret-value");
        fields.put("requestBody", bomb);
        DevTraceDraft draft = new DevTraceDraft(null);
        draft.add("action", 0, 1, fields);
        String json = draft.render(Redactor.of(Set.of("visible-secret-value")), List.of("SID"), true);
        assertThat(json).contains(DevTraceDraft.REDACTION_FAILED);
        assertThat(json).doesNotContain("visible-secret-value").doesNotContain("secret-in-the-exception");
        assertThat(json).doesNotContain("SID");
    }

    @Test
    void truncatesAStepAt256KibAndATraceAt2Mib() {
        String marker = "END-OF-BODY-MARKER";
        DevTraceDraft step = new DevTraceDraft(null);
        step.add("action", 0, 1, DevTraceDraft.requestPiece(
                Map.of(), "GET", "/big", 200, Map.of(), "x".repeat(300_000) + marker));
        String stepJson = step.render(Redactor.of(Set.of()), List.of(), false);
        assertThat(stepJson).doesNotContain(marker).contains(DevTraceDraft.STEP_NOTE);
        JsonNode renderedStep = ApiJson.tree(stepJson).path("steps").get(0);
        assertThat(ApiJson.write(renderedStep).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DevTraceDraft.STEP_LIMIT);

        DevTraceDraft trace = new DevTraceDraft(null);
        for (int i = 0; i < 12; i++) {
            String body = "y".repeat(200_000) + (i == 11 ? "TRACE-TAIL-MARKER" : "");
            trace.add("action", i, 1, DevTraceDraft.requestPiece(
                    Map.of(), "GET", "/big", 200, Map.of(), body));
        }
        String traceJson = trace.render(Redactor.of(Set.of()), List.of(), false);
        assertThat(traceJson.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(DevTraceDraft.TRACE_LIMIT);
        assertThat(traceJson).contains(DevTraceDraft.TRACE_NOTE).doesNotContain("TRACE-TAIL-MARKER");
    }

    @Test
    void jsonRpcHookDropsSessionIdAndMasksSecrets() {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("name", "tools/list");
        params.put("Mcp-Session-Id", "sess-secret-value-77");
        params.put("headers", Map.of("Authorization", "Bearer " + PASSWORD, "X-Note", "hello"));
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", "tools/list");
        fields.put("id", 1);
        fields.put("httpStatus", 200);
        fields.put("durationMs", 3);
        fields.put("params", params);
        fields.put("result", Map.of("token", PASSWORD));
        String json = DevTraceDraft.renderJsonRpc(fields, Redactor.of(Set.of(PASSWORD)));
        assertThat(json).contains("\"kind\":\"jsonrpc\"").contains(DevTraceDraft.UNTRUSTED_NOTE);
        assertThat(json).doesNotContain(PASSWORD).doesNotContain("sess-secret-value-77");
        assertThat(json).contains("hello").contains("***");
        assertThat(headerNames(ApiJson.tree(json).path("params").path("headers")))
                .doesNotContain("Authorization", "authorization");
        assertThat(ApiJson.tree(json).path("exchanges")).hasSize(1);
    }

    @Test
    void jsonRpcResultIsCappedLikeOneStep() {
        String marker = "JSONRPC-TAIL-MARKER";
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", "tools/list");
        fields.put("result", Map.of("blob", "x".repeat(300_000) + marker));
        String json = DevTraceDraft.renderJsonRpc(fields, Redactor.of(Set.of()));
        assertThat(json).doesNotContain(marker).contains(DevTraceDraft.STEP_NOTE);
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(DevTraceDraft.TRACE_LIMIT);
        JsonNode result = ApiJson.tree(json).path("result");
        assertThat(ApiJson.write(result).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DevTraceDraft.STEP_LIMIT);
        String second = DevTraceDraft.renderJsonRpc(Map.of("method", "tools/call", "result", Map.of("ok", true)),
                Redactor.of(Set.of()));
        String merged = DevTraceDraft.mergeJsonRpc(json, second);
        assertThat(merged).contains("tools/call").doesNotContain(marker);
        assertThat(merged.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(DevTraceDraft.TRACE_LIMIT);
        assertThat(ApiJson.tree(merged).path("method").asText()).isEqualTo("tools/call");
    }

    private static Set<String> headerNames(JsonNode headers) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        headers.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
