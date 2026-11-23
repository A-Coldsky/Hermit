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
            String session = server.initialize(false);
            JsonNode tools = server.rpc(session, 2, "tools/list", null);
            assertThat(toolNames(tools)).contains(
                    "conch_validate", "conch_save_local", "conch_trial_run", "debug_trace");
            assertThat(tool(tools, "conch_validate").path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool(tools, "conch_validate").path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool(tools, "conch_validate").path("description").asText()).contains("先和用户确认要做哪些功能");
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
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            String widened = yaml.replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode refused = server.call(session, "conch_save_local", Map.of("yaml", widened));
            assertThat(toolError(refused).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");

            dev.hermit.crab.sync.LocalConches.PendingOverwrite pending =
                    server.runtime.localConches().pending().get(0);
            server.runtime.localConches().confirmPending("saved_ping", pending.sha256(), Actors.CONSOLE, true);
            assertThat(server.runtime.localConches().grants())
                    .extracting(dev.hermit.crab.sync.LocalConches.OverrideGrant::conch)
                    .containsExactly("saved_ping");

            String again = widened.replace("version: 1.0.0", "version: 1.1.0")
                    .replace("base_url: http://127.0.0.1:10/", "base_url: http://127.0.0.1:11/");
            JsonNode saved = server.call(session, "conch_save_local", Map.of("yaml", again));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(structured(saved).path("replaced").asBoolean()).isTrue();
            assertThat(server.runtime.localConches().pending()).isEmpty();
            Path file = server.runtime.paths().localConches().resolve("saved_ping.yaml");
            assertThat(Files.readString(file)).contains("127.0.0.1:11");

            String other = LocalConchBehaviorTest.ping("other_ping", "1.0.0");
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", other))).path("ok").asBoolean()).isTrue();
            String otherWide = other.replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:12/");
            JsonNode still = server.call(session, "conch_save_local", Map.of("yaml", otherWide));
            assertThat(toolError(still).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");
            assertThat(Files.readString(server.runtime.paths().localConches().resolve("other_ping.yaml"))).isEqualTo(other);

            String blocked = again.replace("version: 1.1.0", "version: 1.2.0")
                    .replace("base_url: http://127.0.0.1:11/", "base_url: http://127.0.0.1:13/");
            for (String field : java.util.List.of("remember", "autoApprove", "auto_approve", "skipConfirm", "grant")) {
                JsonNode denied = server.call(session, "conch_save_local", Map.of("yaml", blocked, field, true));
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
            JsonNode validated = server.call(session, "conch_validate", Map.of("yaml", yaml));
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
            JsonNode warned = server.call(session, "conch_validate", Map.of("yaml", warningOnly));
            JsonNode warnedBody = structured(warned);
            assertThat(warnedBody.path("ok").asBoolean()).isFalse();
            assertThat(codes(warnedBody.path("errors"))).containsExactly("PLAINTEXT_CREDENTIAL");
            assertThat(codes(warnedBody.path("warnings"))).doesNotContain("LITERAL_CREDENTIAL");
            assertThat(warnedBody.path("errors").get(0).path("line").isNumber()).isTrue();
            assertThat(warnedBody.path("errors").get(0).path("path").asText())
                    .isEqualTo("actions.ping.steps[0].request.headers.X-Note");
            assertThat(warnedBody.toString()).doesNotContain(token);
            JsonNode blocked = server.call(session, "conch_save_local", Map.of("yaml", warningOnly));
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
            JsonNode saved = server.call(session, "conch_save_local", Map.of("yaml", yaml));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(structured(saved).path("replaced").asBoolean()).isFalse();
            assertThat(structured(saved).path("next").asText())
                    .contains("不要把口令、Cookie 或令牌发给 AI")
                    .contains("新建账号");
            Path file = server.runtime.paths().localConches().resolve("saved_ping.yaml");
            assertThat(Files.readString(file)).isEqualTo(yaml);

            String next = yaml.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode silent = server.call(session, "conch_save_local", Map.of("yaml", next));
            assertThat(structured(silent).path("ok").asBoolean()).as(silent.toString()).isTrue();
            assertThat(structured(silent).path("replaced").asBoolean()).isTrue();
            assertThat(structured(silent).path("previousVersion").asText()).isEqualTo("1.0.0");
            assertThat(structured(silent).path("backupId").asText()).isNotBlank();
            assertThat(Files.readString(file)).isEqualTo(next);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            String widened = next.replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode refused = server.call(session, "conch_save_local", Map.of("yaml", widened));
            assertThat(toolError(refused).path("code").asText()).isEqualTo("CONFIRM_REQUIRED");
            assertThat(toolMessage(refused)).isEqualTo(AuthoringCalls.confirmRequiredMessage(
                    dev.hermit.common.validate.ConchDestinations.between(
                            dev.hermit.common.parse.ConchParser.parse(next),
                            dev.hermit.common.parse.ConchParser.parse(widened))));
            assertThat(toolError(refused).path("hint").asText()).isEqualTo(AuthoringCalls.SAME_NAME);
            assertThat(Files.readString(file)).isEqualTo(next);
            assertThat(server.runtime.localConches().pending()).hasSize(1);

            JsonNode param = server.call(session, "conch_save_local", Map.of("yaml", widened, "confirm", true));
            assertThat(toolMessage(param)).isEqualTo(AuthoringCalls.CONFIRM_PARAM);
            assertThat(Files.readString(file)).isEqualTo(next);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> declined = new AtomicReference<>();
                Thread decline = new Thread(() -> declined.set(
                        server.callUnchecked(confirming, "conch_save_local", Map.of("yaml", widened))));
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
                        server.callUnchecked(confirming, "conch_save_local", Map.of("yaml", widened))));
                accept.start();
                JsonNode again = sse.awaitMethod("elicitation/create");
                server.answer(confirming, again, "accept", true);
                accept.join(15_000);
                assertThat(structured(accepted.get()).path("ok").asBoolean()).as(String.valueOf(accepted.get())).isTrue();
                assertThat(structured(accepted.get()).path("replaced").asBoolean()).isTrue();
                assertThat(Files.readString(file)).contains("127.0.0.1:10");

                server.runtime.admin().createAccount("saved_ping", "main", Actors.CONSOLE);
                String later = widened.replace("version: 1.0.1", "version: 1.0.2");
                JsonNode checked = server.call(confirming, "conch_save_local", Map.of("yaml", later));
                assertThat(structured(checked).path("next").asText())
                        .doesNotContain("新建账号")
                        .contains("可以直接试跑")
                        .doesNotContain("测试登录");

                String changed = later.replace("version: 1.0.2", "version: 1.0.3")
                        .replace("credentials: {}", """
                                credentials:
                                  password: { type: secret, required: true, label: 密码 }""");
                JsonNode relogin = server.call(confirming, "conch_save_local", Map.of("yaml", changed));
                assertThat(structured(relogin).path("next").asText())
                        .contains("测试登录")
                        .contains("凭证字段有变化")
                        .doesNotContain("可以直接试跑")
                        .doesNotContain("新建账号");
            }

            server.runtime.registry().put(ConchParser.parse(LocalConchBehaviorTest.ping("official_ping", "1.0.0")));
            String official = LocalConchBehaviorTest.ping("official_ping", "1.0.0");
            // 没有本地那份时不能试跑正式包。
            JsonNode trial = server.call(session, "conch_trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(trial)).isEqualTo(AuthoringCalls.OFFICIAL);
            JsonNode blocked = server.call(session, "conch_save_local", Map.of("yaml", official));
            // 和正式包同名可以保存；本地这份不生效，试跑要先切来源。
            assertThat(blocked.path("result").path("isError").asBoolean()).as(blocked.toString()).isFalse();
            assertThat(server.runtime.paths().localConches().resolve("official_ping.yaml")).exists();
            assertThat(server.runtime.registry().origin("official_ping")).isEqualTo(dev.hermit.crab.store.Origin.OFFICIAL);
            JsonNode inactive = server.call(session, "conch_trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(inactive)).isEqualTo(AuthoringCalls.LOCAL_INACTIVE);
            assertThat(server.runtime.registry().activate("official_ping", dev.hermit.crab.store.Origin.LOCAL)).isTrue();
            JsonNode ran = server.call(session, "conch_trial_run", Map.of("conch", "official_ping", "action", "ping"));
            assertThat(toolMessage(ran)).isNotEqualTo(AuthoringCalls.LOCAL_INACTIVE);
        }
    }

    @org.junit.jupiter.api.Test
    void overwriteNamesTheNewHostAndDropsTheSavedUpstreamToken() throws Exception {
        try (Harness server = Harness.open(temp.resolve("hosts"))) {
            server.runtime.debug().setEnabled(true, Actors.CONSOLE);
            String session = server.initialize(false);
            JsonNode saved = server.call(session, "conch_save_local",
                    Map.of("yaml", hostYaml("1.0.0", "https://old.example/mcp")));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            server.runtime.admin().createAccount("host_mcp", "ada", Actors.CONSOLE);
            insertToken(server.runtime, "host_mcp", "ada");

            String same = hostYaml("1.0.1", "https://old.example:443/mcp/v2");
            String moved = hostYaml("1.0.2", "https://new.example/mcp");
            JsonNode kept = server.call(session, "conch_save_local", Map.of("yaml", same));
            assertThat(structured(kept).path("ok").asBoolean()).as(kept.toString()).isTrue();
            assertThat(structured(kept).path("replaced").asBoolean()).isTrue();
            assertThat(server.runtime.admin().credentialNames("host_mcp", "ada")).contains(McpOAuthToken.FIELD);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> replaced = new AtomicReference<>();
                Thread second = new Thread(() -> replaced.set(
                        server.callUnchecked(confirming, "conch_save_local", Map.of("yaml", moved))));
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
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            JsonNode missing = server.call(session, "conch_trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            assertThat(toolMessage(missing)).isEqualTo(
                    "还没有账号。请到控制台为本地规则包 trial_ping 新建账号并填写凭证。不要把口令、Cookie 或令牌发给 AI。");
            assertThat(hits).hasValue(0);

            server.runtime.admin().createAccount("trial_ping", "main", Actors.CONSOLE);
            // 整包停用时试跑报整包停用，不让人去启用单个能力。
            server.runtime.setConchDisabled("trial_ping", true, Actors.CONSOLE);
            JsonNode off = server.call(session, "conch_trial_run", Map.of("conch", "trial_ping", "action", "ping"));
            assertThat(toolError(off).path("code").asText()).isEqualTo("CONCH_DISABLED");
            assertThat(toolMessage(off)).isEqualTo(dev.hermit.crab.runtime.Invoker.CONCH_SWITCHED_OFF_CALL);
            assertThat(hits).hasValue(0);
            server.runtime.setConchDisabled("trial_ping", false, Actors.CONSOLE);
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

            JsonNode own = server.rpc(session, 9, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(own.path("result").path("isError").asBoolean(true)).as(own.toString()).isFalse();
            JsonNode ownBody = ApiJson.tree(own.path("result").path("content").get(0).path("text").asText());
            assertThat(ownBody.path("traceId").asText()).isEqualTo(traceId);
            assertThat(ownBody.path("devTrace").isObject()).isTrue();
            assertThat(ownBody.path("conch").asText()).isEqualTo("trial_ping");

            CreatedApiKey all = server.runtime.apiKeys().create(
                    "all", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            String allSession = server.initialize(all.plaintext(), false);
            JsonNode hidden = server.rpc(all.plaintext(), allSession, 2, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(toolMessage(hidden)).isEqualTo(McpCalls.TRACE_DENIED);
            assertThat(toolMessage(hidden)).doesNotContain("没有这个工具");
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
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            server.runtime.admin().createAccount("ro_ping", "main", Actors.CONSOLE);
            Path stored = server.runtime.paths().localConches().resolve("ro_ping.yaml");
            Path other = server.runtime.paths().localConches().resolve("ro_other.yaml");

            CreatedApiKey readOnly = server.runtime.apiKeys().create(
                    "read-only", new ApiKeyScope(Set.of(), Set.of(), true, true), Actors.CONSOLE);
            CreatedApiKey namedReadOnly = server.runtime.apiKeys().create(
                    "named-read-only", new ApiKeyScope(Set.of("ro_ping"), Set.of(), true), Actors.CONSOLE);
            String readOnlySession = server.initialize(readOnly.plaintext(), false);
            String namedSession = server.initialize(namedReadOnly.plaintext(), false);
            JsonNode readOnlyTools = server.rpc(readOnly.plaintext(), readOnlySession, 2, "tools/list", null);
            assertThat(toolNames(readOnlyTools)).doesNotContain("conch_save_local")
                    .contains("conch_validate", "conch_trial_run", "debug_trace");

            JsonNode validated = server.rpc(readOnly.plaintext(), readOnlySession, 3, "tools/call", Map.of(
                    "name", "conch_validate", "arguments", Map.of("yaml", yaml)));
            assertThat(validated.path("result").path("isError").asBoolean(true)).isFalse();
            assertThat(structured(validated).path("ok").asBoolean()).isTrue();

            String replacement = yaml.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode saveDenied = server.rpc(readOnly.plaintext(), readOnlySession, 4, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", replacement)));
            assertThat(toolMessage(saveDenied)).isEqualTo(AuthoringCalls.READ_ONLY_SAVE);
            assertThat(toolError(saveDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            String fresh = readWritePackage("ro_other", "1.0.0", base);
            JsonNode freshDenied = server.rpc(namedReadOnly.plaintext(), namedSession, 4, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", fresh)));
            assertThat(toolMessage(freshDenied)).isEqualTo(AuthoringCalls.READ_ONLY_SAVE);
            assertThat(Files.readString(stored)).contains("version: 1.0.0").doesNotContain("version: 1.0.1");
            assertThat(other).doesNotExist();

            JsonNode shown = server.rpc(readOnly.plaintext(), readOnlySession, 5, "tools/call", Map.of(
                    "name", "conch_trial_run",
                    "arguments", Map.of("conch", "ro_ping", "action", "show", "account", "main")));
            assertThat(shown.path("result").path("isError").asBoolean(true)).as(shown.toString()).isFalse();
            assertThat(structured(shown).path("data").path("message").asText()).isEqualTo("seen");
            JsonNode ownTrace = server.rpc(readOnly.plaintext(), readOnlySession, 6, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", structured(shown).path("traceId").asText())));
            assertThat(ownTrace.path("result").path("isError").asBoolean(true)).as(ownTrace.toString()).isFalse();

            JsonNode writeDenied = server.rpc(readOnly.plaintext(), readOnlySession, 7, "tools/call", Map.of(
                    "name", "conch_trial_run",
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
            JsonNode localTools = server.rpc(localSession, 2, "tools/list", null);
            assertThat(toolNames(localTools)).contains("conch_validate", "conch_save_local", "conch_trial_run", "debug_trace");
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
            String remoteSession = opened.headers().firstValue("Mcp-Session-Id").orElseThrow();
            HttpResponse<String> remoteTools = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """, Map.of());
            assertThat(remoteTools.body()).contains("debug_trace")
                    .doesNotContain("conch_validate")
                    .doesNotContain("conch_save_local")
                    .doesNotContain("conch_trial_run");
            HttpResponse<String> remoteResources = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, """
                    {"jsonrpc":"2.0","id":3,"method":"resources/list"}
                    """, Map.of());
            assertThat(remoteResources.body()).doesNotContain(AuthoringGuide.URI);
            for (String tool : java.util.List.of("conch_validate", "conch_save_local", "conch_trial_run")) {
                HttpResponse<String> called = server.postRaw(remote + "/mcp", server.key.plaintext(), remoteSession, callJson(tool, yaml), Map.of());
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

            JsonNode saved = server.call(localSession, "conch_save_local", Map.of("yaml", yaml));
            assertThat(structured(saved).path("ok").asBoolean()).as(saved.toString()).isTrue();
            assertThat(server.runtime.paths().localConches().resolve("remote_ping.yaml")).exists();
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
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            HttpResponse<String> noAccount = server.postRaw(server.key.plaintext(), session, """
                    {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"conch_trial_run","arguments":{"conch":"needs_password","action":"show"}}}
                    """, Map.of());
            assertThat(noAccount.headers().firstValue("X-Hermit-Trace")).isEmpty();
            JsonNode noAccountBody = ApiJson.tree(noAccount.body());
            assertThat(noAccountBody.toString()).doesNotContain("\"traceId\"");
            assertThat(toolMessage(noAccountBody)).contains("还没有账号").contains("needs_password");
            assertThat(noAccountBody.toString()).contains("不可查追踪");
            String requestId = toolError(noAccountBody).path("requestId").asText();
            assertThat(requestId).isNotBlank();
            JsonNode rejectedTrace = server.rpc(session, 8, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", requestId)));
            assertThat(toolMessage(rejectedTrace)).doesNotContain("试跑被拒").doesNotContain("没有这个工具");

            server.runtime.admin().createAccount("needs_password", "main", Actors.CONSOLE);
            HttpResponse<String> rejected = server.postRaw(server.key.plaintext(), session, """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"conch_trial_run","arguments":{"conch":"needs_password","action":"show","account":"main"}}}
                    """, Map.of());
            assertThat(rejected.headers().firstValue("X-Hermit-Trace")).isEmpty();
            JsonNode missing = ApiJson.tree(rejected.body());
            assertThat(missing.toString()).doesNotContain("\"traceId\"");
            assertThat(toolMessage(missing)).contains("main").contains("密码（password）").contains("填写凭证")
                    .doesNotContain("missing credentials");
            assertThat(missing.toString()).contains("不可查追踪").doesNotContain("s3cret-value");

            JsonNode illegal = server.call(session, "conch_trial_run", Map.of(
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
            assertThat(structured(server.call(server.initialize(false), "conch_save_local", Map.of("yaml", yaml)))
                    .path("ok").asBoolean()).isTrue();
            String next = yaml.replace("version: 1.0.0", "version: 1.0.1")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                JsonNode timed = server.call(confirming, "conch_save_local", Map.of("yaml", next));
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
            assertThat(structured(server.call(server.initialize(false), "conch_save_local", Map.of("yaml", yaml)))
                    .path("ok").asBoolean()).isTrue();
            String widened = yaml.replace("version: 1.0.0", "version: 1.0.1")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(
                        server.callUnchecked(confirming, "conch_save_local", Map.of("yaml", widened))));
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
            JsonNode validated = server.call(session, "conch_validate", Map.of("yaml", yaml));
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
            JsonNode ok = server.call(session, "conch_validate", Map.of("yaml", declared));
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
            assertThat(structured(server.call(session, "conch_save_local", Map.of("yaml", yaml))).path("ok").asBoolean()).isTrue();
            dev.hermit.common.model.ConchDoc doc = server.runtime.registry().find("partial_ping").orElseThrow();
            server.runtime.admin().createAccount("partial_ping", "ada", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(doc, "ada", Map.of("password", "filled-secret"), Actors.CONSOLE);

            JsonNode trial = server.call(session, "conch_trial_run", Map.of(
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
        if ("conch_trial_run".equals(tool)) {
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
                    "mine-only", new ApiKeyScope(Set.of("mine_pkg"), Set.of(), false), Actors.CONSOLE);
            String session = server.initialize(named.plaintext(), false);

            JsonNode trial = server.rpc(named.plaintext(), session, 3, "tools/call", Map.of(
                    "name", "conch_trial_run", "arguments", Map.of("conch", "other_pkg", "action", "write_note")));
            assertThat(toolError(trial).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(trial)).isEqualTo(AuthoringCalls.NOT_IN_SCOPE_PREFIX + "other_pkg"
                    + AuthoringCalls.NOT_IN_SCOPE_SUFFIX);
            assertThat(trial.toString()).doesNotContain("wrote-other_pkg");

            Path stored = server.runtime.paths().localConches().resolve("other_pkg.yaml");
            String before = Files.readString(stored);
            JsonNode overwrite = server.rpc(named.plaintext(), session, 4, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", writable("other_pkg", "1.0.1"))));
            assertThat(toolError(overwrite).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(overwrite)).startsWith(AuthoringCalls.NOT_IN_SCOPE_PREFIX + "other_pkg");
            assertThat(Files.readString(stored)).isEqualTo(before);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            String widened = writable("other_pkg", "1.0.2")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode widenedOverwrite = server.rpc(named.plaintext(), session, 6, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", widened)));
            assertThat(toolError(widenedOverwrite).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(widenedOverwrite)).startsWith(AuthoringCalls.NOT_IN_SCOPE_PREFIX + "other_pkg");
            assertThat(Files.readString(stored)).isEqualTo(before);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            JsonNode fresh = server.rpc(named.plaintext(), session, 5, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", writable("new_pkg", "1.0.0"))));
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
            JsonNode refused = server.call(plain, "conch_trial_run", Map.of("conch", "write_pkg", "action", "write_note"));
            assertThat(toolError(refused).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(refused)).isEqualTo(McpCalls.NO_CONFIRM);

            String confirming = server.initialize(true);
            try (Sse sse = Sse.open(server, confirming)) {
                sse.awaitPrefix(": open");
                AtomicReference<JsonNode> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(server.callUnchecked(
                        confirming, "conch_trial_run", Map.of("conch", "write_pkg", "action", "write_note"))));
                accept.start();
                server.answer(confirming, sse.awaitMethod("elicitation/create"), "accept", true);
                accept.join(15_000);
                assertThat(structured(accepted.get()).path("data").path("message").asText())
                        .as(String.valueOf(accepted.get())).isEqualTo("wrote-write_pkg");

                AtomicReference<JsonNode> swapped = new AtomicReference<>();
                Thread swap = new Thread(() -> swapped.set(server.callUnchecked(
                        confirming, "conch_trial_run", Map.of("conch", "write_pkg", "action", "write_note"))));
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
                    "narrow", new ApiKeyScope(Set.of("mine_only"), Set.of(), false), Actors.CONSOLE);
            String narrowSession = server.initialize(narrow.plaintext(), false);
            JsonNode absentDenied = server.rpc(narrow.plaintext(), narrowSession, 2, "tools/call", Map.of(
                    "name", "conch_trial_run", "arguments", Map.of("conch", "missing_pkg", "action", "show")));
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
                    "ro-all", new ApiKeyScope(Set.of(), Set.of(), true, true), Actors.CONSOLE);
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
            assertThat(structured(server.call(fullSession, "conch_save_local", Map.of("yaml", dualV1)))
                    .path("ok").asBoolean()).isTrue();
            String dualV2 = dualV1.replace("version: 1.0.0", "version: 1.0.1");
            JsonNode replaced = server.call(fullSession, "conch_save_local", Map.of("yaml", dualV2));
            assertThat(structured(replaced).path("ok").asBoolean()).as(replaced.toString()).isTrue();
            assertThat(structured(replaced).path("replaced").asBoolean()).isTrue();
            assertThat(structured(replaced).path("next").asText()).isEqualTo(AuthoringCalls.NEXT_ACCOUNT);
        }
    }

    /** Issue 5c: debug_trace 核对 read_only 必须查 Origin.LOCAL，不能被同名生效的 OFFICIAL 只读定义放行。 */
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
            JsonNode ran = server.call(fullSession, "conch_trial_run", Map.of("conch", "dual_trace", "action", "act"));
            assertThat(ran.path("result").path("isError").asBoolean(true)).as(ran.toString()).isFalse();
            String traceId = structured(ran).path("traceId").asText();
            assertThat(traceId).isNotBlank();

            // 同名正式包生效，且在正式包里 act 是 read_only: true。
            server.runtime.registry().put(ConchParser.parse(officialRead), dev.hermit.crab.store.Origin.OFFICIAL);
            assertThat(server.runtime.registry().activate("dual_trace", dev.hermit.crab.store.Origin.OFFICIAL)).isTrue();
            assertThat(server.runtime.registry().origin("dual_trace")).isEqualTo(dev.hermit.crab.store.Origin.OFFICIAL);

            CreatedApiKey namedReadOnly = server.runtime.apiKeys().create(
                    "named-ro", new ApiKeyScope(Set.of("dual_trace"), Set.of(), true), Actors.CONSOLE);
            String roSession = server.initialize(namedReadOnly.plaintext(), false);
            JsonNode denied = server.rpc(namedReadOnly.plaintext(), roSession, 2, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(toolError(denied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(denied)).isEqualTo(McpCalls.TRACE_DENIED);

            CreatedApiKey namedRw = server.runtime.apiKeys().create(
                    "named-rw", new ApiKeyScope(Set.of("dual_trace"), Set.of(), false), Actors.CONSOLE);
            String rwSession = server.initialize(namedRw.plaintext(), false);
            JsonNode allowed = server.rpc(namedRw.plaintext(), rwSession, 2, "tools/call", Map.of(
                    "name", "debug_trace", "arguments", Map.of("traceId", traceId)));
            assertThat(allowed.path("result").path("isError").asBoolean(true)).as(allowed.toString()).isFalse();
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
                    ApiKeyScope.explicit(false, Set.of("scoped_local"), Set.of("scoped_local:hr"), Set.of(), false),
                    Actors.CONSOLE);
            CreatedApiKey actionScoped = server.runtime.apiKeys().create(
                    "action-scoped",
                    ApiKeyScope.explicit(false, Set.of("scoped_local"), Set.of(), Set.of("scoped_local.safe_op"), false),
                    Actors.CONSOLE);
            CreatedApiKey wholePackage = server.runtime.apiKeys().create(
                    "whole-pkg",
                    ApiKeyScope.explicit(false, Set.of("scoped_local"), Set.of(), Set.of(), false),
                    Actors.CONSOLE);

            String groupSession = server.initialize(groupScoped.plaintext(), false);
            String actionSession = server.initialize(actionScoped.plaintext(), false);
            String wholeSession = server.initialize(wholePackage.plaintext(), false);
            String allSession = server.initialize(false);

            // 可写 Key 仍暴露 conch_save_local（可建新包）。
            assertThat(toolNames(server.rpc(groupScoped.plaintext(), groupSession, 2, "tools/list", null)))
                    .contains("conch_save_local");
            assertThat(toolNames(server.rpc(actionScoped.plaintext(), actionSession, 2, "tools/list", null)))
                    .contains("conch_save_local");

            // 1) 按分组限权的 Key 试图在同目的地边界下把管理能力改入 hr 组：必须 FORBIDDEN。
            String groupEscalation = groupedLocal("scoped_local", "1.0.1").replace("group: admin", "group: hr");
            JsonNode groupDenied = server.rpc(groupScoped.plaintext(), groupSession, 3, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", groupEscalation)));
            assertThat(toolError(groupDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(groupDenied)).isEqualTo(
                    AuthoringCalls.NARROWED_SAVE_PREFIX + "scoped_local" + AuthoringCalls.NARROWED_SAVE_SUFFIX);
            assertThat(Files.readString(stored)).isEqualTo(original);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            // 扩大目的地时同样直接 FORBIDDEN，不挂待确认覆盖。
            String widened = groupedLocal("scoped_local", "1.0.2")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: http://127.0.0.1:10/");
            JsonNode groupWidenedDenied = server.rpc(groupScoped.plaintext(), groupSession, 4, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", widened)));
            assertThat(toolError(groupWidenedDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(groupWidenedDenied)).isEqualTo(
                    AuthoringCalls.NARROWED_SAVE_PREFIX + "scoped_local" + AuthoringCalls.NARROWED_SAVE_SUFFIX);
            assertThat(Files.readString(stored)).isEqualTo(original);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            // 2) 按能力限权的 Key 试图在同目的地边界下篡改 safe_op：必须 FORBIDDEN。
            String actionEscalation = groupedLocal("scoped_local", "1.0.1").replace("safe-scoped_local", "hijacked");
            JsonNode actionDenied = server.rpc(actionScoped.plaintext(), actionSession, 3, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", actionEscalation)));
            assertThat(toolError(actionDenied).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolMessage(actionDenied)).isEqualTo(
                    AuthoringCalls.NARROWED_SAVE_PREFIX + "scoped_local" + AuthoringCalls.NARROWED_SAVE_SUFFIX);
            assertThat(Files.readString(stored)).isEqualTo(original);
            assertThat(server.runtime.localConches().pending()).isEmpty();

            // 3) 限权 Key 仍可新建全新本地包，并试跑授权范围内的能力（范围外能力拒绝）。
            JsonNode created = server.rpc(groupScoped.plaintext(), groupSession, 5, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", writable("brand_new_pkg", "1.0.0"))));
            assertThat(structured(created).path("ok").asBoolean()).as(created.toString()).isTrue();
            assertThat(structured(created).path("replaced").asBoolean()).isFalse();

            JsonNode allowedTrial = server.rpc(groupScoped.plaintext(), groupSession, 6, "tools/call", Map.of(
                    "name", "conch_trial_run", "arguments", Map.of("conch", "scoped_local", "action", "safe_op")));
            assertThat(allowedTrial.path("result").path("isError").asBoolean(true)).as(allowedTrial.toString()).isFalse();
            assertThat(structured(allowedTrial).path("data").path("message").asText()).isEqualTo("safe-scoped_local");

            JsonNode deniedTrial = server.rpc(groupScoped.plaintext(), groupSession, 7, "tools/call", Map.of(
                    "name", "conch_trial_run", "arguments", Map.of("conch", "scoped_local", "action", "admin_op")));
            assertThat(toolError(deniedTrial).path("code").asText()).isEqualTo("FORBIDDEN");

            // 4) 整包范围 Key（conches 点名且未限分组/能力）与全部权限 Key 仍可覆盖已有本地包。
            String wholeUpdate = groupedLocal("scoped_local", "1.0.1");
            JsonNode wholeSaved = server.rpc(wholePackage.plaintext(), wholeSession, 3, "tools/call", Map.of(
                    "name", "conch_save_local", "arguments", Map.of("yaml", wholeUpdate)));
            assertThat(structured(wholeSaved).path("ok").asBoolean()).as(wholeSaved.toString()).isTrue();
            assertThat(structured(wholeSaved).path("replaced").asBoolean()).isTrue();
            assertThat(Files.readString(stored)).contains("version: 1.0.1");

            String allUpdate = groupedLocal("scoped_local", "1.0.2");
            JsonNode allSaved = server.call(allSession, "conch_save_local", Map.of("yaml", allUpdate));
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
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), CrabConfig.defaults().withListen(host, 0));
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
