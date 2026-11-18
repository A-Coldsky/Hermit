package dev.hermit.crab.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.testing.SecretChecks;
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
        headers.put("Proxy-Authorization", "Basic " + PASSWORD);
        headers.put("Cookie", "n=" + PASSWORD);
        headers.put("X-Note", "hello");
        LinkedHashMap<String, Object> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("Set-Cookie", "SID=" + COOKIE);
        responseHeaders.put("Authorization", "Bearer " + PASSWORD);
        responseHeaders.put("Proxy-Authorization", "Basic " + PASSWORD);
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
                .doesNotContain("authorization", "proxy-authorization", "cookie", "Authorization", "Proxy-Authorization", "Cookie");
        assertThat(headerNames(root.path("steps").get(0).path("responseHeaders")))
                .doesNotContain(
                        "set-cookie", "authorization", "proxy-authorization", "mcp-session-id",
                        "Set-Cookie", "Authorization", "Proxy-Authorization", "Mcp-Session-Id");
        assertThat(root.path("session").path("cookieNames").get(0).asText()).isEqualTo("SID");
        assertThat(root.path("session").path("cookieCount").asInt()).isEqualTo(1);
        assertThat(root.path("session").path("stored").asBoolean()).isTrue();
        assertThat(root.path("session").path("loginRan").asBoolean()).isTrue();
        assertThat(root.path("session").path("loginOk").asBoolean()).isTrue();
    }

    @Test
    void masksSecretsInsideTraceWarnings() {
        DevTraceDraft draft = new DevTraceDraft(null);
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("method", "GET");
        spec.put("path", "/hold");
        Map<String, Object> piece = DevTraceDraft.requestPiece(
                spec, "GET", "/hold", 200, Map.of(), PASSWORD);
        HttpCalls.attachWarnings(piece, List.of(new TraceWarning(
                OutboundHttp.BODY_ENDED_BY_IDLE, "leak " + PASSWORD)));
        draft.add("action", 0, 4, piece);
        String json = draft.render(Redactor.of(Set.of(PASSWORD)), List.of(), false);

        assertThat(json).doesNotContain(PASSWORD);
        JsonNode message = ApiJson.tree(json).path("steps").get(0).path("warnings").get(0);
        assertThat(message.path("code").asText()).isEqualTo(OutboundHttp.BODY_ENDED_BY_IDLE);
        assertThat(message.path("message").asText()).isEqualTo("leak ***");
        assertThat(OutboundHttp.BODY_ENDED_BY_IDLE_MESSAGE).doesNotContain(PASSWORD);
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

    @Test
    void credentialBearingBodyHidesATokenWhenExtractWouldFail() {
        String token = "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0";
        DevTraceDraft draft = new DevTraceDraft(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        Map<String, Object> login = DevTraceDraft.requestPiece(
                Map.of("method", "POST", "path", "/login"),
                "POST", "/login", 200, Map.of(),
                "{\"session\":\"abc\",\"note\":\"" + token + "\",\"side\":\"left\"}");
        login.put("credentialBearing", Boolean.TRUE);
        draft.add("login", 0, 1, login);
        draft.add("action", 1, 1, DevTraceDraft.requestPiece(
                Map.of(), "GET", "/items", 200, Map.of(),
                "{\"side\":\"left\",\"title\":\"hello-business\"}"));
        Map<String, Object> page = DevTraceDraft.requestPiece(
                Map.of(), "GET", "/page", 200, Map.of(), "<html>" + token + "</html>");
        page.put("credentialBearing", Boolean.TRUE);
        draft.add("alive_check", -1, 1, page);
        String rendered = draft.render(Redactor.of(Set.of()), List.of(), false);
        assertThat(rendered).doesNotContain(token).doesNotContain("abc");
        assertThat(rendered).contains("left").contains("hello-business");
        assertThat(rendered).contains(DevTraceDraft.HIDDEN_CREDENTIAL_BODY);
    }

    @Test
    void credentialBearingJsonDoesNotRestoreEscapedSecrets() {
        String secret = "secret/value";
        String quote = "se\"cret";
        String body = "{\"msg\":\"secret\\/value\","
                + "\"note\":\"id=secret\\/value&ok=1\","
                + "\"nested\":{\"label\":\"secret\\u002fvalue\"},"
                + "\"items\":[\"left\",\"secret\\/value\",{\"label\":\"secret\\/value\"}],"
                + "\"quote\":\"se\\\"cret\","
                + "\"password\":\"secret\\/value-tail-keep-91\","
                + "\"path\":\"a/b\","
                + "\"side\":\"left\",\"count\":1}";
        String arrayBody = "[\"secret\\/value\",{\"label\":\"se\\\"cret\"}]";
        assertThat(body).doesNotContain(secret).doesNotContain(quote);
        assertThat(arrayBody).doesNotContain(secret).doesNotContain(quote);

        DevTraceDraft draft = new DevTraceDraft(null);
        Map<String, Object> login = DevTraceDraft.requestPiece(
                Map.of("method", "POST", "path", "/login"),
                "POST", "/login", 200, Map.of(), body);
        login.put("credentialBearing", Boolean.TRUE);
        draft.add("login", 0, 1, login);
        Map<String, Object> array = DevTraceDraft.requestPiece(
                Map.of(), "POST", "/tokens", 200, Map.of(), arrayBody);
        array.put("credentialBearing", Boolean.TRUE);
        draft.add("alive_check", -1, 1, array);

        String rendered = draft.render(Redactor.of(Set.of(secret, quote)), List.of(), false);
        SecretChecks.absent(rendered, secret);
        SecretChecks.absent(rendered, quote);
        SecretChecks.absent(rendered, "tail-keep-91");
        assertThat(rendered).contains("left").contains("a/b").contains("***");

        JsonNode fields = ApiJson.tree(ApiJson.tree(rendered).path("steps").get(0).path("responseBody").asText());
        assertText(fields.path("msg").asText(), "***");
        assertText(fields.path("note").asText(), "id=***&ok=1");
        assertText(fields.path("nested").path("label").asText(), "***");
        assertText(fields.path("items").get(0).asText(), "left");
        assertText(fields.path("items").get(1).asText(), "***");
        assertText(fields.path("items").get(2).path("label").asText(), "***");
        assertText(fields.path("quote").asText(), "***");
        assertText(fields.path("password").asText(), "***");
        assertText(fields.path("path").asText(), "a/b");
        assertText(fields.path("side").asText(), "left");
        assertThat(fields.path("count").asInt()).isEqualTo(1);

        JsonNode listed = ApiJson.tree(ApiJson.tree(rendered).path("steps").get(1).path("responseBody").asText());
        assertText(listed.get(0).asText(), "***");
        assertText(listed.get(1).path("label").asText(), "***");
    }

    @Test
    void base64UrlTokenIsMaskedFromItsFirstCharacterThroughItsLast() {
        String leak4 = shaped(43, 3, '-');
        String leak7 = shaped(43, 6, '_');
        String leak11 = shaped(43, 10, '-');
        String suffix32 = shaped(36, 3, '_');
        String leak20 = shaped(64, 19, '-');
        String leak31 = shaped(86, 30, '_');
        String leading = shaped(33, 0, '-');
        String minimum = shaped(32, 10, '_');
        String split = splitToken();
        String both = bothSides();
        String hexTail = "Qq9Z-" + "ab".repeat(16);
        String embedded = "id=" + leak7 + "&ok=1";
        String pair = leak4 + "," + leak11;
        String beside = leak20 + " left";
        String standard = "G".repeat(3) + "+" + "G".repeat(20) + "/" + "G".repeat(20) + "==";
        String jwt = "eyJ" + "G".repeat(30) + ".Zz9k." + "G".repeat(30);
        String keep = "below-threshold-dashed-valueyzz";
        String business = "M".repeat(20) + "_" + "M".repeat(22);
        String known = "k9-x_2";
        List<String> hidden = List.of(
                leak4, leak7, leak11, suffix32, leak20, leak31, leading, minimum, split, both, hexTail,
                standard, jwt);

        LinkedHashMap<String, Object> credential = new LinkedHashMap<>();
        credential.put("leak4", leak4);
        credential.put("leak7", leak7);
        credential.put("leak11", leak11);
        credential.put("suffix32", suffix32);
        credential.put("leak20", leak20);
        credential.put("leak31", leak31);
        credential.put("leading", leading);
        credential.put("minimum", minimum);
        credential.put("split", split);
        credential.put("both", both);
        credential.put("hexTail", hexTail);
        credential.put("embedded", embedded);
        credential.put("pair", pair);
        credential.put("beside", beside);
        credential.put("standard", standard);
        credential.put("jwt", jwt);
        credential.put("keep", keep);
        credential.put("side", "left");
        credential.put("title", "hello-business");
        credential.put("known", known);
        credential.put("tiny", "a-b");
        credential.put("nested", Map.of("label", leak4));
        credential.put("items", List.of("left", leak11));

        DevTraceDraft draft = new DevTraceDraft(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        Map<String, Object> login = DevTraceDraft.requestPiece(
                Map.of("method", "POST", "path", "/login"),
                "POST", "/login", 200, Map.of(), ApiJson.write(credential));
        login.put("credentialBearing", Boolean.TRUE);
        draft.add("login", 0, 1, login);
        draft.add("action", 1, 1, DevTraceDraft.requestPiece(
                Map.of(), "GET", "/biz", 200, Map.of(),
                ApiJson.write(Map.of("title", "hello-business", "ref", business, "known", known, "tiny", "a-b"))));
        String rendered = draft.render(Redactor.of(Set.of(known)), List.of(), false);

        JsonNode fields = ApiJson.tree(ApiJson.tree(rendered).path("steps").get(0).path("responseBody").asText());
        for (String name : List.of(
                "leak4", "leak7", "leak11", "suffix32", "leak20", "leak31", "leading", "minimum",
                "split", "both", "hexTail", "standard", "jwt")) {
            assertText(fields.path(name).asText(), "***");
        }
        assertText(fields.path("embedded").asText(), "id=***&ok=1");
        assertText(fields.path("pair").asText(), "***,***");
        assertText(fields.path("beside").asText(), "*** left");
        assertText(fields.path("nested").path("label").asText(), "***");
        assertText(fields.path("items").get(0).asText(), "left");
        assertText(fields.path("items").get(1).asText(), "***");
        assertText(fields.path("keep").asText(), keep);
        assertText(fields.path("side").asText(), "left");
        assertText(fields.path("title").asText(), "hello-business");
        assertText(fields.path("known").asText(), "***");
        assertText(fields.path("tiny").asText(), "a-b");

        for (String token : hidden) {
            assertNoPlainSegment(rendered, token);
        }
        SecretChecks.absent(rendered, "Zz9k");
        SecretChecks.absent(rendered, known);

        JsonNode plain = ApiJson.tree(ApiJson.tree(rendered).path("steps").get(1).path("responseBody").asText());
        assertText(plain.path("ref").asText(), business);
        assertText(plain.path("title").asText(), "hello-business");
        assertText(plain.path("known").asText(), "***");
        assertText(plain.path("tiny").asText(), "a-b");
        assertHas(rendered, business);
        assertHas(rendered, "hello-business");
        assertHas(rendered, "a-b");
    }

    private static String shaped(int length, int separatorAt, char separator) {
        StringBuilder raw = new StringBuilder(length);
        String alphabet = "Qq9Z";
        for (int i = 0; i < length; i++) {
            raw.append(alphabet.charAt(i % alphabet.length()));
        }
        raw.setCharAt(separatorAt, separator);
        return raw.toString();
    }

    private static String splitToken() {
        StringBuilder raw = new StringBuilder(shaped(60, 4, '-'));
        raw.setCharAt(9, '_');
        raw.setCharAt(14, '-');
        return raw.toString();
    }

    private static String bothSides() {
        return shaped(70, 34, '-');
    }

    private static void assertNoPlainSegment(String rendered, String token) {
        SecretChecks.absent(rendered, token);
        int start = 0;
        for (int i = 0; i <= token.length(); i++) {
            boolean boundary = i == token.length() || token.charAt(i) == '-' || token.charAt(i) == '_';
            if (!boundary) {
                continue;
            }
            if (i - start >= 4) {
                SecretChecks.absent(rendered, token.substring(start, i));
            }
            start = i + 1;
        }
        int last = Math.max(token.lastIndexOf('-'), token.lastIndexOf('_'));
        if (last >= 3) {
            SecretChecks.absent(rendered, token.substring(0, last + 1));
        }
    }

    private static void assertText(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("text differed; actual length " + (actual == null ? -1 : actual.length())
                    + ", expected length " + expected.length());
        }
    }

    private static void assertHas(String haystack, String plain) {
        if (haystack == null || !haystack.contains(plain)) {
            throw new AssertionError("missing plain text of length " + plain.length());
        }
    }

    private static Set<String> headerNames(JsonNode headers) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        headers.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
