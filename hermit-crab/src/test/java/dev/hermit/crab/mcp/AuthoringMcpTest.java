package dev.hermit.crab.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Enumeration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import dev.hermit.crab.upstream.McpOAuthToken;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringMcpTest {
    @org.junit.jupiter.api.io.TempDir
    Path temp;

    @org.junit.jupiter.api.Test
    void developerModeShowsToolsAndGuideAndHidesThemImmediately() throws Exception {
        try (Harness server = Harness.open(temp.resolve("gate"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            HttpResponse<String> openedResponse = server.postRaw(server.key.plaintext(), null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                    """, Map.of());
            assertThat(openedResponse.statusCode()).isEqualTo(200);
            JsonNode openedInit = ApiJson.tree(openedResponse.body()).path("result");
            assertThat(openedInit.path("instructions").asText())
                    .startsWith(McpCatalog.INSTRUCTIONS)
                    .contains("hermit_dev__guide")
                    .contains("hermit_dev__read_yaml")
                    .contains("hermit://conches/hermit_dev/guide")
                    .contains("Reef 订阅的正式包只读");
            String session = openedResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
            JsonNode flat = server.rpc(session, 2, "tools/list", null);
            // 开发者工具不平铺：tools/list 里只有一个入口 hermit_dev，清单由它返回。
            assertThat(toolNames(flat)).contains("hermit_dev").noneMatch(name -> name.startsWith("hermit_dev__"));
            assertThat(tool(flat, "hermit_dev").path("description").asText()).contains("只在用户要求编写、修改或调试规则包");
            JsonNode tools = DevToolList.expand(flat, server.devOverview(server.key.plaintext(), session));
            JsonNode viaEntry = structured(server.call(session, "hermit_dev",
                    Map.of("tool", "hermit_dev__validate", "arguments", Map.of("yaml", "conch: x"))));
            assertThat(viaEntry.has("ok")).isTrue();
            assertThat(toolError(server.rpc(session, 21, "tools/call",
                    Map.of("name", "hermit_dev", "arguments", Map.of("tool", "oa__list")))).path("code").asText())
                    .isEqualTo("INVALID_INPUT");
            assertThat(toolNames(tools)).contains(
                    "hermit_dev__guide", "hermit_dev__read_yaml", "hermit_dev__validate", "hermit_dev__save_local", "hermit_dev__trial_run", "hermit_dev__debug_trace");
            assertThat(tool(tools, "hermit_dev__guide").path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool(tools, "hermit_dev__guide").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "hermit_dev__read_yaml").path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool(tools, "hermit_dev__read_yaml").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "hermit_dev__read_yaml").path("description").asText()).contains("Reef 订阅的正式包只读");
            assertThat(tool(tools, "hermit_dev__validate").path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool(tools, "hermit_dev__validate").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "hermit_dev__validate").path("description").asText())
                    .contains("先和用户确认要做哪些功能")
                    .contains("hermit_dev__guide")
                    .contains("hermit://conches/hermit_dev/guide");
            assertThat(tool(tools, "hermit_dev__save_local").path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(tool(tools, "hermit_dev__save_local").path("annotations").path("destructiveHint").asBoolean()).isTrue();
            assertThat(tool(tools, "hermit_dev__save_local").path("description").asText())
                    .contains("hermit_dev__read_yaml")
                    .contains("绝不修改 Reef 订阅的正式规则包");
            assertThat(tool(tools, "hermit_dev__trial_run").path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(tool(tools, "hermit_dev__trial_run").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "hermit_dev__trial_run").path("annotations").path("idempotentHint").asBoolean()).isFalse();

            JsonNode resources = server.rpc(session, 3, "resources/list", null);
            assertThat(resourceUris(resources)).contains(AuthoringGuide.URI);
            JsonNode read = server.rpc(session, 4, "resources/read", Map.of("uri", AuthoringGuide.URI));
            String guide = read.path("result").path("contents").get(0).path("text").asText();
            assertThat(guide).contains("从 HAR 写规则").doesNotContain("](").doesNotContain("github.com");

            server.runtime.debug().setEnabled(false, Actors.CONSOLE);
            HttpResponse<String> closedResponse = server.postRaw(server.key.plaintext(), null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                    """, Map.of());
            assertThat(closedResponse.statusCode()).isEqualTo(200);
            assertThat(ApiJson.tree(closedResponse.body()).path("result").path("instructions").asText())
                    .isEqualTo(McpCatalog.INSTRUCTIONS);
            String closed = closedResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
            JsonNode hiddenTools = server.rpc(closed, 2, "tools/list", null);
            assertThat(toolNames(hiddenTools)).doesNotContain(
                    "hermit_dev__guide", "hermit_dev__read_yaml", "hermit_dev__validate", "hermit_dev__save_local", "hermit_dev__trial_run", "hermit_dev__debug_trace");
            JsonNode hiddenResources = server.rpc(closed, 3, "resources/list", null);
            assertThat(resourceUris(hiddenResources)).doesNotContain(AuthoringGuide.URI);

            for (Map<String, Object> call : List.of(
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of()),
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "demo")),
                    Map.of("name", "hermit_dev__validate", "arguments", Map.of("yaml", "conch: x\n")))) {
                JsonNode called = server.rpc(closed, 4, "tools/call", call);
                assertThat(toolMessage(called)).as(String.valueOf(call.get("name"))).isEqualTo(AuthoringCalls.DEV_MODE_CLOSED);
                assertThat(toolError(called).path("code").asText()).isEqualTo("NOT_CONFIGURED");
            }
            JsonNode trace = server.rpc(closed, 5, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", "missing")));
            assertThat(toolMessage(trace)).isEqualTo(AuthoringCalls.DEV_MODE_CLOSED).doesNotContain("没有这个工具");

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
    void saveAutoAppliesOnlyTheGrantedConchAndRejectsGrantArguments() throws Exception {
        try (Harness server = Harness.open(temp.resolve("grant"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            String yaml = LocalConchBehaviorTest.ping("saved_ping", "1.0.0");
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            String widened = yaml.replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode refused = server.call(session, "hermit_dev__save_local", Map.of("yaml", widened));
            assertThat(toolError(refused).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");

            dev.hermit.crab.sync.LocalConches.PendingOverwrite pending =
                    server.runtime.localConches().pending().get(0);
            server.runtime.localConches().confirmPending("saved_ping", pending.sha256(), Actors.CONSOLE, true);
            assertThat(server.runtime.localConches().grants())
                    .extracting(dev.hermit.crab.sync.LocalConches.OverrideGrant::conch)
                    .containsExactly("saved_ping");

            String again = widened.replace("version: 1.0.0", "version: 1.1.0")
                    .replace("base_url: http://127.0.0.1:10/", "base_url: http://127.0.0.1:11/");
            JsonNode saved = server.call(session, "hermit_dev__save_local", Map.of("yaml", again));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(structured(saved).path("replaced").asBoolean()).isTrue();
            assertThat(server.runtime.localConches().pending()).isEmpty();
            Path file = server.runtime.paths().localConches().resolve("saved_ping.yaml");
            assertThat(Files.readString(file)).contains("127.0.0.1:11");

            String other = LocalConchBehaviorTest.ping("other_ping", "1.0.0");
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", other))).path("ok").asBoolean()).isTrue();
            String otherWide = other.replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:12/");
            JsonNode still = server.call(session, "hermit_dev__save_local", Map.of("yaml", otherWide));
            assertThat(toolError(still).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");
            assertThat(Files.readString(server.runtime.paths().localConches().resolve("other_ping.yaml"))).isEqualTo(other);

            String blocked = again.replace("version: 1.1.0", "version: 1.2.0")
                    .replace("base_url: http://127.0.0.1:11/", "base_url: http://127.0.0.1:13/");
            for (String field : java.util.List.of("remember", "autoApprove", "auto_approve", "skipConfirm", "grant")) {
                JsonNode denied = server.call(session, "hermit_dev__save_local", Map.of("yaml", blocked, field, true));
                assertThat(toolError(denied).path("code").asText()).as(field).isEqualTo("FORBIDDEN");
                assertThat(toolMessage(denied)).isEqualTo(AuthoringCalls.GRANT_PARAM);
            }
            assertThat(Files.readString(file)).contains("127.0.0.1:11").doesNotContain("127.0.0.1:13");
            assertThat(server.runtime.localConches().grants())
                    .extracting(dev.hermit.crab.sync.LocalConches.OverrideGrant::conch)
                    .containsExactly("saved_ping");
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
            JsonNode validated = server.call(session, "hermit_dev__validate", Map.of("yaml", yaml));
            assertThat(validated.path("result").path("isError").asBoolean(true)).isFalse();
            JsonNode body = structured(validated);
            assertThat(body.path("ok").asBoolean()).isFalse();
            assertThat(body.toString()).doesNotContain(secret).doesNotContain(token).doesNotContain(password);
            HttpResponse<String> console = server.consoleImport(yaml);
            assertThat(console.statusCode()).isEqualTo(400);
            assertThat(issues(body.path("warnings"), true)).isEqualTo(issues(ApiJson.tree(console.body()).path("data").path("warnings"), true));
            assertThat(issues(body.path("errors"), true)).isEqualTo(issues(ApiJson.tree(console.body()).path("data").path("errors"), true));
            assertThat(codes(body.path("errors"))).containsOnly("PLAINTEXT_CREDENTIAL");
            assertThat(body.path("errors")).allSatisfy(issue -> assertThat(issue.path("line").isNumber()).isTrue());
            assertThat(codes(body.path("warnings"))).doesNotContain("LITERAL_CREDENTIAL");
            assertThat(issues(body.path("errors"), false)).extracting(Issue::path).containsExactlyInAnyOrder(
                    "actions.ping.steps[0].request.headers.Cookie",
                    "actions.ping.steps[0].request.headers.Authorization",
                    "actions.ping.steps[0].request.headers.X-Password");
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
            JsonNode warned = server.call(session, "hermit_dev__validate", Map.of("yaml", warningOnly));
            JsonNode warnedBody = structured(warned);
            assertThat(warnedBody.path("ok").asBoolean()).isFalse();
            assertThat(codes(warnedBody.path("errors"))).containsExactly("PLAINTEXT_CREDENTIAL");
            assertThat(codes(warnedBody.path("warnings"))).doesNotContain("LITERAL_CREDENTIAL");
            assertThat(warnedBody.path("errors").get(0).path("line").isNumber()).isTrue();
            assertThat(warnedBody.path("errors").get(0).path("path").asText())
                    .isEqualTo("actions.ping.steps[0].request.headers.X-Note");
            assertThat(warnedBody.toString()).doesNotContain(token);
            JsonNode blocked = server.call(session, "hermit_dev__save_local", Map.of("yaml", warningOnly));
            assertThat(structured(blocked).path("ok").asBoolean()).isFalse();
            assertThat(server.runtime.paths().localConches().resolve("note_ping.yaml")).doesNotExist();
        }
    }

    @org.junit.jupiter.api.Test
    void saveRequiresUserConfirmationAndRefusesOfficialPackages() throws Exception {
        try (Harness server = Harness.open(temp.resolve("save"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String yaml = LocalConchBehaviorTest.ping("saved_ping", "1.0.0");
            String session = server.initialize(false);
            JsonNode saved = server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(structured(saved).path("replaced").asBoolean()).isFalse();
            assertThat(structured(saved).path("next").asText())
                    .contains("不要把口令、Cookie 或令牌发给 AI")
                    .contains("新建账号");
            Path file = server.runtime.paths().localConches().resolve("saved_ping.yaml");
            assertThat(Files.readString(file)).isEqualTo(yaml);

            String next = yaml.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode silent = server.call(session, "hermit_dev__save_local", Map.of("yaml", next));
            assertThat(structured(silent).path("ok").asBoolean()).as(silent.toString()).isTrue();
            assertThat(structured(silent).path("replaced").asBoolean()).isTrue();
            assertThat(structured(silent).path("previousVersion").asText()).isEqualTo("1.0.0");
            assertThat(structured(silent).path("backupId").asText()).isNotBlank();
            assertThat(Files.readString(file)).isEqualTo(next);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            String widened = next.replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode refused = server.call(session, "hermit_dev__save_local", Map.of("yaml", widened));
            assertThat(toolError(refused).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");
            assertThat(toolMessage(refused)).isEqualTo(AuthoringCalls.confirmRequiredMessage(
                    dev.hermit.common.validate.ConchDestinations.between(
                            dev.hermit.common.parse.ConchParser.parse(next),
                            dev.hermit.common.parse.ConchParser.parse(widened))));
            assertThat(toolError(refused).path("hint").asText()).isEqualTo(AuthoringCalls.SAME_NAME);
            assertThat(Files.readString(file)).isEqualTo(next);
            assertThat(server.runtime.localConches().pending()).hasSize(1);

            JsonNode param = server.call(session, "hermit_dev__save_local", Map.of("yaml", widened, "confirm", true));
            assertThat(toolMessage(param)).isEqualTo(AuthoringCalls.CONFIRM_PARAM);
            assertThat(Files.readString(file)).isEqualTo(next);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> declined = new AtomicReference<>();
                Thread decline = new Thread(() -> declined.set(
                        server.callUnchecked(confirming, "hermit_dev__save_local", Map.of("yaml", widened))));
                decline.start();
                JsonNode question = sse.awaitMethod("elicitation/create");
                assertThat(question.path("params").path("message").asText())
                        .contains("saved_ping")
                        .contains("http://127.0.0.1:10")
                        .doesNotContain("version: 1.0.1");
                server.answer(confirming, question, "decline", null);
                decline.join(15_000);
                assertThat(toolMessage(declined.get())).isEqualTo(AuthoringCalls.OVERWRITE_DECLINED);
                assertThat(Files.readString(file)).isEqualTo(next);

                AtomicReference<JsonNode> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(
                        server.callUnchecked(confirming, "hermit_dev__save_local", Map.of("yaml", widened))));
                accept.start();
                JsonNode again = sse.awaitMethod("elicitation/create");
                server.answer(confirming, again, "accept", true);
                accept.join(15_000);
                assertThat(structured(accepted.get()).path("ok").asBoolean()).as(String.valueOf(accepted.get())).isTrue();
                assertThat(structured(accepted.get()).path("replaced").asBoolean()).isTrue();
                assertThat(Files.readString(file)).contains("127.0.0.1:10");

                server.runtime.admin().createAccount("saved_ping", "main", Actors.CONSOLE);
                String later = widened.replace("version: 1.0.1", "version: 1.0.2");
                JsonNode checked = server.call(confirming, "hermit_dev__save_local", Map.of("yaml", later));
                assertThat(structured(checked).path("next").asText())
                        .doesNotContain("新建账号")
                        .contains("可以直接试跑")
                        .doesNotContain("测试登录");

                String changed = later.replace("version: 1.0.2", "version: 1.0.3")
                        .replace("credentials: {}", """
                                credentials:
                                  password: { type: secret, required: true, label: 密码 }""");
                JsonNode relogin = server.call(confirming, "hermit_dev__save_local", Map.of("yaml", changed));
                assertThat(structured(relogin).path("next").asText())
                        .contains("测试登录")
                        .contains("凭证字段有变化")
                        .doesNotContain("可以直接试跑")
                        .doesNotContain("新建账号");
            }

            server.runtime.registry().put(ConchParser.parse(LocalConchBehaviorTest.ping("official_ping", "1.0.0")));
            String official = LocalConchBehaviorTest.ping("official_ping", "1.0.0");
            // 没有本地那份时不能试跑正式包。
            JsonNode trial = server.call(session, "hermit_dev__trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(trial)).isEqualTo(AuthoringCalls.OFFICIAL);
            JsonNode blocked = server.call(session, "hermit_dev__save_local", Map.of("yaml", official));
            // 和正式包同名可以保存；本地这份不生效，试跑要先切来源。
            assertThat(blocked.path("result").path("isError").asBoolean()).as(blocked.toString()).isFalse();
            assertThat(server.runtime.paths().localConches().resolve("official_ping.yaml")).exists();
            assertThat(server.runtime.registry().origin("official_ping")).isEqualTo(dev.hermit.crab.store.Origin.OFFICIAL);
            JsonNode inactive = server.call(session, "hermit_dev__trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(inactive)).isEqualTo(AuthoringCalls.LOCAL_INACTIVE);
            assertThat(server.runtime.registry().activate("official_ping", dev.hermit.crab.store.Origin.LOCAL)).isTrue();
            JsonNode ran = server.call(session, "hermit_dev__trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(ran)).isNotEqualTo(AuthoringCalls.LOCAL_INACTIVE);
        }
    }

    @org.junit.jupiter.api.Test
    void overwriteNamesTheNewHostAndDropsTheSavedUpstreamToken() throws Exception {
        try (Harness server = Harness.open(temp.resolve("hosts"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            JsonNode saved = server.call(session, "hermit_dev__save_local",
                    Map.of("yaml", hostYaml("1.0.0", "https://old.example/mcp")));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            server.runtime.admin().createAccount("host_mcp", "ada", Actors.CONSOLE);
            insertToken(server.runtime, "host_mcp", "ada");

            String same = hostYaml("1.0.1", "https://old.example:443/mcp/v2");
            String moved = hostYaml("1.0.2", "https://new.example/mcp");
            JsonNode kept = server.call(session, "hermit_dev__save_local", Map.of("yaml", same));
            assertThat(structured(kept).path("ok").asBoolean()).as(kept.toString()).isTrue();
            assertThat(structured(kept).path("replaced").asBoolean()).isTrue();
            assertThat(server.runtime.admin().credentialNames("host_mcp", "ada")).contains(McpOAuthToken.FIELD);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> replaced = new AtomicReference<>();
                Thread second = new Thread(() -> replaced.set(
                        server.callUnchecked(confirming, "hermit_dev__save_local", Map.of("yaml", moved))));
                second.start();
                JsonNode again = sse.awaitMethod("elicitation/create");
                assertThat(again.path("params").path("message").asText())
                        .isEqualTo("将覆盖已有的本地规则包 host_mcp。新增地址：https://new.example:443。"
                                + "上游主机将从 old.example 改为 new.example，"
                                + "确认后会清掉已保存的上游令牌。确认后才会写入。");
                server.answer(confirming, again, "accept", true);
                second.join(15_000);
                assertThat(structured(replaced.get()).path("ok").asBoolean()).as(String.valueOf(replaced.get())).isTrue();
                assertThat(server.runtime.admin().credentialNames("host_mcp", "ada"))
                        .doesNotContain(McpOAuthToken.FIELD);
            }
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
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            JsonNode missing = server.call(session, "hermit_dev__trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            assertThat(toolMessage(missing)).isEqualTo(
                    "还没有账号。请到控制台为本地规则包 trial_ping 新建账号并填写凭证。不要把口令、Cookie 或令牌发给 AI。");
            assertThat(hits).hasValue(0);

            server.runtime.admin().createAccount("trial_ping", "main", Actors.CONSOLE);
            // 整包停用时试跑报整包停用，不让人去启用单个能力。
            server.runtime.setConchDisabled("trial_ping", true, Actors.CONSOLE);
            JsonNode off = server.call(session, "hermit_dev__trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            assertThat(toolError(off).path("code").asText()).isEqualTo("CONCH_DISABLED");
            assertThat(toolMessage(off)).isEqualTo(dev.hermit.crab.runtime.Invoker.CONCH_SWITCHED_OFF_CALL);
            assertThat(hits).hasValue(0);
            server.runtime.setConchDisabled("trial_ping", false, Actors.CONSOLE);
            JsonNode ran = server.call(session, "hermit_dev__trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            JsonNode body = structured(ran);
            assertThat(ran.path("result").path("isError").asBoolean(true)).as(ran.toString()).isFalse();
            assertThat(body.path("data").path("message").asText()).isEqualTo("ok");
            assertThat(body.path("note").asText()).isEqualTo(AuthoringCalls.UNTRUSTED);
            assertThat(body.path("untrusted").asBoolean()).isTrue();
            String traceId = body.path("traceId").asText();
            assertThat(traceId).isNotBlank();
            assertThat(hits).hasValue(1);

            // 另一把点名了该本地包的 Key 也不能通过 hermit_dev__debug_trace 读取别把 Key 的 hermit_dev__trial_run 追踪。
            CreatedApiKey named = server.runtime.apiKeys().create(
                    "named", new ApiKeyScope(Set.of("trial_ping"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            String namedSession = server.initialize(named.plaintext(), false);
            JsonNode crossKeyDenied = server.rpc(named.plaintext(), namedSession, 2, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(toolError(crossKeyDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(crossKeyDenied)).isEqualTo(McpCalls.TRACE_DENIED);

            // 真控制台试跑（apiKeyId == null）产生的追踪仍可被点名了该本地包的 Key 读取。
            dev.hermit.crab.engine.ExecutionResult consoleRan = server.runtime.trialLocal(
                    "trial_ping", "ping", java.util.Optional.of("main"), Map.of(), "console-trial-ping");
            assertThat(consoleRan.success()).isTrue();
            JsonNode seen = server.rpc(named.plaintext(), namedSession, 3, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", consoleRan.traceId())));
            assertThat(seen.path("result").path("isError").asBoolean(true)).as(seen.toString()).isFalse();
            JsonNode trace = ApiJson.tree(seen.path("result").path("content").get(0).path("text").asText());
            assertThat(trace.path("note").asText()).contains("正文已脱敏").isNotEqualTo(AuthoringCalls.UNTRUSTED);

            JsonNode own = server.rpc(session, 9, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(own.path("result").path("isError").asBoolean(true)).as(own.toString()).isFalse();
            JsonNode ownBody = ApiJson.tree(own.path("result").path("content").get(0).path("text").asText());
            assertThat(ownBody.path("traceId").asText()).isEqualTo(traceId);
            assertThat(ownBody.path("devTrace").isObject()).isTrue();
            assertThat(ownBody.path("conch").asText()).isEqualTo("trial_ping");

            CreatedApiKey all = server.runtime.apiKeys().create(
                    "all", new ApiKeyScope(Set.of(), Set.of(), false, true, Set.of(), true), Actors.CONSOLE);
            String allSession = server.initialize(all.plaintext(), false);
            JsonNode hidden = server.rpc(all.plaintext(), allSession, 2, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(toolMessage(hidden)).isEqualTo(McpCalls.TRACE_DENIED);
            assertThat(toolMessage(hidden)).doesNotContain("没有这个工具");
        } finally {
            upstream.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void conchWhoseNameStartsWithTheDeveloperPrefixStaysAnOrdinaryPackage() throws Exception {
        String yaml = """
                conch: hermit_dev__sandbox
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9
                title: 沙盒
                description: 名字以内置开发者工具包的前缀开头。
                credentials: {}
                actions:
                  ping:
                    title: 探测
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
                """;
        for (int limit : new int[] {40, 0}) {
            CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(limit);
            try (Harness server = Harness.open(temp.resolve("prefix-" + limit), config)) {
                server.runtime.debug().setEnabled(true, Actors.CONSOLE);
                String session = server.initialize(false);
                assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml)))
                        .path("ok").asBoolean()).isTrue();
                if (limit == 0) {
                    JsonNode described = server.call(session, "describe_capability",
                            Map.of("name", "hermit_dev__sandbox__ping"));
                    assertThat(described.path("result").path("isError").asBoolean()).as(described.toString()).isFalse();
                    JsonNode called = server.call(session, "call_capability",
                            Map.of("name", "hermit_dev__sandbox__ping", "arguments", Map.of()));
                    // 还没建账号：走到了普通能力的调用链，而不是被当成开发者工具拒绝。
                    assertThat(toolError(called).path("code").asText()).as(called.toString()).isEqualTo("NOT_CONFIGURED");
                } else {
                    JsonNode direct = server.call(session, "hermit_dev__sandbox__ping", Map.of());
                    assertThat(toolError(direct).path("code").asText()).as(direct.toString()).isEqualTo("NOT_CONFIGURED");
                }
            }
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
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml.toString()))).path("ok").asBoolean())
                    .isTrue();
            JsonNode tools = server.rpc(session, 8, "tools/list", null);
            // 目录模式：开发者工具包和其它规则包一样收进 list_systems，不平铺在 tools/list 里。
            assertThat(toolNames(tools)).contains("list_systems", "call_capability");
            assertThat(toolNames(tools)).doesNotContain("bulk_local__act_00")
                    .noneMatch(name -> name.startsWith("hermit_dev__"));
            JsonNode systems = structured(server.call(session, "list_systems", Map.of())).path("systems");
            assertThat(systems.findValuesAsText("conch")).contains("bulk_local", "hermit_dev");
            JsonNode found = structured(server.call(session, "search_capabilities",
                    Map.of("conch", "hermit_dev", "ungrouped", true)));
            assertThat(found.path("matches").findValuesAsText("name")).contains(
                    "hermit_dev__validate", "hermit_dev__save_local", "hermit_dev__trial_run", "hermit_dev__debug_trace");
            JsonNode described = structured(server.call(session, "describe_capability",
                    Map.of("name", "hermit_dev__validate")));
            assertThat(described.path("inputSchema").path("properties").has("yaml")).isTrue();
            JsonNode viaCall = structured(server.call(session, "call_capability",
                    Map.of("name", "hermit_dev__validate", "arguments", Map.of("yaml", yaml.toString()))));
            assertThat(viaCall.path("ok").asBoolean()).isTrue();

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

    @org.junit.jupiter.api.Test
    void fullAccessKeyWithoutDevToolsNeverSeesTheDeveloperPackage() throws Exception {
        for (int limit : new int[] {40, 0}) {
            CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(limit);
            try (Harness server = Harness.open(temp.resolve("no-dev-" + limit), config)) {
                server.runtime.debug().setEnabled(true, Actors.CONSOLE);
                CreatedApiKey plain = server.runtime.apiKeys().create(
                        "plain-all", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
                HttpResponse<String> init = server.postRaw(plain.plaintext(), null, """
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                        """, Map.of());
                assertThat(ApiJson.tree(init.body()).path("result").path("instructions").asText())
                    .isEqualTo(McpCatalog.INSTRUCTIONS);
                String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();

                assertThat(toolNames(server.rpc(plain.plaintext(), session, 2, "tools/list", null)))
                        .noneMatch(name -> name.startsWith("hermit_dev__"));
                assertThat(server.rpc(plain.plaintext(), session, 3, "resources/list", null).toString())
                        .doesNotContain("hermit_dev");
                JsonNode denied = server.rpc(plain.plaintext(), session, 4, "tools/call",
                        Map.of("name", "hermit_dev__validate", "arguments", Map.of("yaml", "conch: x")));
                assertThat(toolError(denied).path("code").asText()).isEqualTo("FORBIDDEN");
                assertThat(toolMessage(denied)).isEqualTo(AuthoringCalls.NEEDS_DEV_SCOPE);
                if (limit == 0) {
                    JsonNode systems = server.rpc(plain.plaintext(), session, 5, "tools/call",
                            Map.of("name", "list_systems", "arguments", Map.of()));
                    assertThat(systems.toString()).doesNotContain("hermit_dev");
                    JsonNode viaCall = server.rpc(plain.plaintext(), session, 6, "tools/call",
                            Map.of("name", "call_capability", "arguments",
                                    Map.of("name", "hermit_dev__guide", "arguments", Map.of())));
                    assertThat(toolError(viaCall).path("code").asText()).isEqualTo("FORBIDDEN");
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    void readOnlyKeyCannotSaveAndOnlyTrialsReadOnlyActions() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/note", exchange -> {
            writes.incrementAndGet();
            byte[] body = "{\"message\":\"saved\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try (Harness server = Harness.open(temp.resolve("readonly"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            String yaml = readWritePackage("ro_ping", "1.0.0", base);
            String session = server.initialize(false);
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            server.runtime.admin().createAccount("ro_ping", "main", Actors.CONSOLE);
            Path stored = server.runtime.paths().localConches().resolve("ro_ping.yaml");
            Path other = server.runtime.paths().localConches().resolve("ro_other.yaml");

            CreatedApiKey readOnly = server.runtime.apiKeys().create(
                    "read-only", new ApiKeyScope(Set.of(), Set.of(), true, true, Set.of(), true), Actors.CONSOLE);
            CreatedApiKey namedReadOnly = server.runtime.apiKeys().create(
                    "named-read-only", new ApiKeyScope(Set.of("ro_ping"), Set.of(), true, false, Set.of(), true), Actors.CONSOLE);
            String readOnlySession = server.initialize(readOnly.plaintext(), false);
            String namedSession = server.initialize(namedReadOnly.plaintext(), false);
            JsonNode readOnlyTools = DevToolList.expand(
                    server.rpc(readOnly.plaintext(), readOnlySession, 2, "tools/list", null),
                    server.devOverview(readOnly.plaintext(), readOnlySession));
            assertThat(toolNames(readOnlyTools)).doesNotContain("hermit_dev__save_local")
                    .contains("hermit_dev__validate", "hermit_dev__trial_run", "hermit_dev__debug_trace");

            JsonNode validated = server.rpc(readOnly.plaintext(), readOnlySession, 3, "tools/call", Map.of(
                    "name", "hermit_dev__validate", "arguments", Map.of("yaml", yaml)));
            assertThat(validated.path("result").path("isError").asBoolean(true)).isFalse();
            assertThat(structured(validated).path("ok").asBoolean()).isTrue();

            String replacement = yaml.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode saveDenied = server.rpc(readOnly.plaintext(), readOnlySession, 4, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", replacement)));
            assertThat(toolMessage(saveDenied)).isEqualTo(AuthoringCalls.READ_ONLY_SAVE);
            assertThat(toolError(saveDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            String fresh = readWritePackage("ro_other", "1.0.0", base);
            JsonNode freshDenied = server.rpc(namedReadOnly.plaintext(), namedSession, 4, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", fresh)));
            assertThat(toolMessage(freshDenied)).isEqualTo(AuthoringCalls.READ_ONLY_SAVE);
            assertThat(Files.readString(stored)).contains("version: 1.0.0").doesNotContain("version: 1.0.1");
            assertThat(other).doesNotExist();

            JsonNode shown = server.rpc(readOnly.plaintext(), readOnlySession, 5, "tools/call", Map.of(
                    "name", "hermit_dev__trial_run",
                    "arguments", Map.of("conch", "ro_ping", "action", "show", "account", "main")));
            assertThat(shown.path("result").path("isError").asBoolean(true)).as(shown.toString()).isFalse();
            assertThat(structured(shown).path("data").path("message").asText()).isEqualTo("seen");
            JsonNode ownTrace = server.rpc(readOnly.plaintext(), readOnlySession, 6, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", structured(shown).path("traceId").asText())));
            assertThat(ownTrace.path("result").path("isError").asBoolean(true)).as(ownTrace.toString()).isFalse();

            JsonNode writeDenied = server.rpc(readOnly.plaintext(), readOnlySession, 7, "tools/call", Map.of(
                    "name", "hermit_dev__trial_run",
                    "arguments", Map.of("conch", "ro_ping", "action", "save_note", "account", "main")));
            assertThat(toolMessage(writeDenied)).isEqualTo(AuthoringCalls.READ_ONLY_TRIAL_PREFIX + "save_note 不是只读，已拒绝。");
            assertThat(writes).hasValue(0);
            assertThat(Files.readString(stored)).doesNotContain("version: 1.0.1");
        } finally {
            upstream.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void externalSocketCannotAuthorOrReadTheGuideEvenWithoutOrigin() throws Exception {
        String external = nonLoopback();
        try (Harness server = Harness.open(temp.resolve("remote"), "0.0.0.0")) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String localSession = server.initialize(false);
            JsonNode localTools = DevToolList.expand(
                    server.rpc(localSession, 2, "tools/list", null),
                    server.devOverview(server.key.plaintext(), localSession));
            assertThat(toolNames(localTools)).contains("hermit_dev__validate", "hermit_dev__save_local", "hermit_dev__trial_run", "hermit_dev__debug_trace");
            JsonNode localResources = server.rpc(localSession, 3, "resources/list", null);
            assertThat(resourceUris(localResources)).contains(AuthoringGuide.URI);
            JsonNode localGuide = server.rpc(localSession, 4, "resources/read", Map.of("uri", AuthoringGuide.URI));
            assertThat(localGuide.path("result").path("contents").get(0).path("text").asText()).contains("从 HAR 写规则");
            String yaml = LocalConchBehaviorTest.ping("remote_ping", "1.0.0");
            String remote = "http://" + external + ":" + server.httpServer.port();

            HttpResponse<String> opened = server.postRaw(remote + "/mcp", server.key.plaintext(), null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                    """, Map.of());
            assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
            assertThat(ApiJson.tree(opened.body()).path("result").path("instructions").asText())
                    .isEqualTo(McpCatalog.INSTRUCTIONS);
            String remoteSession = opened.headers().firstValue("Mcp-Session-Id").orElseThrow();
            HttpResponse<String> remoteTools = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """, Map.of());
            HttpResponse<String> remoteOverview = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"hermit_dev","arguments":{}}}
                    """, Map.of());
            assertThat(ApiJson.tree(remoteOverview.body()).path("result").path("structuredContent").path("tools")
                    .findValuesAsText("name")).containsExactly("hermit_dev__debug_trace");
            assertThat(remoteTools.body()).contains("\"name\":\"hermit_dev\"")
                    .doesNotContain("hermit_dev__debug_trace_list")
                    .doesNotContain("hermit_dev__guide")
                    .doesNotContain("hermit_dev__read_yaml")
                    .doesNotContain("hermit_dev__capture_list")
                    .doesNotContain("hermit_dev__capture_get")
                    .doesNotContain("hermit_dev__validate")
                    .doesNotContain("hermit_dev__save_local")
                    .doesNotContain("hermit_dev__test_login")
                    .doesNotContain("hermit_dev__mcp_refresh")
                    .doesNotContain("hermit_dev__trial_run");
            HttpResponse<String> remoteResources = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":3,"method":"resources/list"}
                    """, Map.of());
            assertThat(remoteResources.body()).doesNotContain(AuthoringGuide.URI);
            for (String tool : java.util.List.of(
                    "hermit_dev__debug_trace_list", "hermit_dev__guide", "hermit_dev__read_yaml", "hermit_dev__auth_list",
                    "hermit_dev__capture_list", "hermit_dev__capture_get", "hermit_dev__dry_run_response",
                    "hermit_dev__validate", "hermit_dev__save_local", "hermit_dev__test_login",
                    "hermit_dev__mcp_refresh", "hermit_dev__mcp_configure", "hermit_dev__stage_text_file", "hermit_dev__trial_run")) {
                String payload = switch (tool) {
                    case "hermit_dev__debug_trace_list" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__debug_trace_list","arguments":{}}}
                            """;
                    case "hermit_dev__guide" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__guide","arguments":{}}}
                            """;
                    case "hermit_dev__read_yaml" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__read_yaml","arguments":{"conch":"remote_ping"}}}
                            """;
                    case "hermit_dev__capture_list" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__capture_list","arguments":{}}}
                            """;
                    case "hermit_dev__auth_list" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__auth_list","arguments":{}}}
                            """;
                    case "hermit_dev__capture_get" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__capture_get","arguments":{"captureId":"cap_00000000"}}}
                            """;
                    case "hermit_dev__dry_run_response" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__dry_run_response","arguments":{"response":{"status":200,"body":"{}"}}}}
                            """;
                    case "hermit_dev__mcp_configure" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__mcp_configure","arguments":{"conch":"remote_ping"}}}
                            """;
                    case "hermit_dev__stage_text_file" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__stage_text_file","arguments":{"filename":"a.txt","content":"hi"}}}
                            """;
                    case "hermit_dev__test_login", "hermit_dev__mcp_refresh" -> """
                            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"%s","arguments":{"conch":"remote_ping"}}}
                            """.formatted(tool);
                    default -> callJson(tool, yaml);
                };
                HttpResponse<String> called = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, payload, Map.of());
                assertThat(called.statusCode()).as(called.body()).isEqualTo(200);
                JsonNode body = ApiJson.tree(called.body());
                assertThat(toolMessage(body)).as(tool).isEqualTo(AuthoringCalls.LOCAL_ONLY);
                assertThat(toolError(body).path("code").asText()).isEqualTo("FORBIDDEN");
            }
            assertThat(server.runtime.paths().localConches().resolve("remote_ping.yaml")).doesNotExist();

            HttpResponse<String> guide = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":8,"method":"resources/read","params":{"uri":"%s"}}
                    """.formatted(AuthoringGuide.URI), Map.of());
            assertThat(guide.statusCode()).isEqualTo(200);
            JsonNode guideError = ApiJson.tree(guide.body()).path("error");
            assertThat(guideError.path("message").asText()).isEqualTo(AuthoringCalls.LOCAL_ONLY);
            assertThat(guideError.path("data").path("code").asText()).isEqualTo("FORBIDDEN");

            HttpResponse<String> console = server.http.send(HttpRequest.newBuilder(URI.create(remote + "/console/api/session"))
                    .header("X-Hermit-Console", "1")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(console.statusCode()).isEqualTo(403);
            assertThat(console.body()).contains("控制台只接受本机访问。");

            JsonNode saved = server.call(localSession, "hermit_dev__save_local", Map.of("yaml", yaml));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(server.runtime.paths().localConches().resolve("remote_ping.yaml")).exists();

            // 控制台触发的本地包 test_login 追踪：本机全部权限读写 Key 可查，远端连接仍拒绝。
            server.runtime.admin().createAccount("remote_ping", "alice", Actors.CONSOLE);
            dev.hermit.common.model.ConchDoc savedDoc = server.runtime.registry().find("remote_ping").orElseThrow();
            server.runtime.admin().testLogin(
                    dev.hermit.crab.store.Origin.LOCAL, savedDoc, "alice", "remote-console-login", Actors.CONSOLE);
            assertThat(server.runtime.devTraces().find("remote-console-login")).isPresent();
            JsonNode localSeen = server.call(localSession, "hermit_dev__debug_trace", Map.of("traceId", "remote-console-login"));
            assertThat(localSeen.path("result").path("isError").asBoolean(true)).as(localSeen.toString()).isFalse();
            HttpResponse<String> remoteDenied = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"hermit_dev__debug_trace","arguments":{"traceId":"remote-console-login"}}}
                    """, Map.of());
            assertThat(remoteDenied.statusCode()).isEqualTo(200);
            JsonNode remoteDeniedBody = ApiJson.tree(remoteDenied.body());
            assertThat(toolError(remoteDeniedBody).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(remoteDeniedBody)).isEqualTo(McpCalls.TRACE_DENIED);
        }
    }

    @org.junit.jupiter.api.Test
    void trialNamesTheMissingCredentialAndDoesNotEchoAnIllegalAccount() throws Exception {
        try (Harness server = Harness.open(temp.resolve("account"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String yaml = """
                    conch: needs_password
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: http://127.0.0.1:9
                    title: 需要凭证
                    description: 缺凭证时要说清字段。
                    credentials:
                      password:
                        type: secret
                        label: 密码
                        required: true
                    actions:
                      show:
                        title: 看
                        description: 返回常量。
                        idempotent: true
                        mcp: {expose: true, read_only: true}
                        steps:
                          - set: {message: seen}
                        output:
                          message: {type: string, description: 文本}
                        examples:
                          - input: {}
                            output: {message: seen}
                        notes: 无
                        related: []
                    """;
            String session = server.initialize(false);
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            HttpResponse<String> noAccount = server.postRaw(server.key.plaintext(), session, """
                    {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"hermit_dev__trial_run","arguments":{"conch":"needs_password","action":"show"}}}
                    """, Map.of());
            assertThat(noAccount.headers().firstValue("X-Hermit-Trace")).isEmpty();
            JsonNode noAccountBody = ApiJson.tree(noAccount.body());
            assertThat(noAccountBody.toString()).doesNotContain("\"traceId\"");
            assertThat(toolMessage(noAccountBody)).contains("还没有账号").contains("needs_password");
            assertThat(noAccountBody.toString()).contains("不可查追踪");
            String requestId = toolError(noAccountBody).path("requestId").asText();
            assertThat(requestId).isNotBlank();
            JsonNode rejectedTrace = server.rpc(session, 8, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", requestId)));
            assertThat(toolMessage(rejectedTrace)).doesNotContain("试跑被拒").doesNotContain("没有这个工具");

            server.runtime.admin().createAccount("needs_password", "main", Actors.CONSOLE);
            HttpResponse<String> rejected = server.postRaw(server.key.plaintext(), session, """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hermit_dev__trial_run","arguments":{"conch":"needs_password","action":"show","account":"main"}}}
                    """, Map.of());
            assertThat(rejected.headers().firstValue("X-Hermit-Trace")).isEmpty();
            JsonNode missing = ApiJson.tree(rejected.body());
            assertThat(missing.toString()).doesNotContain("\"traceId\"");
            assertThat(toolMessage(missing)).contains("main").contains("密码（password）").contains("填写凭证")
                    .doesNotContain("missing credentials");
            assertThat(missing.toString()).contains("不可查追踪").doesNotContain("s3cret-value");

            JsonNode illegal = server.call(session, "hermit_dev__trial_run", Map.of(
                    "conch", "needs_password", "action", "show", "account", "s3cret-value!!"));
            assertThat(toolMessage(illegal)).isEqualTo(CrabRuntime.TRIAL_ACCOUNT_INVALID);
            assertThat(toolError(illegal).path("field").asText()).isEqualTo("account");
            assertThat(toolError(illegal).has("traceId")).isFalse();
            assertThat(toolError(illegal).path("requestNote").asText()).contains("不可查追踪");
            assertThat(illegal.toString()).doesNotContain("s3cret-value").doesNotContain("invalid account name");
        }
    }

    @org.junit.jupiter.api.Test
    void developerModeNotifiesToolAndResourceListsWhenOpenedAndClosed() throws Exception {
        try (Harness server = Harness.open(temp.resolve("notify-debug"))) {
            String session = server.initialize(false);
            try (Sse sse = Sse.open(server, session)) {
                sse.awaitPrefix(": open");
                server.runtime.debug().setEnabled(true, Actors.CONSOLE);
                sse.awaitMethod("notifications/tools/list_changed");
                sse.awaitMethod("notifications/resources/list_changed");
                server.runtime.debug().setEnabled(false, Actors.CONSOLE);
                sse.awaitMethod("notifications/tools/list_changed");
                sse.awaitMethod("notifications/resources/list_changed");
            }
        }
    }

    @org.junit.jupiter.api.Test
    void confirmationTimeoutIsNotAnInternalError() throws Exception {
        long previous = McpSession.elicitWaitMillis;
        McpSession.elicitWaitMillis = 400;
        try (Harness server = Harness.open(temp.resolve("timeout"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String yaml = LocalConchBehaviorTest.ping("timeout_ping", "1.0.0");
            assertThat(structured(server.call(server.initialize(false), "hermit_dev__save_local", Map.of("yaml", yaml)))
                    .path("ok").asBoolean()).isTrue();
            String next = yaml.replace("version: 1.0.0", "version: 1.0.1")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                JsonNode timed = server.call(confirming, "hermit_dev__save_local", Map.of("yaml", next));
                assertThat(toolMessage(timed)).contains("等待确认超时").doesNotContain("内部错误");
                assertThat(toolError(timed).path("code").asText()).isNotEqualTo("INTERNAL");
                assertThat(toolError(timed).path("hint").asText()).doesNotContain("内部错误");
            }
        } finally {
            McpSession.elicitWaitMillis = previous;
        }
    }

    @org.junit.jupiter.api.Test
    void widenedConfirmRejectsWhenTheFileChanged() throws Exception {
        try (Harness server = Harness.open(temp.resolve("stale-confirm"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String yaml = LocalConchBehaviorTest.ping("stale_ping", "1.0.0");
            assertThat(structured(server.call(server.initialize(false), "hermit_dev__save_local", Map.of("yaml", yaml)))
                    .path("ok").asBoolean()).isTrue();
            String widened = yaml.replace("version: 1.0.0", "version: 1.0.1")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(
                        server.callUnchecked(confirming, "hermit_dev__save_local", Map.of("yaml", widened))));
                accept.start();
                JsonNode question = sse.awaitMethod("elicitation/create");
                String moved = yaml.replace("version: 1.0.0", "version: 1.0.2");
                server.runtime.localConches().importBytes(moved.getBytes(java.nio.charset.StandardCharsets.UTF_8), Actors.CLI, true);
                server.answer(confirming, question, "accept", true);
                accept.join(15_000);
                assertThat(toolError(accepted.get()).path("code").asText()).isEqualTo("CONFLICT");
                assertThat(toolMessage(accepted.get())).isEqualTo(dev.hermit.crab.sync.LocalConchException.STALE);
                Path file = server.runtime.paths().localConches().resolve("stale_ping.yaml");
                assertThat(Files.readString(file)).contains("version: 1.0.2").doesNotContain("127.0.0.1:10");
            }
        }
    }

    @org.junit.jupiter.api.Test
    void undefinedVariablesFailAtValidateWithLineNumbers() throws Exception {
        String yaml = """
                conch: undef_ping
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9
                title: 未定义
                description: 变量还没声明。
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 引用了三个未定义变量。
                    steps:
                      - request:
                          method: GET
                          path: "/{{vars.token}}"
                          headers:
                            X-A: "{{token}}"
                          query:
                            q: "{{auth.token}}"
                    output:
                      message: {type: string, description: 文本, value: no}
                    examples:
                      - input: {}
                        output: {message: no}
                    notes: 无
                    related: []
                """;
        String declared = """
                conch: declared_ping
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9
                title: 已声明
                description: 这些变量都先出现过。
                credentials:
                  password: {type: secret, required: true, label: 密码}
                auth:
                  steps:
                    - extract:
                        viewstate: {jsonpath: $.view}
                    - request:
                        method: POST
                        path: /login
                        form:
                          state: "{{viewstate}}"
                          password: "{{credential.password}}"
                actions:
                  ping:
                    title: 探测
                    description: 使用已声明变量。
                    input:
                      page: {type: integer, description: 页码}
                    steps:
                      - request:
                          method: GET
                          path: /items
                          query: {p: "{{input.page}}"}
                      - extract:
                          items: {jsonpath: $.items}
                      - request:
                          method: GET
                          path: "/item/{{items}}"
                    output:
                      items: {type: string, description: 列表}
                    examples:
                      - input: {page: 1}
                        output: {items: ok}
                    notes: 无
                    related: []
                """;
        try (Harness server = Harness.open(temp.resolve("undef"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            JsonNode validated = server.call(session, "hermit_dev__validate", Map.of("yaml", yaml));
            JsonNode errors = structured(validated).path("errors");
            assertThat(structured(validated).path("ok").asBoolean()).isFalse();
            assertThat(errors).hasSize(3);
            String[] lines = yaml.split("\n", -1);
            for (JsonNode error : errors) {
                assertThat(error.path("code").asText()).isEqualTo("UNDECLARED_VARIABLE");
                assertThat(error.path("line").isNumber()).isTrue();
                String message = error.path("message").asText();
                assertThat(message).contains("未定义的变量");
                String ref = message.substring("引用了未定义的变量 ".length(), message.length() - 1);
                assertThat(lines[error.path("line").asInt() - 1]).contains("{{" + ref + "}}");
            }
            JsonNode ok = server.call(session, "hermit_dev__validate", Map.of("yaml", declared));
            assertThat(structured(ok).path("ok").asBoolean()).as(ok.toString()).isTrue();
            assertThat(McpErrors.toolHint("RULE_MISMATCH", "引用了未定义的变量 token。"))
                    .doesNotContain("上游").doesNotContain("版本不一致");
            assertThat(McpErrors.toolHint("AUTH_FAILED", "引用了未定义的变量 token。"))
                    .doesNotContain("上游").doesNotContain("版本不一致").doesNotContain("test-login");
        }
    }

    @org.junit.jupiter.api.Test
    void missingCredentialsNameTheAccountFieldAndConsoleLabelOnEveryCallPath() throws Exception {
        String yaml = """
                conch: partial_ping
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9
                title: 部分凭证
                description: 只填了一个字段。
                credentials:
                  password:
                    type: secret
                    label: 密码
                    required: true
                  token:
                    type: secret
                    label: 访问令牌
                    required: true
                actions:
                  show:
                    title: 看
                    description: 返回常量。
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {message: seen}
                    output:
                      message: {type: string, description: 文本, value: seen}
                    examples:
                      - input: {}
                        output: {message: seen}
                    notes: 无
                    related: []
                """;
        try (Harness server = Harness.open(temp.resolve("partial"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            dev.hermit.common.model.ConchDoc doc = server.runtime.registry().find("partial_ping").orElseThrow();
            server.runtime.admin().createAccount("partial_ping", "ada", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(doc, "ada", Map.of("password", "filled-secret"), Actors.CONSOLE);

            JsonNode trial = server.call(session, "hermit_dev__trial_run", Map.of(
                    "conch", "partial_ping", "action", "show", "account", "ada"));
            JsonNode mcp = server.call(session, "partial_ping__show", Map.of("account", "ada"));
            HttpResponse<String> api = server.http.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(server.base + "/api/partial_ping/show"))
                    .header("Authorization", "Bearer " + server.key.plaintext())
                    .header("X-Hermit-Account", "ada")
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> login = server.http.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(server.base + "/console/api/conches/partial_ping/accounts/ada/test-login"))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            for (String text : java.util.List.of(
                    toolMessage(trial),
                    toolMessage(mcp),
                    api.body(),
                    login.body())) {
                assertThat(text).contains("ada").contains("访问令牌（token）").contains("填写凭证");
                assertThat(text).doesNotContain("missing credentials").doesNotContain("password（密码）").doesNotContain("filled-secret");
            }
        }
    }

    private static void assertSameIssues(Harness server, String session, String yaml) throws Exception {
        JsonNode validated = server.call(session, "hermit_dev__validate", Map.of("yaml", yaml));
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
            String message = line.substring(pathEnd + 1).replaceFirst(" 第 \\d+ 行$", "");
            found.add(new Issue(line.substring(0, codeEnd), line.substring(codeEnd + 1, pathEnd), message, null));
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
            JsonNode tree = ApiJson.tree(content.get(0).path("text").asText());
            if (tree.hasNonNull("message")) {
                return tree.path("message").asText();
            }
            return tree.path("error").path("message").asText();
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

    private static String readWritePackage(String name, String version, String base) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 只读和写入
                description: 用来区分只读能力。
                credentials: {}
                actions:
                  show:
                    title: 看
                    description: 只读。
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {message: seen}
                    output:
                      message: {type: string, description: 文本}
                    examples:
                      - input: {}
                        output: {message: seen}
                    notes: 无
                    related: []
                  save_note:
                    title: 写
                    description: 会打上游。
                    idempotent: false
                    mcp: {expose: true, read_only: false}
                    steps:
                      - request: {method: POST, path: /note}
                      - extract:
                          message: {jsonpath: $.message}
                    output:
                      message: {type: string, description: 文本}
                    examples:
                      - input: {}
                        output: {message: saved}
                    notes: 无
                    related: []
                """.formatted(name, version, base);
    }

    private static String callJson(String tool, String yaml) {
        java.util.LinkedHashMap<String, Object> arguments = new java.util.LinkedHashMap<>();
        if ("hermit_dev__trial_run".equals(tool)) {
            arguments.put("conch", "remote_ping");
            arguments.put("action", "ping");
        } else {
            arguments.put("yaml", yaml);
        }
        java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 3);
        body.put("method", "tools/call");
        body.put("params", params);
        return ApiJson.write(body);
    }

    private static String nonLoopback() throws Exception {
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        while (nics.hasMoreElements()) {
            NetworkInterface nic = nics.nextElement();
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isAnyLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        throw new AssertionError("no non-loopback address");
    }

    /** 只点名了别的包的 Key：可以建新包，但不能覆盖或试跑它没点名的已有本地包。 */
    @org.junit.jupiter.api.Test
    void namedKeysCannotOverwriteOrTrialOtherLocalPackages() throws Exception {
        try (Harness server = Harness.open(temp.resolve("scope"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            assertThat(server.consoleImport(writable("other_pkg", "1.0.0")).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount("other_pkg", "main", Actors.CONSOLE);
            CreatedApiKey named = server.runtime.apiKeys().create(
                    "mine-only", new ApiKeyScope(Set.of("mine_pkg"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            String session = server.initialize(named.plaintext(), false);

            JsonNode trial = server.rpc(named.plaintext(), session, 3, "tools/call", Map.of(
                    "name", "hermit_dev__trial_run", "arguments", Map.of("conch", "other_pkg", "action", "write_note")));
            assertThat(toolError(trial).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(trial)).isEqualTo(AuthoringCalls.NOT_IN_SCOPE_PREFIX + "other_pkg"
                    + AuthoringCalls.NOT_IN_SCOPE_SUFFIX);
            assertThat(trial.toString()).doesNotContain("wrote-other_pkg");

            Path stored = server.runtime.paths().localConches().resolve("other_pkg.yaml");
            String before = Files.readString(stored);
            JsonNode overwrite = server.rpc(named.plaintext(), session, 4, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", writable("other_pkg", "1.0.1"))));
            assertThat(toolError(overwrite).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(overwrite)).startsWith(AuthoringCalls.NOT_IN_SCOPE_PREFIX + "other_pkg");
            assertThat(Files.readString(stored)).isEqualTo(before);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            String widened = writable("other_pkg", "1.0.2")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode widenedOverwrite = server.rpc(named.plaintext(), session, 6, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", widened)));
            assertThat(toolError(widenedOverwrite).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(widenedOverwrite)).startsWith(AuthoringCalls.NOT_IN_SCOPE_PREFIX + "other_pkg");
            assertThat(Files.readString(stored)).isEqualTo(before);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            JsonNode fresh = server.rpc(named.plaintext(), session, 5, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", writable("new_pkg", "1.0.0"))));
            assertThat(structured(fresh).path("ok").asBoolean()).as(fresh.toString()).isTrue();
        }
    }

    /** 破坏性能力的试跑和普通调用一样要确认；确认期间包被换掉就不执行。 */
    @org.junit.jupiter.api.Test
    void destructiveTrialNeedsConfirmationOfTheSameSnapshot() throws Exception {
        try (Harness server = Harness.open(temp.resolve("trial-confirm"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            assertThat(server.consoleImport(writable("write_pkg", "1.0.0")).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount("write_pkg", "main", Actors.CONSOLE);

            String plain = server.initialize(false);
            JsonNode refused = server.call(plain, "hermit_dev__trial_run", Map.of("conch", "write_pkg", "action", "write_note"));
            assertThat(toolError(refused).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(refused)).isEqualTo(McpCalls.NO_CONFIRM);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(server.callUnchecked(
                        confirming, "hermit_dev__trial_run", Map.of("conch", "write_pkg", "action", "write_note"))));
                accept.start();
                server.answer(confirming, sse.awaitMethod("elicitation/create"), "accept", true);
                accept.join(15_000);
                assertThat(structured(accepted.get()).path("data").path("message").asText())
                        .as(String.valueOf(accepted.get())).isEqualTo("wrote-write_pkg");

                AtomicReference<JsonNode> swapped = new AtomicReference<>();
                Thread swap = new Thread(() -> swapped.set(server.callUnchecked(
                        confirming, "hermit_dev__trial_run", Map.of("conch", "write_pkg", "action", "write_note"))));
                swap.start();
                JsonNode question = sse.awaitMethod("elicitation/create");
                server.runtime.registry().put(
                        ConchParser.parse(writable("write_pkg", "1.0.0").replace("wrote-write_pkg", "changed")),
                        dev.hermit.crab.store.Origin.LOCAL);
                server.answer(confirming, question, "accept", true);
                swap.join(15_000);
                assertThat(toolError(swapped.get()).path("code").asText()).isEqualTo("CONFLICT");
                assertThat(toolMessage(swapped.get())).isEqualTo(dev.hermit.crab.runtime.Invoker.CHANGED_AFTER_CONFIRM);
                assertThat(swapped.get().toString()).doesNotContain("\"changed\"");
            }
        }
    }

    /** 普通工具调用：确认的是旧版本，执行前发现注册表换了版本就拒绝（审计 A01 的 MCP 部分）。 */
    @org.junit.jupiter.api.Test
    void confirmedToolCallRefusesAVersionSwappedDuringConfirmation() throws Exception {
        try (Harness server = Harness.open(temp.resolve("call-confirm"))) {
            assertThat(server.consoleImport(writable("swap_pkg", "1.0.0")).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount("swap_pkg", "main", Actors.CONSOLE);
            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> result = new AtomicReference<>();
                Thread call = new Thread(() -> result.set(server.callUnchecked(
                        confirming, "swap_pkg__write_note", Map.of())));
                call.start();
                JsonNode question = sse.awaitMethod("elicitation/create");
                server.runtime.registry().put(
                        ConchParser.parse(writable("swap_pkg", "1.0.1").replace("wrote-swap_pkg", "changed")),
                        dev.hermit.crab.store.Origin.LOCAL);
                server.answer(confirming, question, "accept", true);
                call.join(15_000);
                assertThat(result.get().toString()).as(String.valueOf(result.get()))
                        .contains("CONFLICT")
                        .contains(dev.hermit.crab.runtime.Invoker.CHANGED_AFTER_CONFIRM)
                        .doesNotContain("\"changed\"");
            }
        }
    }

    /** Issue 5a: trialLocal 原子核对快照与 Key 权限；窄 Key 试跑未加载或正式包也拒；保存本地包只看本地账号。 */
    @org.junit.jupiter.api.Test
    void trialLocalEnforcesSnapshotPrincipalAndLocalAccountGuidance() throws Exception {
        try (Harness server = Harness.open(temp.resolve("trial-atomic"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String roV1 = readWritePackage("snap_pkg", "1.0.0", "http://127.0.0.1:9");
            assertThat(server.consoleImport(roV1).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "snap_pkg", "main", Actors.CONSOLE);
            dev.hermit.common.model.ConchDoc v1Doc = server.runtime.registry()
                    .find("snap_pkg", dev.hermit.crab.store.Origin.LOCAL).orElseThrow();

            // 窄 Key 试跑不在范围的包（即使尚未加载或为正式包），应返回 FORBIDDEN。
            CreatedApiKey narrow = server.runtime.apiKeys().create(
                    "narrow", new ApiKeyScope(Set.of("mine_only"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            String narrowSession = server.initialize(narrow.plaintext(), false);
            JsonNode absentDenied = server.rpc(narrow.plaintext(), narrowSession, 2, "tools/call", Map.of(
                    "name", "hermit_dev__trial_run", "arguments", Map.of("conch", "missing_pkg", "action", "show")));
            assertThat(toolError(absentDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(absentDenied)).isEqualTo(
                    AuthoringCalls.NOT_IN_SCOPE_PREFIX + "missing_pkg" + AuthoringCalls.NOT_IN_SCOPE_SUFFIX);

            // 即使原本是只读能力，本地快照变动后传入旧快照也必须以 409 CONFLICT 拒绝。
            String roV2 = roV1.replace("version: 1.0.0", "version: 1.0.1");
            server.runtime.registry().put(ConchParser.parse(roV2), dev.hermit.crab.store.Origin.LOCAL);
            dev.hermit.crab.store.ApiKeyPrincipal fullPrincipal =
                    server.runtime.apiKeys().authenticate(server.key.plaintext()).orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.runtime.trialLocal(
                            "snap_pkg", "show", java.util.Optional.of("main"), Map.of(), "t-snap",
                            fullPrincipal.id(), v1Doc, fullPrincipal, false))
                    .isInstanceOf(dev.hermit.crab.runtime.TrialRejected.class)
                    .satisfies(ex -> {
                        dev.hermit.crab.runtime.TrialRejected rej = (dev.hermit.crab.runtime.TrialRejected) ex;
                        assertThat(rej.status()).isEqualTo(409);
                        assertThat(rej.code()).isEqualTo(dev.hermit.common.error.ErrorCode.CONFLICT);
                    });

            // 即使 expectedSnapshot 为 null，trialLocal 仍原子校验 principal 范围、只读限制和破坏性确认。
            dev.hermit.crab.store.ApiKeyPrincipal narrowPrincipal =
                    server.runtime.apiKeys().authenticate(narrow.plaintext()).orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.runtime.trialLocal(
                            "snap_pkg", "show", java.util.Optional.of("main"), Map.of(), "t-narrow",
                            narrowPrincipal.id(), null, narrowPrincipal, false))
                    .isInstanceOf(dev.hermit.crab.runtime.TrialRejected.class)
                    .satisfies(ex -> assertThat(((dev.hermit.crab.runtime.TrialRejected) ex).code())
                            .isEqualTo(dev.hermit.common.error.ErrorCode.FORBIDDEN));

            CreatedApiKey roKey = server.runtime.apiKeys().create(
                    "ro-all", new ApiKeyScope(Set.of(), Set.of(), true, true, Set.of(), true), Actors.CONSOLE);
            dev.hermit.crab.store.ApiKeyPrincipal roPrincipal =
                    server.runtime.apiKeys().authenticate(roKey.plaintext()).orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.runtime.trialLocal(
                            "snap_pkg", "save_note", java.util.Optional.of("main"), Map.of(), "t-ro",
                            roPrincipal.id(), null, roPrincipal, false))
                    .isInstanceOf(dev.hermit.crab.runtime.TrialRejected.class)
                    .satisfies(ex -> {
                        dev.hermit.crab.runtime.TrialRejected rej = (dev.hermit.crab.runtime.TrialRejected) ex;
                        assertThat(rej.code()).isEqualTo(dev.hermit.common.error.ErrorCode.FORBIDDEN);
                        assertThat(rej.getMessage()).contains("save_note 不是只读");
                    });

            assertThat(server.consoleImport(writable("destr_pkg", "1.0.0")).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "destr_pkg", "main", Actors.CONSOLE);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.runtime.trialLocal(
                            "destr_pkg", "write_note", java.util.Optional.of("main"), Map.of(), "t-unconf",
                            fullPrincipal.id(), null, fullPrincipal, false))
                    .isInstanceOf(dev.hermit.crab.runtime.TrialRejected.class)
                    .satisfies(ex -> assertThat(((dev.hermit.crab.runtime.TrialRejected) ex).code())
                            .isEqualTo(dev.hermit.common.error.ErrorCode.CONFLICT));

            // 同名正式包生效且有账号，但本地包没有账号时，覆盖保存本地包应提示新建本地账号（NEXT_ACCOUNT）。
            String dualV1 = LocalConchBehaviorTest.ping("dual_pkg", "1.0.0");
            server.runtime.registry().put(ConchParser.parse(dualV1), dev.hermit.crab.store.Origin.OFFICIAL);
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.OFFICIAL, "dual_pkg", "prod", Actors.CONSOLE);
            String fullSession = server.initialize(false);
            assertThat(structured(server.call(fullSession, "hermit_dev__save_local", Map.of("yaml", dualV1)))
                    .path("ok").asBoolean()).isTrue();
            String dualV2 = dualV1.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode replaced = server.call(fullSession, "hermit_dev__save_local", Map.of("yaml", dualV2));
            assertThat(structured(replaced).path("ok").asBoolean()).as(replaced.toString()).isTrue();
            assertThat(structured(replaced).path("replaced").asBoolean()).isTrue();
            assertThat(structured(replaced).path("next").asText()).isEqualTo(AuthoringCalls.NEXT_ACCOUNT);
        }
    }

    /** Issue 5c: hermit_dev__debug_trace 核对 read_only 必须查 Origin.LOCAL，不能被同名生效的 OFFICIAL 只读定义放行。 */
    @org.junit.jupiter.api.Test
    void debugTraceUsesLocalReadOnlyNotActiveOfficial() throws Exception {
        try (Harness server = Harness.open(temp.resolve("dual-trace"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String localWrite = """
                    conch: dual_trace
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: http://127.0.0.1:9/
                    title: 本地写
                    description: 本地写能力。
                    credentials: {}
                    actions:
                      act:
                        title: 写操作
                        description: 本地非只读。
                        idempotent: false
                        mcp: {expose: true, read_only: false, destructive: false}
                        steps:
                          - set: {message: local-write}
                        output:
                          message: {type: string, description: 文本}
                        examples:
                          - input: {}
                            output: {message: local-write}
                        notes: 无
                        related: []
                    """;
            String officialRead = localWrite
                    .replace("read_only: false", "read_only: true")
                    .replace("local-write", "official-read");
            assertThat(server.consoleImport(localWrite).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "dual_trace", "main", Actors.CONSOLE);
            String fullSession = server.initialize(false);
            JsonNode mcpRan = server.call(fullSession, "hermit_dev__trial_run", Map.of("conch", "dual_trace", "action", "act"));
            assertThat(mcpRan.path("result").path("isError").asBoolean(true)).as(mcpRan.toString()).isFalse();
            String mcpTraceId = structured(mcpRan).path("traceId").asText();
            assertThat(mcpTraceId).isNotBlank();

            // 控制台试跑（apiKeyId == null）产生的追踪供点名该本地包的 Key 读取，校验其按本地包 read_only 判权。
            dev.hermit.crab.engine.ExecutionResult consoleRan = server.runtime.trialLocal(
                    "dual_trace", "act", java.util.Optional.of("main"), Map.of(), "console-dual-trace");
            assertThat(consoleRan.success()).isTrue();
            String traceId = consoleRan.traceId();

            // 同名正式包生效，且在正式包里 act 是 read_only: true。
            server.runtime.registry().put(ConchParser.parse(officialRead), dev.hermit.crab.store.Origin.OFFICIAL);
            assertThat(server.runtime.registry().activate("dual_trace", dev.hermit.crab.store.Origin.OFFICIAL)).isTrue();
            assertThat(server.runtime.registry().origin("dual_trace")).isEqualTo(dev.hermit.crab.store.Origin.OFFICIAL);

            CreatedApiKey namedReadOnly = server.runtime.apiKeys().create(
                    "named-ro", new ApiKeyScope(Set.of("dual_trace"), Set.of(), true, false, Set.of(), true), Actors.CONSOLE);
            String roSession = server.initialize(namedReadOnly.plaintext(), false);
            JsonNode denied = server.rpc(namedReadOnly.plaintext(), roSession, 2, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(toolError(denied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(denied)).isEqualTo(McpCalls.TRACE_DENIED);

            CreatedApiKey namedRw = server.runtime.apiKeys().create(
                    "named-rw", new ApiKeyScope(Set.of("dual_trace"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            String rwSession = server.initialize(namedRw.plaintext(), false);
            JsonNode allowed = server.rpc(namedRw.plaintext(), rwSession, 2, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(allowed.path("result").path("isError").asBoolean(true)).as(allowed.toString()).isFalse();

            // 其他 Key 通过 hermit_dev__trial_run 产生的追踪对 namedRw 保持不可见。
            JsonNode otherKeyDenied = server.rpc(namedRw.plaintext(), rwSession, 3, "tools/call", Map.of(
                    "name", "hermit_dev__debug_trace", "arguments", Map.of("traceId", mcpTraceId)));
            assertThat(toolError(otherKeyDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(otherKeyDenied)).isEqualTo(McpCalls.TRACE_DENIED);
        }
    }

    /** H1：按分组或能力限权的可写 Key 不能覆盖已有本地包，但整包范围 Key 与全权限 Key 仍可覆盖，且限权 Key 仍可建新包和试跑授权能力。 */
    @org.junit.jupiter.api.Test
    void groupOrActionScopedKeysCannotOverwriteExistingLocalPackage() throws Exception {
        try (Harness server = Harness.open(temp.resolve("narrow-save"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            assertThat(server.consoleImport(groupedLocal("scoped_local", "1.0.0")).statusCode()).isEqualTo(200);
            server.runtime.admin().createAccount("scoped_local", "main", Actors.CONSOLE);
            Path stored = server.runtime.paths().localConches().resolve("scoped_local.yaml");
            String original = Files.readString(stored);

            CreatedApiKey groupScoped = server.runtime.apiKeys().create(
                    "group-scoped",
                    ApiKeyScope.explicit(false, Set.of("scoped_local"), Set.of("scoped_local:hr"), Set.of(), false, true),
                    Actors.CONSOLE);
            CreatedApiKey actionScoped = server.runtime.apiKeys().create(
                    "action-scoped",
                    ApiKeyScope.explicit(false, Set.of("scoped_local"), Set.of(), Set.of("scoped_local.safe_op"), false, true),
                    Actors.CONSOLE);
            CreatedApiKey wholePackage = server.runtime.apiKeys().create(
                    "whole-pkg",
                    ApiKeyScope.explicit(false, Set.of("scoped_local"), Set.of(), Set.of(), false, true),
                    Actors.CONSOLE);

            String groupSession = server.initialize(groupScoped.plaintext(), false);
            String actionSession = server.initialize(actionScoped.plaintext(), false);
            String wholeSession = server.initialize(wholePackage.plaintext(), false);
            String allSession = server.initialize(false);

            // 可写 Key 仍暴露 hermit_dev__save_local（可建新包）。
            assertThat(toolNames(DevToolList.expand(
                    server.rpc(groupScoped.plaintext(), groupSession, 2, "tools/list", null),
                    server.devOverview(groupScoped.plaintext(), groupSession))))
                    .contains("hermit_dev__save_local");
            assertThat(toolNames(DevToolList.expand(
                    server.rpc(actionScoped.plaintext(), actionSession, 2, "tools/list", null),
                    server.devOverview(actionScoped.plaintext(), actionSession))))
                    .contains("hermit_dev__save_local");

            // 1) 按分组限权的 Key 试图在同目的地边界下把管理能力改入 hr 组：必须 FORBIDDEN。
            String groupEscalation = groupedLocal("scoped_local", "1.0.1").replace("group: admin", "group: hr");
            JsonNode groupDenied = server.rpc(groupScoped.plaintext(), groupSession, 3, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", groupEscalation)));
            assertThat(toolError(groupDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(groupDenied)).isEqualTo(
                    AuthoringCalls.NARROWED_SAVE_PREFIX + "scoped_local" + AuthoringCalls.NARROWED_SAVE_SUFFIX);
            assertThat(Files.readString(stored)).isEqualTo(original);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            // 扩大目的地时同样直接 FORBIDDEN，不挂待确认覆盖。
            String widened = groupedLocal("scoped_local", "1.0.2")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode groupWidenedDenied = server.rpc(groupScoped.plaintext(), groupSession, 4, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", widened)));
            assertThat(toolError(groupWidenedDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(groupWidenedDenied)).isEqualTo(
                    AuthoringCalls.NARROWED_SAVE_PREFIX + "scoped_local" + AuthoringCalls.NARROWED_SAVE_SUFFIX);
            assertThat(Files.readString(stored)).isEqualTo(original);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            // 2) 按能力限权的 Key 试图在同目的地边界下篡改 safe_op：必须 FORBIDDEN。
            String actionEscalation = groupedLocal("scoped_local", "1.0.1").replace("safe-scoped_local", "hijacked");
            JsonNode actionDenied = server.rpc(actionScoped.plaintext(), actionSession, 3, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", actionEscalation)));
            assertThat(toolError(actionDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(actionDenied)).isEqualTo(
                    AuthoringCalls.NARROWED_SAVE_PREFIX + "scoped_local" + AuthoringCalls.NARROWED_SAVE_SUFFIX);
            assertThat(Files.readString(stored)).isEqualTo(original);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            // 3) 限权 Key 仍可新建全新本地包，并试跑授权范围内的能力（范围外能力拒绝）。
            JsonNode created = server.rpc(groupScoped.plaintext(), groupSession, 5, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", writable("brand_new_pkg", "1.0.0"))));
            assertThat(structured(created).path("ok").asBoolean()).as(created.toString()).isTrue();
            assertThat(structured(created).path("replaced").asBoolean()).isFalse();

            JsonNode allowedTrial = server.rpc(groupScoped.plaintext(), groupSession, 6, "tools/call", Map.of(
                    "name", "hermit_dev__trial_run", "arguments", Map.of("conch", "scoped_local", "action", "safe_op")));
            assertThat(allowedTrial.path("result").path("isError").asBoolean(true)).as(allowedTrial.toString()).isFalse();
            assertThat(structured(allowedTrial).path("data").path("message").asText()).isEqualTo("safe-scoped_local");

            JsonNode deniedTrial = server.rpc(groupScoped.plaintext(), groupSession, 7, "tools/call", Map.of(
                    "name", "hermit_dev__trial_run", "arguments", Map.of("conch", "scoped_local", "action", "admin_op")));
            assertThat(toolError(deniedTrial).path("code").asText()).isEqualTo("FORBIDDEN");

            // 4) 整包范围 Key（conches 点名且未限分组/能力）与全部权限 Key 仍可覆盖已有本地包。
            String wholeUpdate = groupedLocal("scoped_local", "1.0.1");
            JsonNode wholeSaved = server.rpc(wholePackage.plaintext(), wholeSession, 3, "tools/call", Map.of(
                    "name", "hermit_dev__save_local", "arguments", Map.of("yaml", wholeUpdate)));
            assertThat(structured(wholeSaved).path("ok").asBoolean()).as(wholeSaved.toString()).isTrue();
            assertThat(structured(wholeSaved).path("replaced").asBoolean()).isTrue();
            assertThat(Files.readString(stored)).contains("version: 1.0.1");

            String allUpdate = groupedLocal("scoped_local", "1.0.2");
            JsonNode allSaved = server.call(allSession, "hermit_dev__save_local", Map.of("yaml", allUpdate));
            assertThat(structured(allSaved).path("ok").asBoolean()).as(allSaved.toString()).isTrue();
            assertThat(structured(allSaved).path("replaced").asBoolean()).isTrue();
            assertThat(Files.readString(stored)).contains("version: 1.0.2");
        }
    }

    private static String groupedLocal(String name, String version) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.10.0
                base_url: http://127.0.0.1:9/
                title: 分组本地包
                description: 用于校验按分组或能力限权的 Key 不能覆盖已有本地包。
                credentials: {}
                groups:
                  hr:
                    title: 人事
                  admin:
                    title: 管理
                actions:
                  safe_op:
                    group: hr
                    title: 人事查询
                    description: 只读查询。
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {message: safe-%s}
                    output:
                      message: {type: string, description: 文本}
                    examples:
                      - input: {}
                        output: {message: safe-%s}
                    notes: 无
                    related: []
                  admin_op:
                    group: admin
                    title: 管理操作
                    description: 仅管理员可调用。
                    idempotent: false
                    mcp: {expose: true, read_only: false, destructive: true}
                    steps:
                      - set: {message: admin-%s}
                    output:
                      message: {type: string, description: 文本}
                    examples:
                      - input: {}
                        output: {message: admin-%s}
                    notes: 无
                    related: []
                """.formatted(name, version, name, name, name, name);
    }

    private static String hostYaml(String version, String url) {
        return """
                conch: host_mcp
                version: %s
                driver: mcp
                min_crab_version: 1.1.0
                title: 上游
                description: 本地。
                credentials: {}
                upstream:
                  url: %s
                  transport: streamable_http
                  auth:
                    type: oauth
                    client_id: hermit
                actions:
                  search:
                    tool: search
                    title: 搜索
                    description: 查询。
                    input_schema: {type: object}
                """.formatted(version, url);
    }

    private static void insertToken(CrabRuntime runtime, String conch, String account) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO credentials (origin, conch, account, field, ciphertext, updated_at)
                     VALUES ('LOCAL', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                     """)) {
            statement.setString(1, conch);
            statement.setString(2, account);
            statement.setString(3, McpOAuthToken.FIELD);
            statement.setBytes(4, new byte[] {1, 2, 3, 4});
            statement.executeUpdate();
        }
    }

    @org.junit.jupiter.api.Test
    void authListShowsAuthenticatorsWithoutAccountsAndNarrowKeysSeeOnlyTheirOwn() throws Exception {
        String sso = """
                conch: NAME
                version: 1.2.0
                driver: auth
                min_crab_version: 1.13.0
                title: 统一登录
                description: SSO 登录
                credentials:
                  username: { type: text, label: 用户名, required: true }
                  password: { type: secret, label: 密码, required: true }
                auth:
                  origins: ["https://HOST.corp.example"]
                  steps:
                    - request:
                        method: POST
                        url: https://HOST.corp.example/login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}" }
                    - extract: { access_token: { jsonpath: "$.token" } }
                  alive_check:
                    request: { method: GET, url: https://HOST.corp.example/me }
                  exports:
                    access_token: { description: 访问令牌 }
                """;
        String app = """
                conch: NAME
                version: 1.0.0
                driver: http
                min_crab_version: 1.13.0
                base_url: https://HOST.corp.example
                title: 应用
                description: 用授权器登录的包。
                auth:
                  use: SSO
                actions:
                  read:
                    title: 读
                    description: 读一条数据。
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /data
                          headers: { Authorization: "Bearer {{auth.access_token}}" }
                      - extract: { echo: { jsonpath: "$.echo" } }
                    output:
                      echo: { type: string, description: 回显 }
                    examples:
                      - input: {}
                        output: { echo: x }
                    notes: 无
                    related: []
                """;
        try (Harness server = Harness.open(temp.resolve("auth-list"))) {
            server.runtime.registry().put(dev.hermit.common.parse.ConchParser.parse(sso.replace("NAME", "corp_sso").replace("HOST", "sso")));
            server.runtime.registry().put(dev.hermit.common.parse.ConchParser.parse(sso.replace("NAME", "other_sso").replace("HOST", "sso2")));
            server.runtime.registry().put(dev.hermit.common.parse.ConchParser.parse(
                    app.replace("NAME", "pkg_a").replace("HOST", "a").replace("SSO", "corp_sso")));
            server.runtime.registry().put(dev.hermit.common.parse.ConchParser.parse(
                    app.replace("NAME", "pkg_b").replace("HOST", "b").replace("SSO", "other_sso")));
            server.runtime.registry().put(dev.hermit.common.parse.ConchParser.parse(
                    app.replace("NAME", "pkg_local").replace("HOST", "c").replace("SSO", "other_sso")),
                    dev.hermit.crab.store.Origin.LOCAL);
            server.runtime.admin().createAccount("corp_sso", "zhangsan_secret_name", Actors.CONSOLE);
            String session = server.initialize(false);

            // 开发者模式关着时不可用。
            assertThat(toolNames(server.rpc(session, 2, "tools/list", null))).doesNotContain("hermit_dev");
            assertThat(toolError(server.devOverview(server.key.plaintext(), session)).path("code").asText())
                    .isEqualTo("NOT_CONFIGURED");
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            assertThat(toolNames(DevToolList.expand(
                    server.rpc(session, 3, "tools/list", null),
                    server.devOverview(server.key.plaintext(), session)))).contains("hermit_dev__auth_list");
            // 授权器不是可调用的工具。
            assertThat(server.rpc(session, 3, "tools/list", null).toString()).doesNotContain("corp_sso");

            JsonNode all = structured(server.call(session, "hermit_dev__auth_list", Map.of()));
            assertThat(all.path("total").asInt()).isEqualTo(2);
            JsonNode first = all.path("authenticators").get(0);
            assertThat(first.path("conch").asText()).isEqualTo("corp_sso");
            assertThat(first.path("exports").get(0).path("name").asText()).isEqualTo("access_token");
            assertThat(first.path("consumers").get(0).asText()).isEqualTo("pkg_a");
            assertThat(all.toString()).doesNotContain("zhangsan_secret_name");

            CreatedApiKey narrow = server.runtime.apiKeys().create(
                    "only-a", new ApiKeyScope(Set.of("pkg_a"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            String narrowSession = server.initialize(narrow.plaintext(), false);
            JsonNode mine = structured(server.rpc(narrow.plaintext(), narrowSession, 4, "tools/call",
                    Map.of("name", "hermit_dev__auth_list", "arguments", Map.of())));
            assertThat(mine.path("total").asInt()).isEqualTo(1);
            assertThat(mine.toString()).contains("corp_sso").doesNotContain("other_sso").doesNotContain("pkg_b");

            // 只点名了本地包的某个能力，不算能看到这个本地包，也就看不到它引用的授权器。
            CreatedApiKey actionOnly = server.runtime.apiKeys().create(
                    "only-local-read", new ApiKeyScope(Set.of(), Set.of("pkg_local.read"), false, false, Set.of(), true), Actors.CONSOLE);
            String actionSession = server.initialize(actionOnly.plaintext(), false);
            JsonNode none = structured(server.rpc(actionOnly.plaintext(), actionSession, 5, "tools/call",
                    Map.of("name", "hermit_dev__auth_list", "arguments", Map.of())));
            assertThat(none.path("total").asInt()).isZero();
            assertThat(none.toString()).doesNotContain("other_sso").doesNotContain("pkg_local");
        }
    }

    @org.junit.jupiter.api.Test
    void conchGuideReturnsQuickReferenceSectionsSubsectionsSearchAndRejectsBadInput() throws Exception {
        CrabConfig directoryConfig = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(0);
        try (Harness server = Harness.open(temp.resolve("guide-tool"), directoryConfig)) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            CreatedApiKey readOnly = server.runtime.apiKeys().create(
                    "ro", new ApiKeyScope(Set.of(), Set.of(), true, true, Set.of(), true), Actors.CONSOLE);
            String session = server.initialize(readOnly.plaintext(), false);

            // 目录模式下、只读 Key 在 hermit_dev 包里看得到并能调用 hermit_dev__guide，看不到 hermit_dev__save_local
            JsonNode tools = server.rpc(readOnly.plaintext(), session, 2, "tools/list", null);
            assertThat(toolNames(tools)).contains("list_systems").noneMatch(name -> name.startsWith("hermit_dev__"));
            JsonNode devList = server.rpc(readOnly.plaintext(), session, 20, "tools/call",
                    Map.of("name", "search_capabilities", "arguments", Map.of("conch", "hermit_dev", "ungrouped", true)));
            assertThat(structured(devList).path("matches").findValuesAsText("name"))
                    .contains("hermit_dev__guide", "hermit_dev__read_yaml", "hermit_dev__validate")
                    .doesNotContain("hermit_dev__save_local");

            JsonNode overview = server.rpc(readOnly.plaintext(), session, 3, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of()));
            assertThat(overview.path("result").path("isError").asBoolean()).isFalse();
            JsonNode overData = structured(overview);
            assertThat(overData.path("crabVersion").asText()).isEqualTo(dev.hermit.crab.engine.CrabVersion.CURRENT.toString());
            assertThat(overData.path("quickReference").asText()).contains("- if:").contains("- repeat:");
            assertThat(overData.path("starterTemplate").asText())
                    .contains("conch: my_system")
                    .contains("min_crab_version: 1.14.0")
                    .contains("related: []");
            assertThat(overData.path("pitfalls").isArray()).isTrue();
            assertThat(overData.path("pitfalls").size()).isGreaterThanOrEqualTo(8);
            assertThat(overData.path("sections").isArray()).isTrue();
            assertThat(overData.path("sections").size()).isGreaterThanOrEqualTo(10);

            JsonNode starterSec = server.rpc(readOnly.plaintext(), session, 31, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("section", "starter")));
            assertThat(structured(starterSec).path("id").asText()).isEqualTo("starter");
            assertThat(structured(starterSec).path("content").asText()).contains("conch: my_system").contains("❌");

            JsonNode byId = server.rpc(readOnly.plaintext(), session, 4, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("section", "session_expiry")));
            assertThat(structured(byId).path("id").asText()).isEqualTo("session_expiry");
            assertThat(structured(byId).path("content").asText()).contains("expired_when").doesNotContain("](");

            JsonNode bySub = server.rpc(readOnly.plaintext(), session, 5, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("section", "上传和下载文件")));
            assertThat(structured(bySub).path("parentId").asText()).isEqualTo("steps");
            assertThat(structured(bySub).path("content").asText()).contains("multipart").contains("download");

            JsonNode full = server.rpc(readOnly.plaintext(), session, 6, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("section", "all")));
            assertThat(structured(full).path("content").asText()).isEqualTo(AuthoringGuide.text());

            JsonNode search = server.rpc(readOnly.plaintext(), session, 7, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("query", "expired_when")));
            assertThat(structured(search).path("total").asInt()).isPositive();
            assertThat(structured(search).path("matches").get(0).path("id").asText()).isEqualTo("session_expiry");

            JsonNode both = server.rpc(readOnly.plaintext(), session, 8, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("section", "steps", "query", "repeat")));
            assertThat(toolError(both).path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(toolError(both).path("field").asText()).isEqualTo("section");

            JsonNode missing = server.rpc(readOnly.plaintext(), session, 9, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("section", "unknown_chapter")));
            assertThat(toolError(missing).path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(toolError(missing).path("field").asText()).isEqualTo("section");

            JsonNode unknownArg = server.rpc(readOnly.plaintext(), session, 10, "tools/call",
                    Map.of("name", "hermit_dev__guide", "arguments", Map.of("extra", "1")));
            assertThat(toolError(unknownArg).path("code").asText()).isEqualTo("INVALID_INPUT");
        }
    }

    @org.junit.jupiter.api.Test
    void validateAndSaveFailuresIncludeActionableFixHintsAndSuggestedSections() throws Exception {
        try (Harness server = Harness.open(temp.resolve("fix-hints"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            String broken = """
                    conch: weak_llm_oa
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: http://127.0.0.1:8080
                    title: 示例
                    description: 故意包含常见写错
                    actions:
                      list_todo:
                        title: 查询
                        description: 列表
                        steps:
                          - if:
                              present: token
                              then:
                                - request: {method: GET, path: /api/todos, headers: {X-Tok: "{{token}}"}}
                        output:
                          ok: {type: string, description: 结果, value: ok}
                    """;
            for (String toolName : List.of("hermit_dev__validate", "hermit_dev__save_local")) {
                JsonNode resp = server.call(session, toolName, Map.of("yaml", broken));
                assertThat(resp.path("result").path("isError").asBoolean(true)).isFalse();
                JsonNode body = structured(resp);
                assertThat(body.path("ok").asBoolean()).isFalse();
                assertThat(body.path("fixHints").isArray()).isTrue();
                assertThat(body.path("fixHints").toString())
                        .contains("1.14.0")
                        .contains("credentials: {}")
                        .contains("examples")
                        .contains("{{login.xxx}}");
                assertThat(body.path("suggestedSections").isArray()).isTrue();
                assertThat(body.path("suggestedSections").size()).isGreaterThanOrEqualTo(2);
                for (JsonNode sec : body.path("suggestedSections")) {
                    JsonNode guide = server.call(session, "hermit_dev__guide", Map.of("section", sec.asText()));
                    assertThat(guide.path("result").path("isError").asBoolean(true))
                            .as("suggested section %s should be readable via hermit_dev__guide", sec.asText())
                            .isFalse();
                }
                assertThat(body.path("next").asText()).contains("fixHints").contains("hermit_dev__validate");
            }

            JsonNode okResp = server.call(session, "hermit_dev__validate",
                    Map.of("yaml", AuthoringGuide.starterTemplate()));
            JsonNode okBody = structured(okResp);
            assertThat(okBody.path("ok").asBoolean()).isTrue();
            assertThat(okBody.path("errors").isEmpty()).isTrue();
            assertThat(okBody.path("warnings").isEmpty()).isTrue();
            assertThat(okBody.has("fixHints")).isFalse();
            assertThat(okBody.path("next").asText()).contains("hermit_dev__save_local");
        }
    }

    @org.junit.jupiter.api.Test
    void readYamlSupportsLocalAndVerifiedReefSubscriptionAndKeepsReefStrictlyReadOnly() throws Exception {
        java.security.KeyPair reefKeys = dev.hermit.common.pkg.Ed25519Keys.generate();
        String reefPubPem = dev.hermit.common.pkg.Ed25519Keys.writePublicPem(reefKeys.getPublic());
        Path home = temp.resolve("read-reef");
        CrabPaths paths = new CrabPaths(home);
        String reefYaml = LocalConchBehaviorTest.ping("corp_oa", "1.0.0");
        byte[] signedZip = dev.hermit.common.pkg.ConchPackage.write(
                reefYaml.getBytes(StandardCharsets.UTF_8), reefKeys.getPrivate());
        Path zipFile = paths.conches().resolve("corp_oa").resolve("1.0.0").resolve("package.zip");
        Files.createDirectories(zipFile.getParent());
        Files.write(zipFile, signedZip);
        Files.writeString(paths.conches().resolve("corp_oa").resolve("current"), "1.0.0\n", StandardCharsets.UTF_8);

        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(40).withReef(null, null, reefPubPem);
        try (Harness server = Harness.open(home, config)) {
            server.runtime.loadInstalledConches();
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);

            // 1. 默认读取已订阅的 Reef 正式包：只读、验签、原文完整
            JsonNode readReef = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa"));
            assertThat(readReef.path("result").path("isError").asBoolean()).as(readReef.toString()).isFalse();
            JsonNode reefData = structured(readReef);
            assertThat(reefData.path("conch").asText()).isEqualTo("corp_oa");
            assertThat(reefData.path("source").asText()).isEqualTo("reef");
            assertThat(reefData.path("readOnly").asBoolean()).isTrue();
            assertThat(reefData.path("active").asBoolean()).isTrue();
            assertThat(reefData.path("hasReef").asBoolean()).isTrue();
            assertThat(reefData.path("hasLocal").asBoolean()).isFalse();
            assertThat(reefData.path("version").asText()).isEqualTo("1.0.0");
            assertThat(reefData.path("yaml").asText()).isEqualTo(reefYaml);
            assertThat(reefData.path("sha256").asText()).hasSize(64);
            assertThat(reefData.path("note").asText()).contains("只读不可直接修改").contains("hermit_dev__save_local");

            // 2. 篡改磁盘 zip 正文字节后重新读取：验签失败，绝不返回被篡改内容
            byte[] tampered = signedZip.clone();
            tampered[tampered.length / 2] ^= 0x5A;
            Files.write(zipFile, tampered);
            JsonNode tamperedRead = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa", "source", "reef"));
            assertThat(toolError(tamperedRead).path("code").asText()).as(tamperedRead.toString()).isEqualTo("NOT_FOUND");
            Files.write(zipFile, signedZip);

            // 3. 基于读出的 Reef YAML 起新包名保存到本地：新本地包立即生效，原 Reef 正式包不受任何影响
            String forkedYaml = reefYaml.replace("conch: corp_oa", "conch: corp_oa_custom")
                    .replace("version: 1.0.0", "version: 1.0.1");
            JsonNode savedFork = server.call(session, "hermit_dev__save_local", Map.of("yaml", forkedYaml));
            assertThat(structured(savedFork).path("ok").asBoolean()).isTrue();
            assertThat(structured(savedFork).path("active").asBoolean()).isTrue();
            JsonNode readFork = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa_custom"));
            assertThat(structured(readFork).path("source").asText()).isEqualTo("local");
            assertThat(structured(readFork).path("readOnly").asBoolean()).isFalse();
            assertThat(structured(readFork).path("active").asBoolean()).isTrue();
            assertThat(structured(readFork).path("hasReef").asBoolean()).isFalse();
            assertThat(structured(readFork).path("yaml").asText()).isEqualTo(forkedYaml);

            // 4. 基于读出的 Reef YAML 保存为同名本地副本：原 Reef 正式包保持生效且文件字节未变
            String shadowYaml = reefYaml.replace("version: 1.0.0", "version: 1.0.2");
            JsonNode savedShadow = server.call(session, "hermit_dev__save_local", Map.of("yaml", shadowYaml));
            assertThat(structured(savedShadow).path("ok").asBoolean()).isTrue();
            assertThat(structured(savedShadow).path("replaced").asBoolean()).isFalse();
            assertThat(structured(savedShadow).path("active").asBoolean()).isFalse();
            assertThat(structured(savedShadow).path("next").asText()).isEqualTo(AuthoringCalls.NEXT_SWITCH_SOURCE);
            assertThat(Files.readAllBytes(zipFile)).isEqualTo(signedZip);
            assertThat(server.runtime.registry().origin("corp_oa")).isEqualTo(dev.hermit.crab.store.Origin.OFFICIAL);

            // 默认仍读当前生效的 Reef 正式包，但 hasLocal=true 提示已有同名本地副本
            JsonNode defaultDual = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa"));
            assertThat(structured(defaultDual).path("source").asText()).isEqualTo("reef");
            assertThat(structured(defaultDual).path("active").asBoolean()).isTrue();
            assertThat(structured(defaultDual).path("hasLocal").asBoolean()).isTrue();
            assertThat(structured(defaultDual).path("yaml").asText()).isEqualTo(reefYaml);

            // 指定 source=local 可读出尚未切换生效的同名本地副本
            JsonNode localDual = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa", "source", "local"));
            assertThat(structured(localDual).path("source").asText()).isEqualTo("local");
            assertThat(structured(localDual).path("readOnly").asBoolean()).isFalse();
            assertThat(structured(localDual).path("active").asBoolean()).isFalse();
            assertThat(structured(localDual).path("hasReef").asBoolean()).isTrue();
            assertThat(structured(localDual).path("yaml").asText()).isEqualTo(shadowYaml);
            assertThat(structured(localDual).path("note").asText()).contains("切换为本地规则包");

            // 控制台切换来源为 LOCAL 后：默认读本地副本，显式 source=reef 仍读到原封不动的 Reef 正式包
            assertThat(server.runtime.registry().activate("corp_oa", dev.hermit.crab.store.Origin.LOCAL)).isTrue();
            JsonNode activeLocal = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa"));
            assertThat(structured(activeLocal).path("source").asText()).isEqualTo("local");
            assertThat(structured(activeLocal).path("active").asBoolean()).isTrue();
            assertThat(structured(activeLocal).path("yaml").asText()).isEqualTo(shadowYaml);

            JsonNode inactiveReef = server.call(session, "hermit_dev__read_yaml", Map.of("conch", "corp_oa", "source", "reef"));
            assertThat(structured(inactiveReef).path("source").asText()).isEqualTo("reef");
            assertThat(structured(inactiveReef).path("readOnly").asBoolean()).isTrue();
            assertThat(structured(inactiveReef).path("active").asBoolean()).isFalse();
            assertThat(structured(inactiveReef).path("yaml").asText()).isEqualTo(reefYaml);
        }
    }

    @org.junit.jupiter.api.Test
    void readYamlEnforcesWholePackageScopeAndDoesNotLeakExistence() throws Exception {
        java.security.KeyPair reefKeys = dev.hermit.common.pkg.Ed25519Keys.generate();
        String reefPubPem = dev.hermit.common.pkg.Ed25519Keys.writePublicPem(reefKeys.getPublic());
        Path home = temp.resolve("read-scope");
        CrabPaths paths = new CrabPaths(home);
        String reefYaml = LocalConchBehaviorTest.ping("corp_oa", "1.0.0");
        byte[] signedZip = dev.hermit.common.pkg.ConchPackage.write(
                reefYaml.getBytes(StandardCharsets.UTF_8), reefKeys.getPrivate());
        Path zipFile = paths.conches().resolve("corp_oa").resolve("1.0.0").resolve("package.zip");
        Files.createDirectories(zipFile.getParent());
        Files.write(zipFile, signedZip);
        Files.writeString(paths.conches().resolve("corp_oa").resolve("current"), "1.0.0\n", StandardCharsets.UTF_8);

        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(40).withReef(null, null, reefPubPem);
        try (Harness server = Harness.open(home, config)) {
            server.runtime.loadInstalledConches();
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);

            CreatedApiKey namedFull = server.runtime.apiKeys().create(
                    "named-full", new ApiKeyScope(Set.of("corp_oa"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            CreatedApiKey namedReadOnly = server.runtime.apiKeys().create(
                    "named-ro", new ApiKeyScope(Set.of("corp_oa"), Set.of(), true, false, Set.of(), true), Actors.CONSOLE);
            CreatedApiKey otherKey = server.runtime.apiKeys().create(
                    "other", new ApiKeyScope(Set.of("other_pkg"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            CreatedApiKey groupKey = server.runtime.apiKeys().create(
                    "group", new ApiKeyScope(Set.of("corp_oa"), Set.of(), false, false, Set.of("corp_oa:hr"), true), Actors.CONSOLE);
            CreatedApiKey actionKey = server.runtime.apiKeys().create(
                    "action", new ApiKeyScope(Set.of(), Set.of("corp_oa.ping"), false, false, Set.of(), true), Actors.CONSOLE);

            String namedSession = server.initialize(namedFull.plaintext(), false);
            assertThat(structured(server.rpc(namedFull.plaintext(), namedSession, 2, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "corp_oa"))))
                    .path("yaml").asText()).isEqualTo(reefYaml);

            String roSession = server.initialize(namedReadOnly.plaintext(), false);
            assertThat(structured(server.rpc(namedReadOnly.plaintext(), roSession, 2, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "corp_oa"))))
                    .path("yaml").asText()).isEqualTo(reefYaml);

            // 未授权 Key 读存在的包和不存在的包都直接 FORBIDDEN，不泄露存在性
            String otherSession = server.initialize(otherKey.plaintext(), false);
            JsonNode deniedExisting = server.rpc(otherKey.plaintext(), otherSession, 2, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "corp_oa")));
            JsonNode deniedGhost = server.rpc(otherKey.plaintext(), otherSession, 3, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "ghost_pkg")));
            assertThat(toolError(deniedExisting).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolError(deniedGhost).path("code").asText()).isEqualTo("FORBIDDEN");

            // 限定分组或能力的 Key 不能读完整规则包 YAML
            String groupSession = server.initialize(groupKey.plaintext(), false);
            JsonNode deniedGroup = server.rpc(groupKey.plaintext(), groupSession, 2, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "corp_oa")));
            assertThat(toolError(deniedGroup).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(deniedGroup)).contains("限定了具体分组或能力");

            String actionSession = server.initialize(actionKey.plaintext(), false);
            JsonNode deniedAction = server.rpc(actionKey.plaintext(), actionSession, 2, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "corp_oa")));
            assertThat(toolError(deniedAction).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(deniedAction)).contains("限定了具体分组或能力");

            // 非法包名、非法 source 与未知字段校验
            String fullSession = server.initialize(false);
            assertThat(toolError(server.call(fullSession, "hermit_dev__read_yaml", Map.of("conch", "../etc")))
                    .path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(toolError(server.call(fullSession, "hermit_dev__read_yaml", Map.of("conch", "corp_oa", "source", "other")))
                    .path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(toolError(server.call(fullSession, "hermit_dev__read_yaml", Map.of("conch", "corp_oa", "extra", true)))
                    .path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(toolError(server.call(fullSession, "hermit_dev__read_yaml", Map.of("conch", "missing_pkg")))
                    .path("code").asText()).isEqualTo("NOT_FOUND");
        }
    }

    @org.junit.jupiter.api.Test
    void developerModeSupportsFullAuthoringLoopsAcrossAllThreeDrivers() throws Exception {
        String ssoSecret = "S3cr3t-sso-pass";
        String ssoToken = "sso-export-tok-8842";
        String mcpSecret = "mcp-bearer-tok-7711";
        java.util.concurrent.atomic.AtomicBoolean probeOk = new java.util.concurrent.atomic.AtomicBoolean(false);

        HttpServer sso = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sso.createContext("/login", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains(ssoSecret)) {
                byte[] resp = "{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, resp.length);
                exchange.getResponseBody().write(resp);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "SSO_SID=sid-ok-1; Path=/");
            byte[] resp = ("{\"token\":\"" + ssoToken + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        sso.createContext("/me", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean ok = probeOk.get()
                    && ("Bearer " + ssoToken).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 401, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        sso.start();

        HttpServer app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/exchange", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean ok = ("Bearer " + ssoToken).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            if (ok) {
                exchange.getResponseHeaders().add("Set-Cookie", "APP_SID=app-ok-1; Path=/");
            }
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 401, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        app.createContext("/items", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean ok = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")).contains("APP_SID=app-ok-1");
            byte[] resp = "{\"item\":\"approved-doc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 401, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        app.start();

        HttpServer mcpUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpUpstream.createContext("/mcp", exchange -> {
            String rawReq = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!("Bearer " + mcpSecret).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            JsonNode rpc = ApiJson.tree(rawReq);
            String method = rpc.path("method").asText("");
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String replyJson;
            if ("initialize".equals(method)) {
                replyJson = """
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"demo-mcp","version":"1.0.0"}}}
                        """.formatted(rpc.get("id"));
            } else if ("tools/list".equals(method)) {
                replyJson = """
                        {"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"pingTool","title":"探测工具","description":"返回固定回声。","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"annotations":{"readOnlyHint":true,"destructiveHint":false}}]}}
                        """.formatted(rpc.get("id"));
            } else {
                String q = rpc.path("params").path("arguments").path("q").asText("none");
                replyJson = """
                        {"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"pong:%s"}],"structuredContent":{"reply":"pong:%s"}}}
                        """.formatted(rpc.get("id"), q, q);
            }
            byte[] resp = replyJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "up-sess-1");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        mcpUpstream.start();

        String ssoUrl = "http://127.0.0.1:" + sso.getAddress().getPort();
        String appUrl = "http://127.0.0.1:" + app.getAddress().getPort();
        String mcpUrl = "http://127.0.0.1:" + mcpUpstream.getAddress().getPort() + "/mcp";

        try (Harness server = Harness.open(temp.resolve("all-drivers"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);

            // 0. 指南概览包含多驱动起始模板索引，且可按 section 读出各驱动骨架
            JsonNode guideOverview = structured(server.call(session, "hermit_dev__guide", Map.of()));
            assertThat(guideOverview.path("starterSections")).hasSize(4);
            for (String sec : List.of("starter_auth", "starter_auth_use", "starter_mcp")) {
                JsonNode matched = structured(server.call(session, "hermit_dev__guide", Map.of("section", sec)));
                assertThat(matched.path("id").asText()).isEqualTo(sec);
                assertThat(matched.path("content").asText()).contains("conch:");
            }

            // 1. driver: auth —— 限定范围的 Key 不能新建或读写授权器；全范围 Key 可保存并用 hermit_dev__test_login 验收探活与导出变量
            String authYaml = """
                    conch: local_sso
                    version: 1.0.0
                    driver: auth
                    min_crab_version: 1.13.0
                    title: 本地统一认证
                    description: 本地 SSO 登录授权器。
                    credentials:
                      password: { type: secret, label: 密码, required: true }
                    auth:
                      origins: ["%s"]
                      steps:
                        - request:
                            method: POST
                            url: %s/login
                            json: { password: "{{credential.password}}" }
                        - assert: { status: 200 }
                        - extract: { access_token: { jsonpath: "$.token" } }
                      alive_check:
                        request:
                          method: GET
                          url: %s/me
                          headers: { Authorization: "Bearer {{login.access_token}}" }
                        expect: { status: 200 }
                      exports:
                        access_token: { description: 访问令牌 }
                    """.formatted(ssoUrl, ssoUrl, ssoUrl);

            CreatedApiKey scopedKey = server.runtime.apiKeys().create(
                    "scoped-consumer", new ApiKeyScope(Set.of("local_consumer"), Set.of(), false, false, Set.of(), true), Actors.CONSOLE);
            String scopedSession = server.initialize(scopedKey.plaintext(), false);
            JsonNode scopedCreateAuth = server.rpc(scopedKey.plaintext(), scopedSession, 10, "tools/call",
                    Map.of("name", "hermit_dev__save_local", "arguments", Map.of("yaml", authYaml)));
            assertThat(toolError(scopedCreateAuth).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(scopedCreateAuth)).isEqualTo(AuthoringCalls.AUTH_SCOPE_DENIED);

            // 在安装授权器前先校验引用它的业务包：ok=true 且给出缺少授权器警告
            String consumerYaml = """
                    conch: local_consumer
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.13.0
                    base_url: %s
                    title: 本地协同业务
                    description: 引用本地授权器的业务包。
                    auth:
                      use: local_sso
                      exchange:
                        - request:
                            method: POST
                            path: /exchange
                            headers: { Authorization: "Bearer {{auth.access_token}}" }
                        - assert: { status: 200 }
                    actions:
                      fetch_item:
                        title: 查询条目
                        description: 读取当前登录账号的业务条目。
                        method: GET
                        idempotent: true
                        mcp: { expose: true, read_only: true }
                        steps:
                          - request: { method: GET, path: /items }
                          - assert: { status: 200 }
                          - extract: { item: { jsonpath: "$.item" } }
                        output:
                          item: { type: string, description: 条目 }
                        examples:
                          - input: {}
                            output: { item: demo }
                        notes: 只读查询。
                        related: []
                    """.formatted(appUrl);
            JsonNode preCheck = structured(server.call(session, "hermit_dev__validate", Map.of("yaml", consumerYaml)));
            assertThat(preCheck.path("ok").asBoolean()).isTrue();
            assertThat(preCheck.path("warnings").toString()).contains("local_sso");

            JsonNode savedAuth = structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", authYaml)));
            assertThat(savedAuth.path("ok").asBoolean()).isTrue();
            assertThat(savedAuth.path("next").asText()).contains("hermit_dev__test_login");

            // 授权器安装后，校验引用不存在导出变量的业务包会给出导出名不匹配告警
            String badExportConsumer = consumerYaml.replace("{{auth.access_token}}", "{{auth.missing_export}}");
            JsonNode linkWarn = structured(server.call(session, "hermit_dev__validate", Map.of("yaml", badExportConsumer)));
            assertThat(linkWarn.path("ok").asBoolean()).isTrue();
            assertThat(linkWarn.path("warnings").toString()).contains("missing_export");

            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "local_sso", "admin", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    dev.hermit.crab.store.Origin.LOCAL,
                    server.runtime.registry().authenticator("local_sso").orElseThrow().doc(),
                    "admin",
                    Map.of("password", ssoSecret),
                    Actors.CONSOLE);

            // 探活未通过时，hermit_dev__test_login 返回 AUTH_FAILED 且带可追踪的 traceId
            JsonNode failedProbe = server.call(session, "hermit_dev__test_login", Map.of("conch", "local_sso"));
            assertThat(toolError(failedProbe).path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(toolMessage(failedProbe)).contains("alive_check");
            String failedTraceId = toolError(failedProbe).path("traceId").asText();
            assertThat(failedTraceId).isNotBlank();
            JsonNode probeTrace = structured(server.call(session, "hermit_dev__debug_trace", Map.of("traceId", failedTraceId)));
            assertThat(probeTrace.path("action").asText()).isEqualTo("test_login");
            assertThat(probeTrace.toString()).doesNotContain(ssoSecret).doesNotContain(ssoToken);

            // 探活恢复后，hermit_dev__test_login 验证登录、探活与导出变量名（不回显秘密值）
            probeOk.set(true);
            JsonNode okLogin = structured(server.call(session, "hermit_dev__test_login", Map.of("conch", "local_sso")));
            assertThat(okLogin.path("ok").asBoolean()).isTrue();
            assertThat(okLogin.path("aliveCheckPassed").asBoolean()).isTrue();
            assertThat(okLogin.path("exported")).extracting(JsonNode::asText).containsExactly("access_token");
            assertThat(okLogin.toString()).doesNotContain(ssoSecret).doesNotContain(ssoToken);

            // 2. auth.use 业务包 —— 保存、未绑定前精确诊断、绑定后测换票与试跑、窄范围 Key 对可见授权器的清晰拒绝
            JsonNode savedConsumer = structured(server.rpc(scopedKey.plaintext(), scopedSession, 11, "tools/call",
                    Map.of("name", "hermit_dev__save_local", "arguments", Map.of("yaml", consumerYaml))));
            assertThat(savedConsumer.path("ok").asBoolean()).isTrue();
            assertThat(savedConsumer.path("next").asText()).contains("绑定授权器账号");

            // 窄范围 Key 现在通过 local_consumer 看得到 local_sso，读/改/测 local_sso 均给出 AUTH_SCOPE_DENIED，而读无关包仍给普通提示
            for (Map<String, Object> call : List.of(
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "local_sso")),
                    Map.of("name", "hermit_dev__test_login", "arguments", Map.of("conch", "local_sso")))) {
                JsonNode denied = server.rpc(scopedKey.plaintext(), scopedSession, 12, "tools/call", call);
                assertThat(toolError(denied).path("code").asText()).isEqualTo("FORBIDDEN");
                assertThat(toolMessage(denied)).isEqualTo(AuthoringCalls.AUTH_SCOPE_DENIED);
            }
            JsonNode ghostRead = server.rpc(scopedKey.plaintext(), scopedSession, 13, "tools/call",
                    Map.of("name", "hermit_dev__read_yaml", "arguments", Map.of("conch", "unreferenced_other")));
            assertThat(toolMessage(ghostRead)).doesNotContain("授权器");

            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "local_consumer", "alice", Actors.CONSOLE);
            JsonNode unboundTrial = server.rpc(scopedKey.plaintext(), scopedSession, 14, "tools/call",
                    Map.of("name", "hermit_dev__trial_run", "arguments", Map.of("conch", "local_consumer", "action", "fetch_item")));
            assertThat(toolError(unboundTrial).path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(toolMessage(unboundTrial)).contains("还没有绑定授权器账号");
            assertThat(toolError(unboundTrial).has("traceId")).isFalse();

            HttpResponse<String> bindResp = server.http.send(HttpRequest.newBuilder(
                    URI.create(server.base + "/console/api/conches/local_consumer/accounts/alice/authenticator"))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"authAccount\":\"admin\",\"baseUrl\":\"" + appUrl + "\",\"confirm\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(bindResp.statusCode()).as(bindResp.body()).isEqualTo(200);

            JsonNode exchangeOk = structured(server.rpc(scopedKey.plaintext(), scopedSession, 15, "tools/call",
                    Map.of("name", "hermit_dev__test_login", "arguments", Map.of("conch", "local_consumer"))));
            assertThat(exchangeOk.path("ok").asBoolean()).isTrue();
            JsonNode consumerTrial = structured(server.rpc(scopedKey.plaintext(), scopedSession, 16, "tools/call",
                    Map.of("name", "hermit_dev__trial_run", "arguments", Map.of("conch", "local_consumer", "action", "fetch_item"))));
            assertThat(consumerTrial.path("ok").asBoolean()).isTrue();
            assertThat(consumerTrial.path("data").path("item").asText()).isEqualTo("approved-doc");

            // 同边界覆盖绑定到本地授权器的业务包：直接保存且保留 auth_bindings，无需重新绑定即可继续试跑
            JsonNode sameBound = structured(server.rpc(scopedKey.plaintext(), scopedSession, 17, "tools/call",
                    Map.of("name", "hermit_dev__save_local", "arguments",
                            Map.of("yaml", consumerYaml.replace("version: 1.0.0", "version: 1.0.1")))));
            assertThat(sameBound.path("ok").asBoolean()).isTrue();
            JsonNode afterSameBoundTrial = structured(server.rpc(scopedKey.plaintext(), scopedSession, 18, "tools/call",
                    Map.of("name", "hermit_dev__trial_run", "arguments", Map.of("conch", "local_consumer", "action", "fetch_item"))));
            assertThat(afterSameBoundTrial.path("ok").asBoolean()).isTrue();

            // 扩大目的地覆盖已绑定授权器的本地业务包时，确认文案明确说“授权器绑定”而非“共享身份”
            JsonNode overwriteBound = server.rpc(scopedKey.plaintext(), scopedSession, 19, "tools/call",
                    Map.of("name", "hermit_dev__save_local", "arguments",
                            Map.of("yaml", consumerYaml
                                    .replace("version: 1.0.0", "version: 1.0.2")
                                    .replace(appUrl, "http://127.0.0.1:9"))));
            assertThat(toolError(overwriteBound).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");
            assertThat(toolMessage(overwriteBound)).contains("授权器绑定").doesNotContain("共享身份");

            // 3. driver: mcp —— 空骨架保存提示、拒绝 test_login、hermit_dev__mcp_refresh 拉取快照并试跑
            String mcpYaml = """
                    conch: local_mcp
                    version: 1.0.0
                    driver: mcp
                    min_crab_version: 1.13.0
                    title: 本地 MCP 接入
                    description: 连接本地测试 MCP 上游并拉取工具快照。
                    credentials:
                      api_token: { type: secret, label: 令牌, required: true }
                    upstream:
                      url: %s
                      transport: streamable_http
                      auth:
                        type: headers
                      headers:
                        Authorization: "Bearer {{credential.api_token}}"
                    actions: {}
                    """.formatted(mcpUrl);
            JsonNode savedMcp = structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", mcpYaml)));
            assertThat(savedMcp.path("ok").asBoolean()).isTrue();
            assertThat(savedMcp.path("next").asText()).contains("hermit_dev__mcp_refresh");

            String oauthSkeleton = """
                    conch: local_oauth_mcp
                    version: 1.0.0
                    driver: mcp
                    min_crab_version: 1.13.0
                    title: 本地 OAuth MCP
                    description: OAuth 鉴权的上游 MCP 骨架。
                    credentials: {}
                    upstream:
                      url: %s
                      transport: streamable_http
                      auth:
                        type: oauth
                        client_id: demo-client
                    actions: {}
                    """.formatted(mcpUrl);
            JsonNode savedOauthMcp = structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", oauthSkeleton)));
            assertThat(savedOauthMcp.path("ok").asBoolean()).isTrue();
            assertThat(savedOauthMcp.path("next").asText()).contains("连接上游").doesNotContain("填写凭证");

            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "local_mcp", "bot", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    dev.hermit.crab.store.Origin.LOCAL,
                    server.runtime.registry().find("local_mcp", dev.hermit.crab.store.Origin.LOCAL).orElseThrow(),
                    "bot",
                    Map.of("api_token", mcpSecret),
                    Actors.CONSOLE);

            JsonNode mcpLoginRejected = server.call(session, "hermit_dev__test_login", Map.of("conch", "local_mcp"));
            assertThat(toolError(mcpLoginRejected).path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(toolMessage(mcpLoginRejected)).contains("MCP 规则包没有登录步骤").doesNotContain("base_url");

            JsonNode refreshed = structured(server.call(session, "hermit_dev__mcp_refresh", Map.of("conch", "local_mcp")));
            assertThat(refreshed.path("ok").asBoolean()).isTrue();
            assertThat(refreshed.path("toolCount").asInt()).isEqualTo(1);
            assertThat(refreshed.path("added")).extracting(JsonNode::asText).containsExactly("pingtool");
            assertThat(refreshed.path("traceId").asText()).isNotBlank();
            assertThat(refreshed.path("next").asText())
                    .contains("hermit_dev__read_yaml")
                    .contains("hermit_dev__mcp_configure");

            // 新刷出来的工具默认保守需要确认，不支持 elicitation 的会话直接试跑会被拦下
            JsonNode unreviewedTrial = server.call(session, "hermit_dev__trial_run",
                    Map.of("conch", "local_mcp", "action", "pingtool", "input", Map.of("q", "hello")));
            assertThat(toolMessage(unreviewedTrial)).isEqualTo(McpCalls.NO_CONFIRM);

            // 使用 omitSchemas=true 轻量读取不含膨胀 input_schema，再用 hermit_dev__mcp_configure 原地配置只读与确认标志
            JsonNode compactRead = structured(server.call(session, "hermit_dev__read_yaml",
                    Map.of("conch", "local_mcp", "omitSchemas", true)));
            assertThat(compactRead.path("schemasOmitted").asBoolean()).isTrue();
            assertThat(compactRead.path("yaml").asText()).doesNotContain("input_schema:");

            JsonNode configured = structured(server.call(session, "hermit_dev__mcp_configure",
                    Map.of(
                            "conch", "local_mcp",
                            "groups", Map.of("diag", Map.of("title", "诊断")),
                            "defaults", Map.of("read_only", true, "destructive", false, "confirm", false),
                            "actions", Map.of("pingtool", Map.of("title", "连通性探测", "group", "diag")))));
            assertThat(configured.path("ok").asBoolean()).as(configured.toString()).isTrue();
            assertThat(configured.path("replaced").asBoolean()).isTrue();
            String fullAfterConfigure = structured(server.call(session, "hermit_dev__read_yaml", Map.of("conch", "local_mcp")))
                    .path("yaml").asText();
            assertThat(fullAfterConfigure).contains("input_schema:").contains("schema_sha256:").contains("group: diag");

            JsonNode mcpTrial = structured(server.call(session, "hermit_dev__trial_run",
                    Map.of("conch", "local_mcp", "action", "pingtool", "input", Map.of("q", "hello"))));
            assertThat(mcpTrial.path("ok").asBoolean()).as(mcpTrial.toString()).isTrue();
            assertThat(mcpTrial.path("data").toString()).contains("pong:hello").doesNotContain(mcpSecret);
        } finally {
            sso.stop(0);
            app.stop(0);
            mcpUpstream.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void authoring114OfficialAuthGrantDryRunMultiOriginAndFileStageWorkEndToEnd() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> uploadedPayload = new java.util.concurrent.atomic.AtomicReference<>("");
        com.sun.net.httpserver.HttpServer sso = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        sso.createContext("/login", exchange -> {
            byte[] resp = "{\"token\":\"OFF-TOK-9999\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        sso.createContext("/me", exchange -> {
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        sso.start();

        com.sun.net.httpserver.HttpServer app = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/api/upload", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            uploadedPayload.set(body);
            int code = "Bearer OFF-TOK-9999".equals(auth) ? 200 : 401;
            byte[] resp = "{\"uploaded\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        app.start();

        String ssoUrl = "http://127.0.0.1:" + sso.getAddress().getPort();
        String appUrl = "http://127.0.0.1:" + app.getAddress().getPort();
        try (Harness server = Harness.open(temp.resolve("authoring-114"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            CreatedApiKey readOnly = server.runtime.apiKeys().create(
                    "ro-dev", new ApiKeyScope(Set.of(), Set.of(), true, true, Set.of(), true), Actors.CONSOLE);
            String roSession = server.initialize(readOnly.plaintext(), false);

            // 1. 绑定到 OFFICIAL 正式授权器的本地业务包：首次同边界覆盖需确认（保留绑定），remember=true 后再次同边界保存直接放行且绑定仍在
            String officialAuthYaml = """
                    conch: official_sso
                    version: 1.0.0
                    driver: auth
                    min_crab_version: 1.14.0
                    title: 正式统一登录
                    description: 正式授权器
                    credentials:
                      password: { type: secret, label: 口令, required: true }
                    auth:
                      origins: ["%s"]
                      steps:
                        - request:
                            method: POST
                            url: %s/login
                            json: { password: "{{credential.password}}" }
                        - extract:
                            access_token: { jsonpath: "$.token" }
                      alive_check:
                        request:
                          method: GET
                          url: %s/me
                          headers: { Authorization: "Bearer {{login.access_token}}" }
                      exports:
                        access_token: { description: 访问令牌 }
                    """.formatted(ssoUrl, ssoUrl, ssoUrl);
            server.runtime.registry().put(ConchParser.parse(officialAuthYaml), dev.hermit.crab.store.Origin.OFFICIAL);
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.OFFICIAL, "official_sso", "corp", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    dev.hermit.crab.store.Origin.OFFICIAL,
                    server.runtime.registry().authenticator("official_sso").orElseThrow().doc(),
                    "corp",
                    Map.of("password", "OfficialPass#1"),
                    Actors.CONSOLE);

            String consumerV1 = """
                    conch: local_doc_uploader
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.14.0
                    base_url: %s
                    title: 本地附件上传
                    description: 复用正式授权器并支持文件上传
                    auth:
                      use: official_sso
                      alive_check:
                        request: { method: GET, path: /api/upload }
                    actions:
                      upload_csv:
                        title: 上传报表
                        description: 上传 CSV 附件
                        method: POST
                        idempotent: false
                        mcp: { expose: true, read_only: false, destructive: false }
                        input:
                          report: { type: file, required: true, description: 报表文件 }
                        steps:
                          - request:
                              method: POST
                              path: /api/upload
                              headers: { Authorization: "Bearer {{auth.access_token}}" }
                              multipart:
                                file: { file: "{{input.report}}" }
                          - extract:
                              uploaded: { jsonpath: "$.uploaded" }
                        output:
                          uploaded: { type: boolean, description: 是否成功 }
                        examples:
                          - input: { report: file_demo }
                            output: { uploaded: true }
                        notes: 测试上传
                        related: []
                    """.formatted(appUrl);
            JsonNode savedConsumerV1 = structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", consumerV1)));
            assertThat(savedConsumerV1.path("ok").asBoolean()).as(savedConsumerV1.toString()).isTrue();
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "local_doc_uploader", "alice", Actors.CONSOLE);
            HttpResponse<String> bindResp = server.http.send(HttpRequest.newBuilder(
                            URI.create(server.base + "/console/api/conches/local_doc_uploader/accounts/alice/authenticator"))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"authAccount\":\"corp\",\"baseUrl\":\"" + appUrl + "\",\"confirm\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(bindResp.statusCode()).as(bindResp.body()).isEqualTo(200);

            String consumerV101 = consumerV1.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode needOfficialConfirm = server.call(session, "hermit_dev__save_local", Map.of("yaml", consumerV101));
            assertThat(toolError(needOfficialConfirm).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");
            assertThat(toolMessage(needOfficialConfirm)).contains("正式授权器绑定").contains("保留现有授权器绑定");

            HttpResponse<String> pendingResp = server.http.send(HttpRequest.newBuilder(
                            URI.create(server.base + "/console/api/local-conches/pending"))
                    .header("X-Hermit-Console", "1")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String pendingSha = ApiJson.tree(pendingResp.body()).path("data").path("pending").get(0).path("sha256").asText();
            HttpResponse<String> confirmResp = server.http.send(HttpRequest.newBuilder(
                            URI.create(server.base + "/console/api/local-conches/local_doc_uploader/pending/confirm"))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sha256\":\"" + pendingSha + "\",\"remember\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(confirmResp.statusCode()).as(confirmResp.body()).isEqualTo(200);
            assertThat(server.runtime.authenticators().hasBoundToOfficial("local_doc_uploader")).isTrue();

            // remember=true 生效后，再次同边界修改无需确认且正式授权器绑定保持完好
            String consumerV102 = consumerV1.replace("version: 1.0.0", "version: 1.0.2");
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", consumerV102)))
                    .path("ok").asBoolean()).isTrue();
            assertThat(server.runtime.authenticators().hasBoundToOfficial("local_doc_uploader")).isTrue();

            // 2. hermit_dev__stage_text_file 拒绝只读 Key，读写 Key 生成测试文件后直接传给 trial_run 上传
            JsonNode roStage = server.rpc(readOnly.plaintext(), roSession, 21, "tools/call",
                    Map.of("name", "hermit_dev__stage_text_file", "arguments",
                            Map.of("filename", "demo.csv", "content", "id,amount\n1,100\n")));
            assertThat(toolError(roStage).path("code").asText()).isEqualTo("FORBIDDEN");

            JsonNode staged = structured(server.call(session, "hermit_dev__stage_text_file",
                    Map.of("filename", "demo.csv", "content", "id,amount\n1,100\n", "mimeType", "text/csv")));
            String fileId = staged.path("fileId").asText();
            assertThat(fileId).isNotBlank();

            JsonNode uploadTrial = structured(server.call(session, "hermit_dev__trial_run",
                    Map.of("conch", "local_doc_uploader", "action", "upload_csv", "input", Map.of("report", fileId))));
            assertThat(uploadTrial.path("ok").asBoolean()).as(uploadTrial.toString()).isTrue();
            assertThat(uploadTrial.path("data").path("uploaded").asBoolean()).isTrue();
            assertThat(uploadedPayload.get()).contains("filename=\"demo.csv\"").contains("id,amount\n1,100\n");

            // 3. 多源站抓包快照提示 + hermit_dev__dry_run_response 零网络离线试炼（快照与内联响应均支持）
            String multiOriginHar = """
                    {
                      "log": {
                        "version": "1.2",
                        "entries": [
                          {
                            "startedDateTime": "2026-11-26T12:00:00Z",
                            "time": 12,
                            "request": { "method": "POST", "url": "https://sso.corp.local/cas/login", "headers": [] },
                            "response": {
                              "status": 200,
                              "statusText": "OK",
                              "headers": [{"name": "Content-Type", "value": "application/json"}],
                              "content": { "size": 30, "mimeType": "application/json", "text": "{\\"ticket\\":\\"ST-1\\"}" }
                            }
                          },
                          {
                            "startedDateTime": "2026-11-26T12:00:01Z",
                            "time": 15,
                            "request": { "method": "GET", "url": "https://oa.corp.local/api/orders", "headers": [] },
                            "response": {
                              "status": 200,
                              "statusText": "OK",
                              "headers": [{"name": "Content-Type", "value": "application/json"}],
                              "content": {
                                "size": 68,
                                "mimeType": "application/json",
                                "text": "{\\"code\\":40100,\\"items\\":[{\\"id\\":\\"A1\\",\\"cents\\":2500}]}"
                              }
                            }
                          }
                        ]
                      }
                    }
                    """;
            server.runtime.recorder().importHar(multiOriginHar.getBytes(StandardCharsets.UTF_8), "multi.har");
            var entries = server.runtime.recorder().listSessionEntries(false);
            var snap = server.runtime.recorder().createSnapshot(
                    "跨站登录与订单", entries.stream().map(dev.hermit.crab.recorder.RecordedEntry::id).toList(), List.of());

            JsonNode capList = structured(server.call(session, "hermit_dev__capture_list", Map.of()));
            assertThat(capList.path("next").asText()).contains("driver: auth").contains("auth.origins");
            JsonNode capGet = structured(server.call(session, "hermit_dev__capture_get",
                    Map.of("captureId", snap.id(), "index", 2)));
            assertThat(capGet.path("next").asText()).contains("driver: auth").contains("auth.origins");

            JsonNode dryFromSnap = structured(server.call(session, "hermit_dev__dry_run_response",
                    Map.of(
                            "captureId", snap.id(),
                            "index", 2,
                            "extract", Map.of("first_id", Map.of("jsonpath", "$.items[0].id"),
                                    "cents", Map.of("jsonpath", "$.items[0].cents")),
                            "script", Map.of("code", "return { yuan: Number(vars.cents) / 100, sig: hermit.hex(hermit.md5(vars.first_id)) };"),
                            "expiredWhen", List.of(Map.of("json_equals", Map.of("$.code", 40100))),
                            "output", Map.of(
                                    "orderId", Map.of("type", "string", "description", "单号", "value", "{{first_id}}"),
                                    "yuan", Map.of("type", "number", "description", "金额", "value", "{{yuan}}"),
                                    "sig", Map.of("type", "string", "description", "摘要", "value", "{{sig}}")))));
            assertThat(dryFromSnap.path("ok").asBoolean()).as(dryFromSnap.toString()).isTrue();
            assertThat(dryFromSnap.path("expired").asBoolean()).isTrue();
            assertThat(dryFromSnap.path("matchedExpiredRuleIndex").asInt()).isZero();
            assertThat(dryFromSnap.path("output").path("orderId").asText()).isEqualTo("A1");
            assertThat(dryFromSnap.path("output").path("yuan").asDouble()).isEqualTo(25.0);
            assertThat(dryFromSnap.path("output").path("sig").asText()).hasSize(32);

            JsonNode dryInline = structured(server.call(session, "hermit_dev__dry_run_response",
                    Map.of(
                            "response", Map.of("status", 200, "headers", Map.of("X-Rate", "7"), "body", "<div>Total: 42</div>"),
                            "extract", Map.of("total", Map.of("regex", "Total:\\s*(\\d+)", "group", 1)),
                            "output", Map.of("count", Map.of("type", "integer", "description", "总数", "value", "{{total}}")))));
            assertThat(dryInline.path("ok").asBoolean()).as(dryInline.toString()).isTrue();
            assertThat(dryInline.path("output").path("count").asInt()).isEqualTo(42);
        } finally {
            sso.stop(0);
            app.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void blindTestPolishFixesConfirmHintsMcpConfigurePruningAndTraceNote() throws Exception {
        com.sun.net.httpserver.HttpServer app = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/submit", ex -> {
            byte[] b = "{\"expenseNo\":\"BX-1001\",\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        com.sun.net.httpserver.HttpServer mcpUp = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        mcpUp.createContext("/mcp", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            JsonNode req = McpJson.tree(ex.getRequestBody().readAllBytes());
            String method = req.path("method").asText("");
            if (method.startsWith("notifications/")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            String reqId = req.get("id").toString();
            String resp;
            if ("initialize".equals(method)) {
                resp = "{\"jsonrpc\":\"2.0\",\"id\":" + reqId + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"up\",\"version\":\"1.0.0\"}}}";
            } else {
                resp = """
                        {"jsonrpc":"2.0","id":%s,"result":{"tools":[
                          {"name":"keep_query","description":"保留查询工具","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}}},
                          {"name":"debug_raw_dump","description":"高危内存调试工具","inputSchema":{"type":"object","properties":{}}}
                        ]}}
                        """.formatted(reqId);
            }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Mcp-Session-Id", "up-s1");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        app.start();
        mcpUp.start();
        String appUrl = "http://127.0.0.1:" + app.getAddress().getPort();
        String mcpUrl = "http://127.0.0.1:" + mcpUp.getAddress().getPort() + "/mcp";

        try (Harness server = Harness.open(temp.resolve("blind-polish"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);

            // 1. 指南正文无旧版本残留且精确说明 Uint8Array 返回值与 4 参数 AES
            JsonNode guideFields = structured(server.call(session, "hermit_dev__guide", Map.of("section", "fields")));
            assertThat(guideFields.path("content").asText())
                    .contains("当前 Crab 兼容版本为 1.14.0")
                    .doesNotContain("当前 Crab 兼容版本为 1.13.0");
            JsonNode guideScripts = structured(server.call(session, "hermit_dev__guide", Map.of("section", "templates_scripts")));
            assertThat(guideScripts.path("content").asText())
                    .contains("统一返回 `Uint8Array`")
                    .contains("(mode, key, iv, data)");

            // 2. HTTP 规则包误写 actions.<name>.mcp.confirm 或 actions.<name>.confirm 均有定向修复提示
            String misplacedMcpConfirm = """
                    conch: bad_confirm
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.14.0
                    base_url: %s
                    title: 错位确认测试
                    description: 测试 mcp.confirm 提示。
                    credentials: {}
                    actions:
                      do_write:
                        title: 写操作
                        description: 提交表单。
                        mcp: { expose: true, read_only: false, destructive: true, confirm: true }
                        steps:
                          - request: { method: POST, path: /submit }
                        output:
                          ok: { type: string, description: 状态 }
                        examples:
                          - input: {}
                            output: { ok: "1" }
                    """.formatted(appUrl);
            JsonNode v1 = structured(server.call(session, "hermit_dev__validate", Map.of("yaml", misplacedMcpConfirm)));
            assertThat(v1.path("ok").asBoolean()).isFalse();
            assertThat(v1.path("fixHints").toString()).contains("driver: http 能力不写 confirm 字段");

            String misplacedActionConfirm = misplacedMcpConfirm.replace(
                    "mcp: { expose: true, read_only: false, destructive: true, confirm: true }",
                    "mcp: { expose: true, read_only: false, destructive: true }\n    confirm: true");
            JsonNode v2 = structured(server.call(session, "hermit_dev__validate", Map.of("yaml", misplacedActionConfirm)));
            assertThat(v2.path("ok").asBoolean()).isFalse();
            assertThat(v2.path("fixHints").toString()).contains("driver: http 能力不支持 confirm");

            // 3. hermit_dev__mcp_configure 设置 tools.exclude 后立即原地裁剪被排除 action，无需二次 mcp_refresh
            String mcpSkeleton = """
                    conch: devops_mcp
                    version: 1.0.0
                    driver: mcp
                    min_crab_version: 1.14.0
                    title: 流水线 MCP
                    description: 测试原地过滤裁剪。
                    credentials: {}
                    upstream:
                      url: %s
                      transport: streamable_http
                      auth: { type: headers }
                    actions: {}
                    """.formatted(mcpUrl);
            assertThat(structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", mcpSkeleton)))
                    .path("ok").asBoolean()).isTrue();
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "devops_mcp", "default", Actors.CONSOLE);
            JsonNode refreshed = structured(server.call(session, "hermit_dev__mcp_refresh", Map.of("conch", "devops_mcp")));
            assertThat(refreshed.path("toolCount").asInt()).isEqualTo(2);

            JsonNode configured = structured(server.call(session, "hermit_dev__mcp_configure", Map.of(
                    "conch", "devops_mcp",
                    "tools", Map.of("exclude", List.of("debug_*")),
                    "actions", Map.of("keep_query", Map.of("readOnly", true, "destructive", false, "confirm", false)))));
            assertThat(configured.path("ok").asBoolean()).as(configured.toString()).isTrue();
            assertThat(configured.path("removed")).extracting(JsonNode::asText).containsExactly("debug_raw_dump");
            assertThat(configured.path("warnings").toString()).doesNotContain("debug_raw_dump");
            assertThat(server.runtime.registry().find("devops_mcp", dev.hermit.crab.store.Origin.LOCAL)
                    .orElseThrow().actions().keySet()).containsExactly("keep_query");

            // 4. 携带 credential: true 派生签名的请求在 debug_trace 的 note 中给出明确脱敏原因提示
            String signedHttp = """
                    conch: signed_app
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.14.0
                    base_url: %s
                    title: 签名提交系统
                    description: "测试 credential: true 脚本签名请求的追踪提示。"
                    credentials:
                      api_secret: { type: secret, label: 密钥, required: true }
                    actions:
                      submit_order:
                        title: 提交单据
                        description: 附带 HMAC 签名提交单据。
                        method: POST
                        mcp: { expose: true, read_only: false, destructive: false }
                        steps:
                          - script:
                              credential: true
                              into: sig
                              code: |
                                return hermit.hex(hermit.hmacSha256(vars.credential.api_secret, "BX-1001"));
                          - request:
                              method: POST
                              path: /submit
                              headers:
                                X-Sign: "{{sig}}"
                          - extract:
                              expenseNo: { jsonpath: "$.expenseNo" }
                        output:
                          expenseNo: { type: string, description: 单号 }
                        examples:
                          - input: {}
                            output: { expenseNo: "BX-1001" }
                        notes: 写操作示例。
                        related: []
                    """.formatted(appUrl);
            JsonNode savedSigned = structured(server.call(session, "hermit_dev__save_local", Map.of("yaml", signedHttp)));
            assertThat(savedSigned.path("ok").asBoolean()).as(savedSigned.toString()).isTrue();
            server.runtime.admin().createAccount(dev.hermit.crab.store.Origin.LOCAL, "signed_app", "default", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    dev.hermit.crab.store.Origin.LOCAL,
                    server.runtime.registry().find("signed_app", dev.hermit.crab.store.Origin.LOCAL).orElseThrow(),
                    "default",
                    Map.of("api_secret", "TopSecret#Key99"),
                    Actors.CONSOLE);
            JsonNode trial = structured(server.call(session, "hermit_dev__trial_run",
                    Map.of("conch", "signed_app", "action", "submit_order")));
            assertThat(trial.path("ok").asBoolean()).as(trial.toString()).isTrue();
            assertThat(trial.path("data").path("expenseNo").asText()).isEqualTo("BX-1001");

            JsonNode trace = structured(server.call(session, "hermit_dev__debug_trace",
                    Map.of("traceId", trial.path("traceId").asText())));
            assertThat(trace.path("note").asText())
                    .contains("正文已脱敏")
                    .contains("credentialBearing=true");
        } finally {
            app.stop(0);
            mcpUp.stop(0);
        }
    }

    private static String writable(String name, String version) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 写入探测
                description: 只在本机。
                credentials: {}
                actions:
                  write_note:
                    title: 写入
                    description: 返回常量，模拟写操作。
                    idempotent: false
                    mcp: {expose: true, read_only: false, destructive: true}
                    steps:
                      - set: {message: wrote-%s}
                    output:
                      message: {type: string, description: 文本}
                    examples:
                      - input: {}
                        output: {message: wrote-%s}
                    notes: 无
                    related: []
                """.formatted(name, version, name, name);
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
            return open(home, "127.0.0.1");
        }

        static Harness open(Path home, String host) throws Exception {
            return open(home, CrabConfig.defaults().withListen(host, 0).withMcpDirectLimit(40));
        }

        static Harness open(Path home, CrabConfig config) throws Exception {
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), config);
            CrabHttpServer server = new CrabHttpServer(runtime);
            server.start();
            CreatedApiKey key = runtime.apiKeys().create("authoring", new ApiKeyScope(Set.of(), Set.of(), false, true, Set.of(), true), Actors.CONSOLE);
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

        JsonNode devOverview(String apiKey, String session) throws Exception {
            return rpc(apiKey, session, 30, "tools/call", Map.of("name", "hermit_dev", "arguments", Map.of()));
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
            return postRaw(base + "/mcp", apiKey, session, body, extra);
        }

        HttpResponse<String> postRaw(String endpoint, String apiKey, String session, String body, Map<String, String> extra) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
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
