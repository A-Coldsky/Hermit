package dev.hermit.crab.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.sync.LocalConchBehaviorTest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringMcpTest {
    @org.junit.jupiter.api.io.TempDir
    Path temp;

    @org.junit.jupiter.api.Test
    void developerModeShowsToolsAndGuideAndHidesThemImmediately() throws Exception {
        try (Harness server = Harness.open(temp.resolve("gate"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            JsonNode tools = server.rpc(session, 2, "tools/list", null);
            assertThat(toolNames(tools)).contains(
                    "conch_validate", "conch_save_local", "conch_trial_run", "debug_trace");
            assertThat(tool(tools, "conch_validate").path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool(tools, "conch_validate").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "conch_save_local").path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(tool(tools, "conch_save_local").path("annotations").path("destructiveHint").asBoolean()).isTrue();
            assertThat(tool(tools, "conch_trial_run").path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(tool(tools, "conch_trial_run").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "conch_trial_run").path("annotations").path("idempotentHint").asBoolean()).isFalse();

            JsonNode resources = server.rpc(session, 3, "resources/list", null);
            assertThat(resourceUris(resources)).contains(AuthoringGuide.URI);
            JsonNode read = server.rpc(session, 4, "resources/read", Map.of("uri", AuthoringGuide.URI));
            String guide = read.path("result").path("contents").get(0).path("text").asText();
            assertThat(guide).contains("从 HAR 写规则").doesNotContain("](").doesNotContain("github.com");

            server.runtime.debug().setEnabled(false, Actors.CONSOLE);
            String closed = server.initialize(false);
            JsonNode hiddenTools = server.rpc(closed, 2, "tools/list", null);
            assertThat(toolNames(hiddenTools)).doesNotContain(
                    "conch_validate", "conch_save_local", "conch_trial_run", "debug_trace");
            JsonNode hiddenResources = server.rpc(closed, 3, "resources/list", null);
            assertThat(resourceUris(hiddenResources)).doesNotContain(AuthoringGuide.URI);

            JsonNode called = server.rpc(closed, 4, "tools/call", Map.of(
                    "name", "conch_validate", "arguments", Map.of("yaml", "conch: x\n")));
            assertThat(toolMessage(called)).isEqualTo(AuthoringCalls.DEV_MODE_CLOSED);
            assertThat(toolError(called).path("code").asText()).isEqualTo("NOT_CONFIGURED");
            JsonNode trace = server.rpc(closed, 5, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", "missing")));
            assertThat(toolMessage(trace)).isEqualTo(McpErrors.UNKNOWN_TOOL);

            HttpResponse<String> denied = server.post(Map.of(
                    "jsonrpc", "2.0", "id", 6, "method", "resources/read",
                    "params", Map.of("uri", AuthoringGuide.URI)), closed, null);
            assertThat(denied.statusCode()).isEqualTo(200);
            JsonNode error = ApiJson.tree(denied.body()).path("error");
            assertThat(error.path("message").asText()).isEqualTo(AuthoringCalls.DEV_MODE_CLOSED);
            assertThat(error.path("data").path("code").asText()).isEqualTo("NOT_CONFIGURED");
        }
    }

    @org.junit.jupiter.api.Test
    void validateMatchesConsoleAndCliAndDoesNotWrite() throws Exception {
        try (Harness server = Harness.open(temp.resolve("validate"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            String missing = LocalConchBehaviorTest.ping("local_miss", "1.0.0").replace("min_crab_version: 1.0.0\n", "");
            String driver = LocalConchBehaviorTest.ping("bad_driver", "1.0.0").replace("driver: http", "driver: shell");
            assertSameIssues(server, session, missing);
            assertSameIssues(server, session, driver);
            assertThat(server.runtime.paths().localConches().toFile().list()).isNullOrEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void literalCookiePasswordAndBearerWarnLikeTheConsoleAndDoNotEchoTheValue() throws Exception {
        String secret = "supersecretcookievalue";
        String token = "abcdefghijklmnop";
        String password = "hunter2hunter2";
        String yaml = """
                conch: leak_ping
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9
                title: 泄漏
                description: 把头里的秘密写进了规则。
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 带明文头。
                    steps:
                      - request:
                          method: GET
                          path: /ping
                          headers:
                            Cookie: "sid=%s"
                            Authorization: "Bearer %s"
                            X-Password: "%s"
                    output:
                      message: {type: string, description: 文本, value: no}
                    examples:
                      - input: {}
                        output: {message: no}
                    notes: 不该保存。
                    related: []
                """.formatted(secret, token, password);
        try (Harness server = Harness.open(temp.resolve("literal"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            JsonNode validated = server.call(session, "conch_validate", Map.of("yaml", yaml));
            assertThat(validated.path("result").path("isError").asBoolean(true)).isFalse();
            JsonNode body = structured(validated);
            assertThat(body.path("ok").asBoolean()).isFalse();
            assertThat(body.toString()).doesNotContain(secret).doesNotContain(token).doesNotContain(password);
            HttpResponse<String> console = server.consoleImport(yaml);
            assertThat(console.statusCode()).isEqualTo(400);
            assertThat(issues(body.path("warnings"), true)).isEqualTo(issues(ApiJson.tree(console.body()).path("data").path("warnings"), true));
            assertThat(issues(body.path("errors"), true)).isEqualTo(issues(ApiJson.tree(console.body()).path("data").path("errors"), true));
            assertThat(codes(body.path("warnings"))).contains("LITERAL_CREDENTIAL");
            assertThat(codes(body.path("errors"))).contains("PLAINTEXT_CREDENTIAL");
            assertThat(server.runtime.paths().localConches().resolve("leak_ping.yaml")).doesNotExist();

            String warningOnly = """
                    conch: note_ping
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: http://127.0.0.1:9
                    title: 令牌
                    description: 非敏感字段名上的 Bearer。
                    credentials: {}
                    actions:
                      ping:
                        title: 探测
                        description: 返回标记。
                        steps:
                          - request:
                              method: GET
                              path: /ping
                              headers:
                                X-Note: "Bearer %s"
                        output:
                          marker: {type: string, description: 标记, value: ok}
                        examples:
                          - input: {}
                            output: {marker: ok}
                        notes: 只有警告。
                        related: []
                    """.formatted(token);
            JsonNode warned = server.call(session, "conch_validate", Map.of("yaml", warningOnly));
            JsonNode warnedBody = structured(warned);
            assertThat(warnedBody.path("ok").asBoolean()).isTrue();
            assertThat(codes(warnedBody.path("errors"))).isEmpty();
            assertThat(codes(warnedBody.path("warnings"))).containsExactly("LITERAL_CREDENTIAL");
            assertThat(warnedBody.toString()).doesNotContain(token);
        }
    }

    @org.junit.jupiter.api.Test
    void saveRequiresUserConfirmationAndRefusesOfficialPackages() throws Exception {
        try (Harness server = Harness.open(temp.resolve("save"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String yaml = LocalConchBehaviorTest.ping("saved_ping", "1.0.0");
            String session = server.initialize(false);
            JsonNode saved = server.call(session, "conch_save_local", Map.of("yaml", yaml));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(structured(saved).path("replaced").asBoolean()).isFalse();
            assertThat(structured(saved).path("next").asText()).contains("不要把口令、Cookie 或令牌发给 AI");
            Path file = server.runtime.paths().localConches().resolve("saved_ping.yaml");
            assertThat(Files.readString(file)).isEqualTo(yaml);

            String next = yaml.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode refused = server.call(session, "conch_save_local", Map.of("yaml", next));
            assertThat(toolMessage(refused)).isEqualTo(AuthoringCalls.NO_OVERWRITE);
            assertThat(Files.readString(file)).isEqualTo(yaml);

            JsonNode param = server.call(session, "conch_save_local", Map.of("yaml", next, "confirm", true));
            assertThat(toolMessage(param)).isEqualTo(AuthoringCalls.CONFIRM_PARAM);
            assertThat(Files.readString(file)).isEqualTo(yaml);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> declined = new AtomicReference<>();
                Thread decline = new Thread(() -> declined.set(
                        server.callUnchecked(confirming, "conch_save_local", Map.of("yaml", next))));
                decline.start();
                JsonNode question = sse.awaitMethod("elicitation/create");
                assertThat(question.path("params").path("message").asText()).contains("saved_ping").doesNotContain("1.0.1");
                server.answer(confirming, question, "decline", null);
                decline.join(15_000);
                assertThat(toolMessage(declined.get())).isEqualTo(AuthoringCalls.OVERWRITE_DECLINED);
                assertThat(Files.readString(file)).isEqualTo(yaml);

                AtomicReference<JsonNode> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(
                        server.callUnchecked(confirming, "conch_save_local", Map.of("yaml", next))));
                accept.start();
                JsonNode again = sse.awaitMethod("elicitation/create");
                server.answer(confirming, again, "accept", true);
                accept.join(15_000);
                assertThat(structured(accepted.get()).path("ok").asBoolean()).as(String.valueOf(accepted.get())).isTrue();
                assertThat(structured(accepted.get()).path("replaced").asBoolean()).isTrue();
                assertThat(Files.readString(file)).contains("version: 1.0.1");
            }

            server.runtime.registry().put(ConchParser.parse(LocalConchBehaviorTest.ping("official_ping", "1.0.0")));
            String official = LocalConchBehaviorTest.ping("official_ping", "1.0.0");
            JsonNode blocked = server.call(session, "conch_save_local", Map.of("yaml", official));
            assertThat(toolMessage(blocked)).isEqualTo(AuthoringCalls.OFFICIAL);
            assertThat(server.runtime.paths().localConches().resolve("official_ping.yaml")).doesNotExist();
            JsonNode trial = server.call(session, "conch_trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(trial)).isEqualTo(AuthoringCalls.OFFICIAL);
        }
    }

    @org.junit.jupiter.api.Test
    void trialRunUsesALocalUpstreamAndKeepsTheUntrustedNote() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/ping", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try (Harness server = Harness.open(temp.resolve("trial"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            String yaml = """
                    conch: trial_ping
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: %s
                    title: 试跑
                    description: 打本地假上游。
                    credentials: {}
                    actions:
                      ping:
                        title: 探测
                        description: 读取状态。
                        idempotent: true
                        mcp: {expose: true, read_only: true}
                        steps:
                          - request: {method: GET, path: /ping}
                          - extract:
                              message: {jsonpath: $.message}
                        output:
                          message: {type: string, description: 文本}
                        examples:
                          - input: {}
                            output: {message: ok}
                        notes: 本地假上游。
                        related: []
                    """.formatted(base);
            String session = server.initialize(false);
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            JsonNode missing = server.call(session, "conch_trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            assertThat(toolMessage(missing)).isEqualTo(
                    "还没有账号。请到控制台为本地包 trial_ping 新建账号并填写凭证。不要把口令、Cookie 或令牌发给 AI。");
            assertThat(hits).hasValue(0);

            server.runtime.admin().createAccount("trial_ping", "main", Actors.CONSOLE);
            JsonNode ran = server.call(session, "conch_trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            JsonNode body = structured(ran);
            assertThat(ran.path("result").path("isError").asBoolean(true)).as(ran.toString()).isFalse();
            assertThat(body.path("data").path("message").asText()).isEqualTo("ok");
            assertThat(body.path("note").asText()).isEqualTo(AuthoringCalls.UNTRUSTED);
            assertThat(body.path("untrusted").asBoolean()).isTrue();
            String traceId = body.path("traceId").asText();
            assertThat(traceId).isNotBlank();
            assertThat(hits).hasValue(1);

            CreatedApiKey named = server.runtime.apiKeys().create(
                    "named", new ApiKeyScope(Set.of("trial_ping"), Set.of(), false), Actors.CONSOLE);
            String namedSession = server.initialize(named.plaintext(), false);
            JsonNode seen = server.rpc(named.plaintext(), namedSession, 2, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(seen.path("result").path("isError").asBoolean(true)).isFalse();
            JsonNode trace = ApiJson.tree(seen.path("result").path("content").get(0).path("text").asText());
            assertThat(trace.path("note").asText()).contains("正文已脱敏").isNotEqualTo(AuthoringCalls.UNTRUSTED);

            CreatedApiKey all = server.runtime.apiKeys().create(
                    "all", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            String allSession = server.initialize(all.plaintext(), false);
            JsonNode hidden = server.rpc(all.plaintext(), allSession, 2, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(toolMessage(hidden)).isEqualTo(McpErrors.UNKNOWN_TOOL);
        } finally {
            upstream.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void authoringToolsStayInDirectoryModeAndKeepTheExistingAccessChecks() throws Exception {
        try (Harness server = Harness.open(temp.resolve("access"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            StringBuilder yaml = new StringBuilder("""
                    conch: bulk_local
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: http://127.0.0.1:9
                    title: 很多能力
                    description: 用来进入目录模式。
                    credentials: {}
                    actions:
                    """);
            for (int i = 0; i < 41; i++) {
                yaml.append("""
                          act_%02d:
                            title: 能力 %d
                            description: 返回常量。
                            idempotent: true
                            mcp: {expose: true, read_only: true}
                            steps:
                              - set: {message: ready}
                            output:
                              message: {type: string, description: 文本}
                            examples:
                              - input: {}
                                output: {message: ready}
                            notes: 无
                            related: []
                        """.formatted(i, i));
            }
            String session = server.initialize(false);
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml.toString()))).path("ok").asBoolean())
                    .isTrue();
            JsonNode tools = server.rpc(session, 8, "tools/list", null);
            assertThat(toolNames(tools)).contains(
                    "list_systems", "conch_validate", "conch_save_local", "conch_trial_run", "debug_trace");
            assertThat(toolNames(tools)).doesNotContain("bulk_local__act_00");

            HttpResponse<String> badKey = server.postRaw("not-a-real-key", null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                    """, Map.of());
            assertThat(badKey.statusCode()).isEqualTo(401);
            assertThat(ApiJson.tree(badKey.body()).path("error").path("data").path("code").asText()).isEqualTo(McpErrors.AUTH_INVALID);

            HttpResponse<String> evil = server.postRaw(server.key.plaintext(), null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                    """, Map.of("Origin", "http://evil.example"));
            assertThat(evil.statusCode()).isEqualTo(403);
            assertThat(ApiJson.tree(evil.body()).path("error").path("data").path("code").asText()).isEqualTo(McpErrors.ORIGIN_REJECTED);
        }
    }

    private static void assertSameIssues(Harness server, String session, String yaml) throws Exception {
        JsonNode validated = server.call(session, "conch_validate", Map.of("yaml", yaml));
        assertThat(validated.path("result").path("isError").asBoolean(true)).isFalse();
        JsonNode body = structured(validated);
        assertThat(body.path("ok").asBoolean()).isFalse();
        HttpResponse<String> console = server.consoleImport(yaml);
        assertThat(console.statusCode()).isEqualTo(400);
        JsonNode consoleErrors = ApiJson.tree(console.body()).path("data").path("errors");
        assertThat(issues(body.path("errors"), true)).isEqualTo(issues(consoleErrors, true));
        assertThat(cliIssues(yaml)).isEqualTo(issues(body.path("errors"), false));
        assertThat(server.runtime.registry().find(body.path("conch").asText(""))).isEmpty();
    }

    private static List<Issue> cliIssues(String yaml) throws Exception {
        Path home = Files.createTempDirectory("hermit-authoring-cli");
        Path file = home.resolve("in.yaml");
        Files.writeString(file, yaml);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"local", "import", file.toString()},
                name -> "HERMIT_HOME".equals(name) ? home.toString() : null,
                InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        ArrayList<Issue> found = new ArrayList<>();
        for (String line : err.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.equals("规则包校验失败")) {
                continue;
            }
            int codeEnd = line.indexOf(' ');
            int pathEnd = line.indexOf(' ', codeEnd + 1);
            found.add(new Issue(line.substring(0, codeEnd), line.substring(codeEnd + 1, pathEnd), line.substring(pathEnd + 1), null));
        }
        return found;
    }

    private static JsonNode structured(JsonNode response) {
        return response.path("result").path("structuredContent");
    }

    private static JsonNode toolError(JsonNode response) {
        return structured(response).path("error");
    }

    private static String toolMessage(JsonNode response) {
        JsonNode content = response.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            return ApiJson.tree(content.get(0).path("text").asText()).path("message").asText();
        }
        return toolError(response).path("message").asText();
    }

    private static List<String> toolNames(JsonNode listed) {
        ArrayList<String> names = new ArrayList<>();
        for (JsonNode tool : listed.path("result").path("tools")) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static JsonNode tool(JsonNode listed, String name) {
        for (JsonNode tool : listed.path("result").path("tools")) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("missing tool " + name);
    }

    private static List<String> resourceUris(JsonNode listed) {
        ArrayList<String> uris = new ArrayList<>();
        for (JsonNode resource : listed.path("result").path("resources")) {
            uris.add(resource.path("uri").asText());
        }
        return uris;
    }

    private static List<String> codes(JsonNode array) {
        ArrayList<String> codes = new ArrayList<>();
        for (JsonNode issue : array) {
            codes.add(issue.path("code").asText());
        }
        return codes;
    }

    private static List<Issue> issues(JsonNode array, boolean line) {
        ArrayList<Issue> found = new ArrayList<>();
        for (JsonNode issue : array) {
            Integer row = line && issue.has("line") ? issue.path("line").asInt() : null;
            found.add(new Issue(issue.path("code").asText(), issue.path("path").asText(), issue.path("message").asText(), row));
        }
        return found;
    }

    private record Issue(String code, String path, String message, Integer line) {
    }

    private static final class Harness implements AutoCloseable {
        final CrabRuntime runtime;
        final CrabHttpServer httpServer;
        final CreatedApiKey key;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final String base;

        private Harness(CrabRuntime runtime, CrabHttpServer httpServer, CreatedApiKey key) {
            this.runtime = runtime;
            this.httpServer = httpServer;
            this.key = key;
            this.base = "http://127.0.0.1:" + httpServer.port();
        }

        static Harness open(Path home) throws Exception {
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), CrabConfig.defaults().withListen("127.0.0.1", 0));
            CrabHttpServer server = new CrabHttpServer(runtime);
            server.start();
            CreatedApiKey key = runtime.apiKeys().create("authoring", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            return new Harness(runtime, server, key);
        }

        String initialize(boolean elicitation) throws Exception {
            return initialize(key.plaintext(), elicitation);
        }

        String initialize(String apiKey, boolean elicitation) throws Exception {
            String capabilities = elicitation ? "{\"elicitation\":{}}" : "{}";
            HttpResponse<String> response = postRaw(apiKey, null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":%s,"clientInfo":{"name":"test","version":"0"}}}
                    """.formatted(capabilities), Map.of());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        }

        JsonNode call(String session, String name, Map<String, Object> arguments) throws Exception {
            JsonNode response = rpc(session, 7, "tools/call", Map.of("name", name, "arguments", arguments));
            assertThat(response.has("result")).as(response.toString()).isTrue();
            return response;
        }

        JsonNode callUnchecked(String session, String name, Map<String, Object> arguments) {
            try {
                return call(session, name, arguments);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        JsonNode rpc(String session, int id, String method, Object params) throws Exception {
            return rpc(key.plaintext(), session, id, method, params);
        }

        JsonNode rpc(String apiKey, String session, int id, String method, Object params) throws Exception {
            ObjectNode body = new ObjectNode(id, method, params);
            HttpResponse<String> response = postRaw(apiKey, session, body.json(), Map.of());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return ApiJson.tree(response.body());
        }

        HttpResponse<String> post(Map<String, Object> body, String session, String origin) throws Exception {
            return postRaw(key.plaintext(), session, ApiJson.write(body), origin == null ? Map.of() : Map.of("Origin", origin));
        }

        HttpResponse<String> postRaw(String apiKey, String session, String body, Map<String, String> extra) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (session != null) {
                request.header("Mcp-Session-Id", session);
            }
            extra.forEach(request::header);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        void answer(String session, JsonNode question, String action, Boolean confirm) throws Exception {
            String content = confirm == null ? "" : ",\"content\":{\"confirm\":" + confirm + "}";
            HttpResponse<String> response = postRaw(key.plaintext(), session, """
                    {"jsonrpc":"2.0","id":"%s","result":{"action":"%s"%s}}
                    """.formatted(question.get("id").asText(), action, content), Map.of());
            assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
        }

        HttpResponse<String> consoleImport(String yaml) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/console/api/local-conches"))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "text/yaml")
                    .POST(HttpRequest.BodyPublishers.ofString(yaml))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            httpServer.close();
            runtime.close();
        }
    }

    private record ObjectNode(int id, String method, Object params) {
        String json() {
            if (params == null) {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + ApiJson.write(params) + "}";
        }
    }

    private static final class Sse implements AutoCloseable {
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final InputStream body;
        private Thread reader;

        private Sse(InputStream body) {
            this.body = body;
        }

        static Sse open(Harness server, String session) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.base + "/mcp"))
                    .header("Authorization", "Bearer " + server.key.plaintext())
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", session)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = server.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new AssertionError("sse status " + response.statusCode() + " " + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            Sse sse = new Sse(response.body());
            sse.reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(sse.body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        sse.lines.add(line);
                    }
                } catch (Exception ex) {
                    sse.lines.add("");
                }
            }, "authoring-sse");
            sse.reader.setDaemon(true);
            sse.reader.start();
            return sse;
        }

        void awaitPrefix(String prefix) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(200, TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith(prefix)) {
                    return;
                }
            }
            throw new AssertionError("missing sse prefix");
        }

        JsonNode awaitMethod(String method) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(200, TimeUnit.MILLISECONDS);
                if (line == null || !line.startsWith("data:")) {
                    continue;
                }
                JsonNode node = ApiJson.tree(line.substring(5).trim());
                if (method.equals(node.path("method").asText())) {
                    return node;
                }
            }
            throw new AssertionError("missing " + method);
        }

        @Override
        public void close() throws Exception {
            body.close();
            if (reader != null) {
                reader.join(2_000);
            }
        }
    }
}
