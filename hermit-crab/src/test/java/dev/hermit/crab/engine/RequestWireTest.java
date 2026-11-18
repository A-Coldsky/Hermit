package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.Redactor;

/**
 * 每个字符的期望是手写的。套接字上的请求行是依据，不从 {@code UrlComponents} 再算一遍。
 */
class RequestWireTest {
    @Test
    void queryUrlAndFormAndPathUseSeparateCharacterTables() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            LinkedHashMap<String, Object> query = characterQuery();
            LinkedHashMap<String, Object> url = request("GET", "/p");
            url.put("query", query);
            RawLoopback.Hit urlHit = loop.exchange(url);
            assertThat(urlHit.captured().requestLine).isEqualTo("GET /p?" + URL_QUERY + " HTTP/1.1");
            assertThat(urlHit.captured().target).isEqualTo("/p?" + URL_QUERY);
            assertThat(urlHit.result().path()).isEqualTo("/p");
            assertThat(urlHit.result().wire().get("target")).isEqualTo("/p?" + URL_QUERY);
            assertClientHeaders(urlHit);

            LinkedHashMap<String, Object> form = request("GET", "/p");
            form.put("query", characterQuery());
            form.put("query_style", "form");
            RawLoopback.Hit formHit = loop.exchange(form);
            assertThat(formHit.captured().requestLine).isEqualTo("GET /p?" + FORM_QUERY + " HTTP/1.1");
            assertThat(formHit.result().wire().get("target")).isEqualTo("/p?" + FORM_QUERY);

            LinkedHashMap<String, Object> path = request("GET", PATH_RAW);
            RawLoopback.Hit pathHit = loop.exchange(path);
            assertThat(pathHit.captured().requestLine).isEqualTo("GET " + PATH_ENCODED + " HTTP/1.1");
            assertThat(pathHit.result().path()).isEqualTo(PATH_ENCODED);
            assertThat(pathHit.result().wire().get("target")).isEqualTo(PATH_ENCODED);
            assertThat(pathHit.captured().requestLine).doesNotContain("\\");
        }
    }

    @Test
    void specialsKeepSlashHashPercentAndDoubleSlash() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            RawLoopback.Hit hash = loop.exchange(request("GET", "/p/X#Y"));
            assertThat(hash.captured().requestLine).isEqualTo("GET /p/X HTTP/1.1");
            assertThat(new String(hash.captured().head, StandardCharsets.ISO_8859_1)).doesNotContain("#");
            assertThat(hash.result().path()).isEqualTo("/p/X");
            assertThat(hash.result().wire().get("target")).isEqualTo("/p/X");

            RawLoopback.Hit question = loop.exchange(request("GET", "/p/X?Y"));
            assertThat(question.captured().requestLine).isEqualTo("GET /p/X?Y HTTP/1.1");

            RawLoopback.Hit kept = loop.exchange(request("GET", "/p/%3A"));
            assertThat(kept.captured().requestLine).isEqualTo("GET /p/%3A HTTP/1.1");

            LinkedHashMap<String, Object> again = request("GET", "/p");
            again.put("query", Map.of("q", "%3A"));
            RawLoopback.Hit encoded = loop.exchange(again);
            assertThat(encoded.captured().requestLine).isEqualTo("GET /p?q=%253A HTTP/1.1");

            LinkedHashMap<String, Object> joined = request("GET", "a//b");
            RawLoopback.Hit slash = loop.exchange(joined, "/app/", 200);
            assertThat(slash.captured().requestLine).isEqualTo("GET /app/a//b HTTP/1.1");

            LinkedHashMap<String, Object> withQuery = request("GET", "a//b");
            withQuery.put("query", Map.of("x", "1"));
            RawLoopback.Hit both = loop.exchange(withQuery, "/app/", 200);
            assertThat(both.captured().requestLine).isEqualTo("GET /app/a//b?x=1 HTTP/1.1");

            LinkedHashMap<String, Object> dots = request("GET", "a//../b");
            RawLoopback.Hit popped = loop.exchange(dots, "/app/", 200);
            assertThat(popped.captured().requestLine).isEqualTo("GET /app/a/b HTTP/1.1");

            RawLoopback.Hit absolute = loop.exchange(request("GET", "/app/a/../secret"));
            assertThat(absolute.captured().requestLine).isEqualTo("GET /app/a/../secret HTTP/1.1");

            RawLoopback.Hit percentDots = loop.exchange(request("GET", "/app/%2e%2e/secret"));
            assertThat(percentDots.captured().requestLine).isEqualTo("GET /app/%2e%2e/secret HTTP/1.1");

            RawLoopback.Hit emptyQuery = loop.exchange(request("GET", "/p?"));
            assertThat(emptyQuery.captured().requestLine).isEqualTo("GET /p? HTTP/1.1");
        }
    }

    @Test
    void pathUsesTheStepCharsetAndFormBodyStaysFormEncoded() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            LinkedHashMap<String, Object> gbk = request("GET", "/p/中文");
            gbk.put("encoding", "GBK");
            RawLoopback.Hit encoded = loop.exchange(gbk);
            assertThat(encoded.captured().requestLine).isEqualTo("GET /p/%D6%D0%CE%C4 HTTP/1.1");

            LinkedHashMap<String, Object> utf8 = request("GET", "/p/中文");
            RawLoopback.Hit unicode = loop.exchange(utf8);
            assertThat(unicode.captured().requestLine).isEqualTo("GET /p/%E4%B8%AD%E6%96%87 HTTP/1.1");

            LinkedHashMap<String, Object> body = request("POST", "/p");
            body.put("query", Map.of("q", "X:Y"));
            body.put("form", Map.of("f", "X:Y"));
            RawLoopback.Hit posted = loop.exchange(body);
            assertThat(posted.captured().requestLine).isEqualTo("POST /p?q=X:Y HTTP/1.1");
            assertThat(posted.captured().body).isEqualTo("f=X%3AY".getBytes(StandardCharsets.US_ASCII));
            assertThat(posted.captured().headers.get("content-type")).contains("application/x-www-form-urlencoded");
            assertThat(posted.result().wire().get("body")).isEqualTo("f=X%3AY");
            assertThat(posted.result().wire().get("contentType").toString()).contains("charset");
        }
    }

    @Test
    void jsonUsesTheStepCharsetAndStopsWhenACharacterCannotBeSent() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            LinkedHashMap<String, Object> spec = request("POST", "/p");
            spec.put("encoding", "GBK");
            spec.put("json", Map.of("n", "中"));
            RawLoopback.Hit hit = loop.exchange(spec);
            byte[] expected = "{\"n\":\"中\"}".getBytes(Charset.forName("GBK"));
            assertThat(hit.captured().body).isEqualTo(expected);
            assertThat(hit.captured().body).isNotEqualTo("{\"n\":\"中\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(hit.captured().headers.get("content-type"))
                    .contains("application/json")
                    .contains("charset=GBK");
            assertThat(hit.result().wire().get("body")).isEqualTo("{\"n\":\"中\"}");
            assertThat(hit.result().wire().get("contentType").toString()).contains("charset=GBK");

            LinkedHashMap<String, Object> plain = request("POST", "/p");
            plain.put("json", Map.of("n", "ok"));
            RawLoopback.Hit utf8 = loop.exchange(plain);
            assertThat(utf8.captured().headers.get("content-type")).contains("charset=UTF-8");
            assertThat(utf8.captured().body).isEqualTo("{\"n\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void unsendableCharactersFailBeforeTheSocketSeesARequest() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            LinkedHashMap<String, Object> header = request("GET", "/p");
            header.put("headers", Map.of("X-Name", "中文"));
            RuleProblem problem = loop.refuse(header);
            assertThat(problem.code).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(problem.getMessage()).isEqualTo("请求头 X-Name 含有无法发送的字符，请先用 urlencode 处理");
            assertThat(problem.wire).isNull();

            LinkedHashMap<String, Object> latin = request("GET", "/p");
            latin.put("headers", Map.of("X-Name", "caf\u00e9"));
            RawLoopback.Hit sent = loop.exchange(latin);
            assertThat(sent.captured().head).contains((byte) 0xE9);
            assertThat(sent.captured().headers.get("x-name")).isEqualTo("caf\u00e9");

            LinkedHashMap<String, Object> query = request("GET", "/p");
            query.put("encoding", "ISO-8859-1");
            query.put("query", Map.of("q", "中文"));
            RuleProblem unencodable = loop.refuse(query);
            assertThat(unencodable.getMessage()).isEqualTo("字符无法用 ISO-8859-1 发送");

            LinkedHashMap<String, Object> json = request("POST", "/p");
            json.put("encoding", "ISO-8859-1");
            json.put("json", Map.of("n", "中"));
            RuleProblem jsonProblem = loop.refuse(json);
            assertThat(jsonProblem.getMessage()).isEqualTo("json 含有编码无法表示的字符");

            LinkedHashMap<String, Object> style = request("GET", "/p");
            style.put("query_style", "nope");
            RuleProblem styleProblem = loop.refuse(style);
            assertThat(styleProblem.getMessage()).isEqualTo("query_style 只允许 url 或 form");
        }
    }

    @Test
    void wireDropsSecretsKeepsClientHeadersAndTruncatesTheBody() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer secret-value");
            headers.put("Proxy-Authorization", "Basic secret-value");
            headers.put("Cookie", "sid=secret-value");
            headers.put("X-Note", "hello");
            LinkedHashMap<String, Object> spec = request("POST", "/p");
            spec.put("headers", headers);
            spec.put("json", Map.of("n", "a".repeat(70_000)));
            RawLoopback.Hit hit = loop.exchange(spec);
            assertThat(hit.captured().requestLine).doesNotContain("Authorization");
            String rawHead = new String(hit.captured().head, StandardCharsets.ISO_8859_1);
            assertThat(rawHead)
                    .contains("Authorization: Bearer secret-value")
                    .contains("Proxy-Authorization: Basic secret-value")
                    .doesNotContain("sid=");
            assertThat(hit.result().wire().toString()).doesNotContain("secret-value").doesNotContain("Bearer");
            assertClientHeaders(hit);
            @SuppressWarnings("unchecked")
            Map<String, Object> wireHeaders = (Map<String, Object>) hit.result().wire().get("headers");
            assertThat(wireHeaders).containsKeys("User-Agent", "Accept-Encoding", "X-Note");
            assertThat(wireHeaders.keySet()).map(name -> name.toLowerCase())
                    .doesNotContain("authorization", "proxy-authorization", "cookie", "set-cookie", "mcp-session-id");
            assertThat(hit.result().wire()).doesNotContainKey("truncated");
            assertThat(hit.result().wire().get("body").toString().getBytes(StandardCharsets.UTF_8).length)
                    .isGreaterThan(HttpCalls.WIRE_BODY_LIMIT);
            assertThat(hit.captured().body.length).isGreaterThan(HttpCalls.WIRE_BODY_LIMIT);
            JsonNode stored = storedWire(spec, hit, Set.of());
            assertThat(stored.path("truncated").asBoolean()).isTrue();
            assertThat(stored.path("truncation").asText()).isEqualTo(HttpCalls.WIRE_BODY_NOTE);
            assertThat(stored.path("body").asText().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(HttpCalls.WIRE_BODY_LIMIT);

            RawLoopback.Attempt failed = loop.send(request("GET", "/p"), "/", 500);
            assertThat(failed.problem()).isNotNull();
            assertThat(failed.problem().getMessage()).isEqualTo("上游状态码 500");
            assertThat(failed.problem().wire.get("target")).isEqualTo("/p");
            assertThat(failed.captured().requestLine).isEqualTo("GET /p HTTP/1.1");
        }
    }

    @Test
    void secretStraddlingTheWireCutIsMaskedBeforeTruncation() throws Exception {
        String secret = "StraddleSecret";
        int limit = HttpCalls.WIRE_BODY_LIMIT;
        String prefix = "{\"n\":\"";
        int head = 6;
        String content = "a".repeat(limit - head - prefix.length()) + secret + "z".repeat(40);
        try (RawLoopback loop = RawLoopback.open()) {
            LinkedHashMap<String, Object> spec = request("POST", "/p");
            spec.put("json", Map.of("n", content));
            RawLoopback.Hit hit = loop.exchange(spec);
            String raw = hit.result().wire().get("body").toString();
            int at = raw.indexOf(secret);
            assertThat(at).isGreaterThanOrEqualTo(0);
            assertThat(raw.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(limit);
            assertThat(at).isLessThan(limit);
            assertThat(at + secret.length()).isGreaterThan(limit);

            String rendered = render(spec, hit, Set.of(secret));
            assertThat(rendered).doesNotContain(secret)
                    .doesNotContain(secret.substring(0, head))
                    .doesNotContain(secret.substring(head));
            JsonNode stored = ApiJson.tree(rendered).path("steps").get(0).path("wire");
            assertThat(stored.path("truncated").asBoolean()).isTrue();
            assertThat(stored.path("truncation").asText()).isEqualTo(HttpCalls.WIRE_BODY_NOTE);
            assertThat(stored.path("body").asText().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(limit);
            assertThat(stored.path("body").asText()).contains("***");
        }
    }

    private static JsonNode storedWire(Map<String, Object> spec, RawLoopback.Hit hit, Set<String> secrets) {
        return ApiJson.tree(render(spec, hit, secrets)).path("steps").get(0).path("wire");
    }

    private static String render(Map<String, Object> spec, RawLoopback.Hit hit, Set<String> secrets) {
        DevTraceDraft draft = new DevTraceDraft(null);
        draft.add("action", 0, 1, DevTraceDraft.requestPiece(
                spec,
                hit.result().method(),
                hit.result().path(),
                hit.result().status(),
                hit.result().headers(),
                hit.result().body(),
                hit.result().wire()));
        return draft.render(Redactor.of(secrets), List.of(), false);
    }

    private static void assertClientHeaders(RawLoopback.Hit hit) {
        assertThat(hit.captured().headerCount("User-Agent")).isEqualTo(1);
        assertThat(hit.captured().headerCount("Accept-Encoding")).isEqualTo(1);
        assertThat(hit.result().wire().get("headers").toString()).contains("User-Agent").contains("Accept-Encoding");
    }

    private static LinkedHashMap<String, Object> request(String method, String path) {
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("method", method);
        spec.put("path", path);
        return spec;
    }

    private static LinkedHashMap<String, Object> characterQuery() {
        LinkedHashMap<String, Object> query = new LinkedHashMap<>();
        query.put("sp", "X Y");
        query.put("pl", "X+Y");
        query.put("co", "X:Y");
        query.put("ti", "X~Y");
        query.put("st", "X*Y");
        query.put("ex", "X!Y");
        query.put("do", "X$Y");
        query.put("lp", "X(Y");
        query.put("rp", "X)Y");
        query.put("cm", "X,Y");
        query.put("sc", "X;Y");
        query.put("at", "X@Y");
        query.put("sl", "X/Y");
        query.put("qu", "X?Y");
        query.put("ha", "X#Y");
        query.put("pc", "X%Y");
        query.put("dq", "X\"Y");
        query.put("lt", "X<Y");
        query.put("gt", "X>Y");
        query.put("ap", "X'Y");
        query.put("bt", "X`Y");
        query.put("lb", "X{Y");
        query.put("rb", "X}Y");
        query.put("pi", "X|Y");
        query.put("bs", "X\\Y");
        query.put("ca", "X^Y");
        query.put("lk", "X[Y");
        query.put("rk", "X]Y");
        query.put("tb", "X\tY");
        query.put("dl", "X\u007fY");
        query.put("zh", "X中Y");
        return query;
    }

    private static final String URL_QUERY = "sp=X%20Y&pl=X%2BY&co=X:Y&ti=X~Y&st=X*Y&ex=X!Y&do=X$Y"
            + "&lp=X(Y&rp=X)Y&cm=X,Y&sc=X;Y&at=X@Y&sl=X/Y&qu=X?Y&ha=X%23Y&pc=X%25Y&dq=X%22Y"
            + "&lt=X%3CY&gt=X%3EY&ap=X%27Y&bt=X%60Y&lb=X%7BY&rb=X%7DY&pi=X%7CY&bs=X%5CY"
            + "&ca=X%5EY&lk=X%5BY&rk=X%5DY&tb=X%09Y&dl=X%7FY&zh=X%E4%B8%ADY";

    private static final String FORM_QUERY = "sp=X+Y&pl=X%2BY&co=X%3AY&ti=X%7EY&st=X*Y&ex=X%21Y&do=X%24Y"
            + "&lp=X%28Y&rp=X%29Y&cm=X%2CY&sc=X%3BY&at=X%40Y&sl=X%2FY&qu=X%3FY&ha=X%23Y&pc=X%25Y&dq=X%22Y"
            + "&lt=X%3CY&gt=X%3EY&ap=X%27Y&bt=X%60Y&lb=X%7BY&rb=X%7DY&pi=X%7CY&bs=X%5CY"
            + "&ca=X%5EY&lk=X%5BY&rk=X%5DY&tb=X%09Y&dl=X%7FY&zh=X%E4%B8%ADY";

    private static final String PATH_RAW = "/p/X YX+YX:YX~YX*YX!YX$YX(YX)YX,YX;YX@YX/YX&YX=YX%YX\"YX<YX>YX'Y"
            + "X`YX{YX}YX|YX\\YX^YX[YX]YX\tYX\u007fYX中Y";

    private static final String PATH_ENCODED = "/p/X%20YX+YX:YX~YX*YX!YX$YX(YX)YX,YX;YX@YX/YX&YX=YX%25YX%22Y"
            + "X%3CYX%3EYX'YX%60YX%7BYX%7DYX%7CYX%5CYX%5EYX%5BYX%5DYX%09YX%7FYX%E4%B8%ADY";
}
