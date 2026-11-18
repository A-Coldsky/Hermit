package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.crab.CrabMain;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独立 Crab 进程对回环老网关核对请求行字节。网关按原始查询串查命令，不解码百分号：
 * {@code func=mbox:getAllFolders} 成功，{@code query_style: form} 得到 2004。中文 path、
 * 相对路径里的 {@code //}、GBK 的 query 和 form 与套接字一致。开发追踪的 {@code wire.target}
 * 与请求行里的 target 一致；口令出现在 query、path、form、JSON 和登录表单里时，追踪里是 {@code ***}。
 * 正式包不写 dev_traces，所以用本地规则包，并打开开发者模式。
 * 请求头超过 U+00FF、表示不了的 JSON、64 KiB 截断、OAuth、multipart、{@code urlencode} 二次编码、
 * 非法 {@code query_style}、ISO-8859-1 和 GB18030 仍由引擎单测覆盖，这里不重复。
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class RequestEncodingAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONCH = "legacy_gate";
    private static final String ACCOUNT = "ada";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path temp;

    private CrabProc crab;
    private String apiKey;

    @Test
    void legacyGatewayMatchesTheRawQueryAndTheTraceHidesThePassword() throws Exception {
        try (MockLegacyGateway gateway = MockLegacyGateway.open()) {
            Path home = temp.resolve("crab");
            Files.createDirectories(home);
            Files.writeString(home.resolve("config.yml"), """
                    server:
                      host: 127.0.0.1
                      port: 0
                    report:
                      enabled: false
                    """);
            crab = CrabProc.start(home);
            try {
                exercise(gateway);
            } finally {
                crab.close();
            }
        }
    }

    private void exercise(MockLegacyGateway gateway) throws Exception {
        HttpResponse<String> imported = console(
                "POST", "/console/api/local-conches", packageYaml(gateway.port()), "text/yaml");
        assertThat(imported.statusCode()).as(scrub(imported.body())).isEqualTo(200);
        assertThat(json(imported).path("data").path("conch").asText()).isEqualTo(CONCH);
        assertThat(json(imported).path("data").path("warnings")).isEmpty();
        JsonNode debug = consoleOk("POST", "/console/api/debug", "{\"enabled\":true}");
        assertThat(debug.path("data").path("enabled").asBoolean()).isTrue();
        JsonNode account = consoleOk("POST", "/console/api/conches/" + CONCH + "/accounts",
                "{\"name\":\"" + ACCOUNT + "\"}");
        assertThat(account.path("data").path("default").asBoolean()).isTrue();
        ObjectNode credentials = JSON.createObjectNode();
        credentials.put("password", MockLegacyGateway.PASSWORD);
        consoleOk("PUT", "/console/api/conches/" + CONCH + "/accounts/" + ACCOUNT + "/credentials",
                JSON.writeValueAsString(credentials));
        apiKey = createKey();

        JsonNode folders = call("folders");
        assertThat(folders.path("data").path("code").asText()).as(lines(gateway)).isEqualTo("0");
        assertThat(folders.path("data").path("name").asText()).isEqualTo("inbox");
        assertThat(gateway.hits()).as(lines(gateway)).hasSize(2);
        JsonNode foldersTrace = devTrace(folders);
        assertLogin(gateway.hit(0), requestStep(foldersTrace, 0));
        assertPlain(gateway.hit(1), requestStep(foldersTrace, 1));
        assertThat(gateway.hit(1).target()).as(lines(gateway)).isEqualTo(MockLegacyGateway.FOLDERS);
        assertThat(requestStep(foldersTrace, 1).path("query").path("func").asText()).isEqualTo("mbox:getAllFolders");
        assertThat(requestStep(foldersTrace, 1).path("wire").path("target").asText())
                .contains("func=mbox:getAllFolders")
                .contains("q=a%20b")
                .contains("plus=a%2Bb")
                .doesNotContain("mbox%3A")
                .doesNotContain("q=a+b");
        assertMasked(folders.toString());
        assertMasked(foldersTrace.toString());

        JsonNode form = call("folders_form");
        assertThat(form.path("data").path("code").asText()).as(lines(gateway)).isEqualTo("2004");
        assertThat(gateway.hits()).hasSize(3);
        JsonNode formTrace = devTrace(form);
        assertPlain(gateway.hit(2), requestStep(formTrace, 0));
        assertThat(gateway.hit(2).target()).isEqualTo(MockLegacyGateway.FOLDERS_FORM);
        assertThat(requestStep(formTrace, 0).path("query").path("func").asText()).isEqualTo("mbox:getAllFolders");
        assertThat(requestStep(formTrace, 0).path("wire").path("target").asText())
                .contains("func=mbox%3AgetAllFolders")
                .contains("q=a+b")
                .contains("plus=a%2Bb")
                .doesNotContain("mbox:getAllFolders")
                .doesNotContain("q=a%20b");
        assertMasked(formTrace.toString());

        JsonNode chinese = call("chinese");
        assertThat(chinese.path("data").path("ok").asBoolean()).as(lines(gateway)).isTrue();
        assertPlain(gateway.hit(3), requestStep(devTrace(chinese), 0));
        assertThat(gateway.hit(3).target()).as("中文路径应百分号编码，不能变成问号")
                .isEqualTo(MockLegacyGateway.CHINESE)
                .doesNotContain("?");

        JsonNode slashes = call("slashes");
        assertThat(slashes.path("data").path("ok").asBoolean()).as(lines(gateway)).isTrue();
        assertPlain(gateway.hit(4), requestStep(devTrace(slashes), 0));
        assertThat(gateway.hit(4).target()).isEqualTo(MockLegacyGateway.SLASHES);

        JsonNode gbk = call("gbk");
        assertThat(gbk.path("data").path("ok").asBoolean()).as(lines(gateway)).isTrue();
        JsonNode gbkStep = requestStep(devTrace(gbk), 0);
        assertPlain(gateway.hit(5), gbkStep);
        assertThat(gateway.hit(5).target()).isEqualTo(MockLegacyGateway.GBK_QUERY)
                .doesNotContain("%E9%82%AE");
        assertThat(new String(gateway.hit(5).body(), StandardCharsets.US_ASCII)).isEqualTo(MockLegacyGateway.GBK_FORM);
        assertThat(gbkStep.path("wire").path("body").asText()).isEqualTo(MockLegacyGateway.GBK_FORM);
        assertThat(gbkStep.path("wire").path("contentType").asText()).contains("charset=GBK");
        assertThat(gateway.hit(5).header("content-type")).contains("charset=GBK");

        JsonNode carried = call("carry");
        assertThat(carried.path("data").path("ok").asBoolean()).as(lines(gateway)).isTrue();
        assertThat(gateway.hits()).hasSize(9);
        JsonNode carryTrace = devTrace(carried);
        assertSecret(gateway.hit(6), requestStep(carryTrace, 0),
                MockLegacyGateway.CARRY, MockLegacyGateway.CARRY_FORM.getBytes(StandardCharsets.US_ASCII),
                MockLegacyGateway.REDACTED_CARRY, MockLegacyGateway.REDACTED_CARRY_FORM,
                MockLegacyGateway.REDACTED_CARRY_PATH);
        assertSecret(gateway.hit(7), requestStep(carryTrace, 1),
                MockLegacyGateway.CARRY_GBK, MockLegacyGateway.CARRY_GBK_FORM.getBytes(StandardCharsets.US_ASCII),
                MockLegacyGateway.REDACTED_CARRY_GBK, MockLegacyGateway.REDACTED_CARRY_GBK_FORM,
                MockLegacyGateway.REDACTED_CARRY_GBK_PATH);
        assertSecret(gateway.hit(8), requestStep(carryTrace, 2),
                "/echo-json", MockLegacyGateway.JSON_TEXT.getBytes(StandardCharsets.UTF_8),
                "/echo-json", MockLegacyGateway.REDACTED_JSON, "/echo-json");
        assertThat(requestStep(carryTrace, 2).path("wire").path("contentType").asText()).contains("json");
        assertMasked(carryTrace.toString());
        assertMasked(carried.toString());
        assertMasked(crab.logs());
        for (int i = 1; i < gateway.hits().size(); i++) {
            assertThat(gateway.hit(i).header("cookie")).as(lines(gateway)).contains(MockLegacyGateway.SID);
        }
        assertThat(gateway.hit(0).header("cookie")).isNull();
    }

    private void assertLogin(MockLegacyGateway.Hit hit, JsonNode step) {
        safeEquals(hit.requestLine(), "POST /login HTTP/1.1", "login line");
        safeBytes(hit.body(), MockLegacyGateway.LOGIN_FORM.getBytes(StandardCharsets.US_ASCII), "login form");
        JsonNode wire = step.path("wire");
        safeEquals(wire.path("target").asText(), "/login", "login target");
        safeEquals(wire.path("body").asText(), MockLegacyGateway.REDACTED_LOGIN, "login wire body");
        safeEquals(step.path("path").asText(), "/login", "login path");
        assertThat(wire.path("method").asText()).isEqualTo("POST");
        assertTransport(hit, wire);
        assertThat(hit.header("cookie")).isNull();
    }

    private void assertPlain(MockLegacyGateway.Hit hit, JsonNode step) {
        safeEquals(hit.requestLine(), hit.method() + " " + hit.target() + " HTTP/1.1", "request line");
        JsonNode wire = step.path("wire");
        assertThat(wire.path("method").asText()).isEqualTo(hit.method());
        assertThat(wire.path("target").asText()).isEqualTo(hit.target());
        assertThat(step.path("path").asText()).isEqualTo(pathOf(hit.target()));
        if (hit.body().length == 0) {
            assertThat(wire.path("body").isNull() || wire.path("body").isMissingNode()).isTrue();
        } else {
            assertThat(wire.path("body").asText()).isEqualTo(new String(hit.body(), StandardCharsets.US_ASCII));
        }
        assertTransport(hit, wire);
    }

    private void assertSecret(
            MockLegacyGateway.Hit hit,
            JsonNode step,
            String target,
            byte[] body,
            String redactedTarget,
            String redactedBody,
            String redactedPath
    ) {
        safeEquals(hit.requestLine(), hit.method() + " " + target + " HTTP/1.1", "secret line");
        safeEquals(hit.target(), target, "secret target");
        safeBytes(hit.body(), body, "secret body");
        JsonNode wire = step.path("wire");
        safeEquals(wire.path("target").asText(), redactedTarget, "redacted target");
        safeEquals(wire.path("body").asText(), redactedBody, "redacted body");
        safeEquals(step.path("path").asText(), redactedPath, "redacted path");
        assertThat(wire.path("method").asText()).isEqualTo(hit.method());
        assertTransport(hit, wire);
        assertMasked(wire.toString());
    }

    private void assertTransport(MockLegacyGateway.Hit hit, JsonNode wire) {
        assertThat(hit.headerCount("user-agent")).isEqualTo(1);
        assertThat(hit.headerCount("accept-encoding")).isEqualTo(1);
        assertThat(headerValue(wire.path("headers"), "User-Agent")).isEqualTo(hit.header("user-agent"));
        assertThat(headerValue(wire.path("headers"), "Accept-Encoding")).isEqualTo(hit.header("accept-encoding"));
        List<String> names = headerNames(wire.path("headers"));
        assertThat(names).map(String::toLowerCase)
                .doesNotContain("cookie", "set-cookie", "authorization", "proxy-authorization", "mcp-session-id");
        String contentType = hit.header("content-type");
        JsonNode wireType = wire.path("contentType");
        if (contentType == null) {
            assertThat(wireType.isNull() || wireType.isMissingNode()).isTrue();
        } else {
            assertThat(wireType.asText()).isEqualTo(contentType);
        }
        assertThat(wire.toString()).doesNotContain("hk_");
        assertThat(hit.requestLine()).doesNotContain("hk_");
    }

    private JsonNode call(String action) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(crab.base + "/api/" + CONCH + "/" + action))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(scrub(response.body())).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("success").asBoolean()).as(scrub(body.toString())).isTrue();
        String header = response.headers().firstValue("X-Hermit-Trace").orElse("");
        assertThat(body.path("traceId").asText()).isEqualTo(header).isNotBlank();
        return body;
    }

    private JsonNode devTrace(JsonNode call) throws Exception {
        String id = call.path("traceId").asText();
        JsonNode detail = consoleOk("GET", "/console/api/calls/" + id, null);
        JsonNode trace = detail.path("data").path("devTrace");
        assertThat(trace.isObject()).as(scrub(detail.toString())).isTrue();
        assertMasked(detail.toString());
        return trace;
    }

    private static JsonNode requestStep(JsonNode trace, int index) {
        ArrayList<JsonNode> steps = new ArrayList<>();
        for (JsonNode step : trace.path("steps")) {
            if ("request".equals(step.path("type").asText())) {
                steps.add(step);
            }
        }
        assertThat(steps.size()).isGreaterThan(index);
        return steps.get(index);
    }

    private String createKey() throws Exception {
        JsonNode created = consoleOk("POST", "/console/api/apikeys", "{\"name\":\"caller\",\"all\":true}");
        String plaintext = created.path("data").path("plaintext").asText();
        assertThat(plaintext).startsWith("hk_");
        return plaintext;
    }

    private JsonNode consoleOk(String method, String path, String body) throws Exception {
        HttpResponse<String> response = console(method, path, body, "application/json");
        assertThat(response.statusCode()).as(scrub(response.body()) + "\n" + scrub(crab.logs())).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> console(String method, String path, String body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab.base + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", contentType);
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String packageYaml(int port) {
        return """
                conch: legacy_gate
                version: 1.0.0
                driver: http
                min_crab_version: 1.5.0
                title: 老网关
                description: 按原始查询串查命令。冒号必须原样发出去。
                base_url: http://127.0.0.1:%d/app/
                credentials:
                  password:
                    type: secret
                    label: 口令
                    required: true
                    description: 登录口令
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        form:
                          password: "{{credential.password}}"
                    - assert:
                        cookie_exists: sid
                actions:
                  folders:
                    title: 文件夹
                    description: 冒号原样时命令能查到。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /api
                          query:
                            func: "mbox:getAllFolders"
                            q: "a b"
                            plus: "a+b"
                      - extract:
                          code: {jsonpath: "$.code"}
                          name: {jsonpath: "$.name"}
                    output:
                      code: {type: string, description: 返回码}
                      name: {type: string, description: 名称}
                    examples:
                      - input: {}
                        output: {code: "0", name: inbox}
                    notes: 默认 URL 编码保留冒号。
                    related: []
                  folders_form:
                    title: 旧编码
                    description: 退回表单编码时命令查不到。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /api
                          query_style: form
                          query:
                            func: "mbox:getAllFolders"
                            q: "a b"
                            plus: "a+b"
                      - extract:
                          code: {jsonpath: "$.code"}
                    output:
                      code: {type: string, description: 返回码}
                    examples:
                      - input: {}
                        output: {code: "2004"}
                    notes: query_style form 把冒号编成百分号。
                    related: []
                  chinese:
                    title: 中文路径
                    description: 中文和空格按 UTF-8 百分号编码。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: "/mail/文件夹 一"
                    output:
                      ok: {type: boolean, description: 结果, value: true}
                    examples:
                      - input: {}
                        output: {ok: true}
                    notes: 路径里的中文不再变成问号。
                    related: []
                  slashes:
                    title: 双斜杠
                    description: 相对路径保留连续斜杠。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: a//b
                    output:
                      ok: {type: boolean, description: 结果, value: true}
                    examples:
                      - input: {}
                        output: {ok: true}
                    notes: 相对路径里的双斜杠不合并。
                    related: []
                  gbk:
                    title: 国标
                    description: 查询和表单按 GBK 编码。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: POST
                          encoding: GBK
                          path: /gbk
                          query:
                            q: "邮件"
                          form:
                            title: "夹"
                    output:
                      ok: {type: boolean, description: 结果, value: true}
                    examples:
                      - input: {}
                        output: {ok: true}
                    notes: 非 ASCII 使用步骤 encoding。
                    related: []
                  carry:
                    title: 带口令
                    description: 口令出现在查询、路径、表单和 JSON 里。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: POST
                          path: "/echo/{{credential.password}}"
                          query:
                            token: "{{credential.password}}"
                          form:
                            token: "{{credential.password}}"
                      - request:
                          method: POST
                          encoding: GBK
                          path: "/gbk-echo/{{credential.password}}"
                          query:
                            q: "{{credential.password}}"
                          form:
                            title: "{{credential.password}}"
                      - request:
                          method: POST
                          path: /echo-json
                          json:
                            token: "{{credential.password}}"
                    output:
                      ok: {type: boolean, description: 结果, value: true}
                    examples:
                      - input: {}
                        output: {ok: true}
                    notes: 追踪里看不到口令，线上字节仍然带着编码后的口令。
                    related: []
                """.formatted(port);
    }

    private static String pathOf(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static String headerValue(JsonNode headers, String name) {
        if (headers == null || !headers.isObject()) {
            return null;
        }
        for (Map.Entry<String, JsonNode> entry : headers.properties()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue().asText();
            }
        }
        return null;
    }

    private static List<String> headerNames(JsonNode headers) {
        ArrayList<String> names = new ArrayList<>();
        if (headers != null && headers.isObject()) {
            headers.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static void assertMasked(String text) {
        assertThat(text).doesNotContain("hk_");
        for (String secret : secretForms()) {
            SecretChecks.absent(text, secret);
        }
    }

    private static List<String> secretForms() {
        ArrayList<String> forms = new ArrayList<>();
        forms.add(MockLegacyGateway.PASSWORD);
        forms.add(MockLegacyGateway.SID);
        forms.add(MockLegacyGateway.LOGIN_FORM.substring("password=".length()));
        forms.add(MockLegacyGateway.CARRY_FORM.substring("token=".length()));
        forms.add(MockLegacyGateway.CARRY_GBK_FORM.substring("title=".length()));
        forms.add("p%2B%E8%AF%8D:9");
        forms.add("p%2B%B4%CA:9");
        forms.add("p+%E8%AF%8D:9");
        forms.add("p+%B4%CA:9");
        forms.add("p%2b%e8%af%8d%3a9");
        forms.add("p%2b%b4%ca%3a9");
        forms.add("p%2b%e8%af%8d:9");
        forms.add("p%2b%b4%ca:9");
        forms.add("p+%e8%af%8d:9");
        forms.add("p+%b4%ca:9");
        return forms;
    }

    private String lines(MockLegacyGateway gateway) {
        return scrub(gateway.requestLines()) + "\n" + scrub(crab.logs());
    }

    private static String scrub(String text) {
        if (text == null) {
            return "";
        }
        List<String> forms = secretForms();
        forms.sort(Comparator.comparingInt(String::length).reversed());
        String out = text;
        for (String secret : forms) {
            out = out.replace(secret, "***");
        }
        return out.replaceAll("hk_[A-Za-z0-9_\\-]+", "hk_[redacted]");
    }

    private static void safeEquals(String actual, String expected, String label) {
        if (Objects.equals(expected, actual)) {
            return;
        }
        String shown = scrub(actual);
        if (Objects.equals(expected, shown)) {
            shown = "(contained a secret form, length " + (actual == null ? -1 : actual.length()) + ")";
        }
        throw new AssertionError(label + " differed, actual " + shown);
    }

    private static void safeBytes(byte[] actual, byte[] expected, String label) {
        if (Arrays.equals(actual, expected)) {
            return;
        }
        String shown = actual == null ? "null" : scrub(new String(actual, StandardCharsets.ISO_8859_1));
        throw new AssertionError(label + " differed, length " + (actual == null ? -1 : actual.length) + ", " + shown);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static final class CrabProc implements AutoCloseable {
        private final Process process;
        private final StringBuilder out;
        private final StringBuilder err;
        private final String base;
        private boolean closed;

        private CrabProc(Process process, StringBuilder out, StringBuilder err, String base) {
            this.process = process;
            this.out = out;
            this.err = err;
            this.base = base;
        }

        private static CrabProc start(Path home) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CrabMain.class.getName(),
                    "serve");
            builder.directory(home.toFile());
            builder.environment().put("HERMIT_HOME", home.toAbsolutePath().toString());
            builder.environment().remove("HERMIT_MASTER_KEY");
            builder.environment().remove("HERMIT_CONSOLE_PASSWORD");
            builder.environment().remove("HERMIT_API_KEY");
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = builder.start();
            process.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            pump(process.getInputStream(), out);
            pump(process.getErrorStream(), err);
            try {
                String line = awaitListening(process, out, err);
                return new CrabProc(process, out, err, "http://" + line.substring("listening on ".length()).trim());
            } catch (Throwable ex) {
                process.destroyForcibly();
                throw ex;
            }
        }

        private static String awaitListening(Process process, StringBuilder out, StringBuilder err)
                throws InterruptedException {
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < end) {
                synchronized (out) {
                    for (String line : out.toString().split("\n", -1)) {
                        if (line.startsWith("listening on 127.0.0.1:")) {
                            return line;
                        }
                    }
                    if (!process.isAlive()) {
                        throw new AssertionError("crab exited " + process.exitValue() + "\n" + text(out) + text(err));
                    }
                    out.wait(200);
                }
            }
            throw new AssertionError("crab did not listen\n" + text(out) + text(err));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }

        private String logs() {
            return text(out) + text(err);
        }
    }

    private static Thread pump(InputStream input, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                        sink.notifyAll();
                    }
                }
            } catch (Exception ex) {
                synchronized (sink) {
                    sink.append(ex.getClass().getName()).append('\n');
                    sink.notifyAll();
                }
            }
        }, "crab-log");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String text(StringBuilder builder) {
        synchronized (builder) {
            return builder.toString();
        }
    }
}
