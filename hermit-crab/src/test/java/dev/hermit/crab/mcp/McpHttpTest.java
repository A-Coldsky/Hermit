package dev.hermit.crab.mcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.gateway.RequestBodies;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.LegacyOaFixture;
import dev.hermit.crab.testing.SecretChecks;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpTest {
    private static final String PASSWORD = "HermitMcpSecret-4c91e0aa7b";

    @TempDir
    Path temp;

    @Test
    void directModeCallsResourcesAndHidesSecrets() throws Exception {
        LogBag logs = new LogBag();
        Logger.getLogger("").addHandler(logs);
        try (Harness server = Harness.open(temp.resolve("direct"), PASSWORD)) {
            server.configure("alice");
            server.configure("bob");
            server.runtime.admin().createAccount("account_shell", "bob", Actors.CLI);

            Reply missing = server.post(null, null, null, initialize("{}"));
            assertThat(missing.status()).isEqualTo(401);
            assertThat(missing.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            SecretChecks.absent(missing.body(), PASSWORD);
            SecretChecks.absent(missing.body(), server.apiKey.plaintext());

            Reply initialized = server.post(server.apiKey.plaintext(), null, null, initialize("{}"), true);
            assertThat(initialized.status()).isEqualTo(200);
            String session = initialized.header("Mcp-Session-Id");
            assertThat(session).isNotBlank();
            assertThat(initialized.json().path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
            assertThat(initialized.json().path("result").path("capabilities").path("tools").path("listChanged").asBoolean()).isTrue();

            Reply listed = server.post(server.apiKey.plaintext(), session, null, request(2, "tools/list", null));
            assertThat(listed.status()).isEqualTo(200);
            JsonNode tools = listed.json().path("result").path("tools");
            JsonNode todo = tool(tools, "legacy_oa__list_todo");
            assertThat(todo.path("description").asText()).contains("查询待办").contains("待办").contains("倒序");
            JsonNode account = todo.path("inputSchema").path("properties").path("account");
            assertThat(account.path("type").asText()).isEqualTo("string");
            assertThat(account.path("pattern").asText()).isEqualTo("^[A-Za-z0-9_-]{1,64}$");
            assertThat(todo.path("inputSchema").path("required").toString()).doesNotContain("account");
            assertThat(todo.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(todo.path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(todo.path("annotations").path("idempotentHint").asBoolean()).isTrue();
            JsonNode save = tool(tools, "legacy_oa__save_note");
            assertThat(save.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(save.path("annotations").path("destructiveHint").asBoolean()).isTrue();
            assertThat(save.path("annotations").path("idempotentHint").asBoolean()).isFalse();
            JsonNode lookup = tool(tools, "account_shell__lookup");
            assertThat(lookup.path("inputSchema").path("properties").has("account")).isTrue();
            assertThat(lookup.path("inputSchema").path("properties").has("_account")).isTrue();
            assertThat(lookup.path("inputSchema").path("required").toString()).contains("account").doesNotContain("_account");
            assertThat(names(tools)).doesNotContain("account_shell__hidden");
            SecretChecks.absent(listed.body(), PASSWORD);

            Reply called = server.post(server.apiKey.plaintext(), session, "alice", request(3, "tools/call",
                    Map.of("name", "legacy_oa__list_todo", "arguments", Map.of("page", 1, "account", "bob"))));
            JsonNode result = called.json().path("result");
            assertThat(result.path("isError").asBoolean(false)).isFalse();
            assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
            assertThat(result.path("structuredContent").path("items").isArray()).isTrue();
            assertThat(result.path("content").get(0).path("text").asText()).contains("items");
            assertThat(server.runtime.callLogs().list("legacy_oa", "list_todo", 5).get(0).account()).isEqualTo("bob");
            assertThat(server.runtime.callLogs().list("legacy_oa", "list_todo", 5).get(0).traceId()).isNotBlank();
            SecretChecks.absent(called.body(), PASSWORD);
            SecretChecks.absent(called.body(), server.apiKey.plaintext());

            Reply echoed = server.post(server.apiKey.plaintext(), session, "alice", request(4, "tools/call",
                    Map.of("name", "account_shell__lookup", "arguments", Map.of("account", "biz", "_account", "bob"))));
            assertThat(echoed.json().path("result").path("isError").asBoolean(false)).isFalse();
            assertThat(echoed.json().path("result").path("structuredContent").path("seen").asText()).isEqualTo("biz");
            assertThat(server.runtime.callLogs().list("account_shell", "lookup", 5).get(0).account()).isEqualTo("bob");

            Reply unknown = server.post(server.apiKey.plaintext(), session, null, request(5, "tools/call",
                    Map.of("name", "legacy_oa__list_todo", "arguments", Map.of("account", "nobody"))));
            assertThat(toolError(unknown).path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(unknown.json().path("result").path("isError").asBoolean()).isTrue();
            assertThat(unknown.body()).doesNotContain("Exception");

            CreatedApiKey narrow = server.runtime.admin().createApiKey(
                    "narrow", new ApiKeyScope(Set.of("missing_shell"), Set.of(), false), Actors.CLI);
            Reply narrowInit = server.post(narrow.plaintext(), null, null, initialize("{}"));
            String narrowSession = narrowInit.header("Mcp-Session-Id");
            Reply hidden = server.post(narrow.plaintext(), narrowSession, null, request(2, "tools/list", null));
            assertThat(names(hidden.json().path("result").path("tools"))).doesNotContain("legacy_oa__list_todo");
            Reply denied = server.post(narrow.plaintext(), narrowSession, "alice", request(3, "tools/call",
                    Map.of("name", "legacy_oa__list_todo", "arguments", Map.of("page", 1))));
            assertThat(toolError(denied).path("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(denied.body()).doesNotContain("legacy_oa");

            Reply resources = server.post(server.apiKey.plaintext(), session, null, request(6, "resources/list", null));
            assertThat(resources.body()).contains("hermit://shells/legacy_oa/guide");
            assertThat(resources.body()).contains("hermit://shells/account_shell/guide");
            Reply guide = server.post(server.apiKey.plaintext(), session, null, request(7, "resources/read",
                    Map.of("uri", "hermit://shells/legacy_oa/guide")));
            String markdown = guide.json().path("result").path("contents").get(0).path("text").asText();
            assertThat(guide.json().path("result").path("contents").get(0).path("mimeType").asText()).isEqualTo("text/markdown");
            assertThat(markdown).contains("查询待办").contains("账号").contains("只读: 是");
            SecretChecks.absent(markdown, PASSWORD);
            Reply hiddenGuide = server.post(narrow.plaintext(), narrowSession, null, request(4, "resources/read",
                    Map.of("uri", "hermit://shells/legacy_oa/guide")));
            assertThat(hiddenGuide.json().path("error").path("message").asText()).isEqualTo("not found");

            SecretChecks.absent(logs.text(), PASSWORD);
            SecretChecks.absent(logs.text(), server.apiKey.plaintext());
        } finally {
            Logger.getLogger("").removeHandler(logs);
        }
    }

    @Test
    void notifiesToolListChanges() throws Exception {
        try (Harness server = Harness.open(temp.resolve("notify"), PASSWORD)) {
            Reply initialized = server.post(server.apiKey.plaintext(), null, null, initialize("{}"));
            String session = initialized.header("Mcp-Session-Id");
            try (Sse sse = Sse.open(server, session)) {
                sse.awaitPrefix(": open");
                server.runtime.registry().put(ShellParser.parse(readOnlyShell("extra_shell", "ping", "额外")));
                JsonNode changed = sse.awaitMethod("notifications/tools/list_changed");
                assertThat(changed.path("method").asText()).isEqualTo("notifications/tools/list_changed");
                server.runtime.setCapabilityDisabled("legacy_oa", "list_todo", true, Actors.CLI);
                sse.awaitMethod("notifications/tools/list_changed");
                Reply listed = server.post(server.apiKey.plaintext(), session, null, request(2, "tools/list", null));
                assertThat(names(listed.json().path("result").path("tools"))).doesNotContain("legacy_oa__list_todo");
            }
        }
    }

    @Test
    void directoryModeConfirmsWrites() throws Exception {
        try (Harness server = Harness.open(temp.resolve("directory"), PASSWORD)) {
            ShellDoc bulk = ShellParser.parse(bulkYaml());
            ShellDoc secret = ShellParser.parse(readOnlyShell("secret_shell", "hidden", "隐藏能力"));
            server.runtime.registry().put(bulk);
            server.runtime.registry().put(secret);
            server.runtime.admin().createAccount("bulk_shell", "main", Actors.CLI);
            CreatedApiKey scoped = server.runtime.admin().createApiKey(
                    "bulk", new ApiKeyScope(Set.of("bulk_shell"), Set.of(), false), Actors.CLI);

            Reply initialized = server.post(scoped.plaintext(), null, null, initialize("{}"));
            String session = initialized.header("Mcp-Session-Id");
            Reply listed = server.post(scoped.plaintext(), session, null, request(2, "tools/list", null));
            assertThat(names(listed.json().path("result").path("tools")))
                    .containsExactlyInAnyOrder("list_systems", "search_capabilities", "describe_capability", "call_capability");

            Reply systems = call(server, scoped.plaintext(), session, "list_systems", Map.of());
            JsonNode rows = systems.json().path("result").path("structuredContent").path("systems");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).path("shell").asText()).isEqualTo("bulk_shell");
            assertThat(rows.get(0).path("capabilities").asInt()).isEqualTo(41);
            assertThat(systems.body()).doesNotContain("secret_shell");

            Reply found = call(server, scoped.plaintext(), session, "search_capabilities", Map.of("query", "写入确认"));
            assertThat(found.json().path("result").path("structuredContent").path("matches")).hasSize(1);
            assertThat(found.json().path("result").path("structuredContent").path("matches").get(0).path("name").asText())
                    .isEqualTo("bulk_shell__act_w");
            Reply capped = call(server, scoped.plaintext(), session, "search_capabilities", Map.of("query", "只读项"));
            assertThat(capped.json().path("result").path("structuredContent").path("matches")).hasSize(20);
            Reply concealed = call(server, scoped.plaintext(), session, "search_capabilities", Map.of("query", "隐藏能力"));
            assertThat(concealed.json().path("result").path("structuredContent").path("matches")).isEmpty();

            Reply described = call(server, scoped.plaintext(), session, "describe_capability",
                    Map.of("name", "bulk_shell__act_w"));
            JsonNode describedBody = described.json().path("result").path("structuredContent");
            assertThat(describedBody.path("inputSchema").path("properties").has("text")).isTrue();
            assertThat(describedBody.path("examples").get(0).path("input").path("text").asText()).isEqualTo("hi");
            assertThat(describedBody.path("output").toString()).contains("是否写入");
            Reply hidden = call(server, scoped.plaintext(), session, "describe_capability",
                    Map.of("name", "secret_shell__hidden"));
            assertThat(toolError(hidden).path("code").asText()).isEqualTo("NOT_FOUND");

            Reply read = call(server, scoped.plaintext(), session, "call_capability",
                    Map.of("name", "bulk_shell__act_00", "arguments", Map.of("q", "a"), "account", "main"));
            assertThat(read.json().path("result").path("isError").asBoolean(false)).isFalse();
            assertThat(read.json().path("result").path("structuredContent").path("marker").asBoolean()).isTrue();

            Reply blocked = call(server, scoped.plaintext(), session, "call_capability",
                    Map.of("name", "bulk_shell__act_w", "arguments", Map.of("text", "hello"), "account", "main"));
            assertThat(blocked.json().path("result").path("isError").asBoolean()).isTrue();
            assertThat(toolError(blocked).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(toolError(blocked).path("message").asText()).isEqualTo(McpCalls.NO_CONFIRM);
            assertThat(server.runtime.callLogs().list("bulk_shell", "act_w", 5)).isEmpty();

            Reply confirmed = server.post(scoped.plaintext(), null, null, initialize("{\"elicitation\":{}}"));
            String confirming = confirmed.header("Mcp-Session-Id");
            try (Sse sse = Sse.open(server, confirming, scoped.plaintext())) {
                sse.awaitPrefix(": open");
                AtomicReference<Reply> declined = new AtomicReference<>();
                Thread decline = new Thread(() -> declined.set(callUnchecked(server, scoped.plaintext(), confirming,
                        "call_capability", Map.of("name", "bulk_shell__act_w", "arguments", Map.of("text", "no"), "account", "main"))));
                decline.start();
                JsonNode question = sse.awaitMethod("elicitation/create");
                assertThat(question.path("params").path("message").asText()).contains("确认执行 写入确认项？").contains("text=");
                server.post(scoped.plaintext(), confirming, null, """
                        {"jsonrpc":"2.0","id":"%s","result":{"action":"decline"}}
                        """.formatted(question.get("id").asText()));
                decline.join(15_000);
                assertThat(declined.get().json().path("result").path("isError").asBoolean()).isTrue();
                assertThat(toolError(declined.get()).path("code").asText()).isEqualTo("FORBIDDEN");
                assertThat(toolError(declined.get()).path("message").asText()).isEqualTo(McpCalls.REJECTED);
                assertThat(server.runtime.callLogs().list("bulk_shell", "act_w", 5)).isEmpty();

                AtomicReference<Reply> unchecked = new AtomicReference<>();
                Thread uncheck = new Thread(() -> unchecked.set(callUnchecked(server, scoped.plaintext(), confirming,
                        "call_capability", Map.of("name", "bulk_shell__act_w", "arguments", Map.of("text", "nope"), "account", "main"))));
                uncheck.start();
                JsonNode form = sse.awaitMethod("elicitation/create");
                server.post(scoped.plaintext(), confirming, null, """
                        {"jsonrpc":"2.0","id":"%s","result":{"action":"accept","content":{"confirm":false}}}
                        """.formatted(form.get("id").asText()));
                uncheck.join(15_000);
                assertThat(unchecked.get().json().path("result").path("isError").asBoolean()).isTrue();
                assertThat(toolError(unchecked.get()).path("code").asText()).isEqualTo("FORBIDDEN");
                assertThat(server.runtime.callLogs().list("bulk_shell", "act_w", 5)).isEmpty();

                AtomicReference<Reply> accepted = new AtomicReference<>();
                Thread accept = new Thread(() -> accepted.set(callUnchecked(server, scoped.plaintext(), confirming,
                        "call_capability", Map.of("name", "bulk_shell__act_w", "arguments", Map.of("text", "yes"), "account", "main"))));
                accept.start();
                JsonNode again = sse.awaitMethod("elicitation/create");
                server.post(scoped.plaintext(), confirming, null, """
                        {"jsonrpc":"2.0","id":"%s","result":{"action":"accept","content":{"confirm":true}}}
                        """.formatted(again.get("id").asText()));
                accept.join(15_000);
                assertThat(accepted.get().json().path("result").path("isError").asBoolean(false)).isFalse();
                assertThat(accepted.get().json().path("result").path("structuredContent").path("saved").asBoolean()).isTrue();
                assertThat(server.runtime.callLogs().list("bulk_shell", "act_w", 5)).hasSize(1);
                SecretChecks.absent(accepted.get().body(), PASSWORD);
            }
        }
    }

    @Test
    void sessionsExpireAfterAnIdleHourAndStayUnderTheCap() throws Exception {
        assertThat(McpEndpoint.IDLE_LIMIT).isEqualTo(Duration.ofHours(1));
        assertThat(McpEndpoint.MAX_SESSIONS).isEqualTo(1000);
        MutableClock clock = new MutableClock();
        try (SessionServer server = SessionServer.open(temp.resolve("sessions"), clock, 2)) {
            String key = server.apiKey.plaintext();
            String kept = server.initialize(key);
            clock.advance(Duration.ofMinutes(59));
            assertThat(server.ping(key, kept).status()).isEqualTo(200);

            clock.advance(Duration.ofHours(1));
            Reply expired = server.ping(key, kept);
            assertThat(expired.status()).isEqualTo(404);
            assertThat(expired.json().path("error").path("message").asText()).isEqualTo("session not found");

            String oldest = server.initialize(key);
            String middle = server.initialize(key);
            String newest = server.initialize(key);
            assertThat(server.ping(key, oldest).status()).isEqualTo(404);
            assertThat(server.ping(key, oldest).json().path("error").path("message").asText()).isEqualTo("session not found");
            assertThat(server.ping(key, middle).status()).isEqualTo(200);
            assertThat(server.ping(key, newest).status()).isEqualTo(200);
        }
    }

    @Test
    void stdioForwardsProtocolAndRefusesAMissingCrab() throws Exception {
        try (Harness server = Harness.open(temp.resolve("stdio"), PASSWORD)) {
            server.configure("alice");
            String input = initialize("{}") + "\n" + request(2, "tools/list", null) + "\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = new CrabCommand().run(
                    new String[] {"mcp-stdio", "--url", server.base + "/mcp", "--account", "alice"},
                    name -> "HERMIT_API_KEY".equals(name) ? server.apiKey.plaintext() : null,
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
            assertThat(code).isZero();
            String stdout = out.toString(StandardCharsets.UTF_8);
            assertThat(stdout).contains("legacy_oa__list_todo").contains("protocolVersion");
            SecretChecks.absent(stdout, server.apiKey.plaintext());
            SecretChecks.absent(stdout, PASSWORD);
            SecretChecks.absent(err.toString(StandardCharsets.UTF_8), server.apiKey.plaintext());

            ByteArrayOutputStream rejectedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream rejectedErr = new ByteArrayOutputStream();
            int rejected = new CrabCommand().run(
                    new String[] {"mcp-stdio", server.apiKey.plaintext()},
                    name -> null,
                    InputStream.nullInputStream(),
                    new PrintStream(rejectedOut, true, StandardCharsets.UTF_8),
                    new PrintStream(rejectedErr, true, StandardCharsets.UTF_8));
            assertThat(rejected).isEqualTo(1);
            SecretChecks.absent(rejectedOut.toString(StandardCharsets.UTF_8), server.apiKey.plaintext());
            SecretChecks.absent(rejectedErr.toString(StandardCharsets.UTF_8), server.apiKey.plaintext());
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ByteArrayOutputStream downErr = new ByteArrayOutputStream();
        int down = new CrabCommand().run(
                new String[] {"mcp-stdio", "--url", "http://127.0.0.1:" + port + "/mcp"},
                name -> "HERMIT_API_KEY".equals(name) ? "not-a-real-key" : null,
                InputStream.nullInputStream(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(downErr, true, StandardCharsets.UTF_8));
        assertThat(down).isEqualTo(2);
        assertThat(downErr.toString(StandardCharsets.UTF_8)).contains("crab is not running");
        SecretChecks.absent(downErr.toString(StandardCharsets.UTF_8), "not-a-real-key");
    }

    private static Reply call(Harness server, String key, String session, String name, Map<String, Object> arguments) throws Exception {
        return server.post(key, session, null, request(9, "tools/call", Map.of("name", name, "arguments", arguments)));
    }

    private static Reply callUnchecked(Harness server, String key, String session, String name, Map<String, Object> arguments) {
        try {
            return call(server, key, session, name, arguments);
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getClass().getName());
        }
    }

    private static JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("missing tool");
    }

    private static List<String> names(JsonNode tools) {
        ArrayList<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static JsonNode toolError(Reply reply) {
        return ApiJson.tree(reply.json().path("result").path("content").get(0).path("text").asText());
    }

    private static String initialize(String capabilitiesJson) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":%s,"clientInfo":{"name":"test","version":"0"}}}
                """.formatted(capabilitiesJson).trim();
    }

    private static String request(int id, String method, Object params) {
        if (params == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + ApiJson.write(params) + "}";
    }

    private static String readOnlyShell(String shell, String action, String title) {
        return """
                shell: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: %s
                description: 测试 Shell
                credentials: {}
                actions:
                  %s:
                    title: %s
                    description: 只读查询
                    input:
                      q: { type: string, description: 查询词 }
                    steps:
                      - set:
                          marker: true
                    output:
                      marker: { type: boolean, description: 标记 }
                    examples:
                      - input: { q: a }
                        output: { marker: true }
                    notes: 只读
                    idempotent: true
                    mcp: { read_only: true }
                """.formatted(shell, title, action, title);
    }

    private static String bulkYaml() {
        StringBuilder yaml = new StringBuilder("""
                shell: bulk_shell
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: "http://127.0.0.1:9"
                title: 批量
                description: 用于目录模式的测试 Shell。
                credentials: {}
                actions:
                  act_w:
                    title: 写入确认项
                    description: 写入一条测试记录
                    method: POST
                    input:
                      text: { type: string, description: 正文说明 }
                    steps:
                      - set:
                          saved: true
                    output:
                      saved: { type: boolean, description: 是否写入 }
                    examples:
                      - input: { text: hi }
                        output: { saved: true }
                    notes: 需要确认后才执行
                    mcp: { read_only: false }
                """);
        for (int i = 0; i < 40; i++) {
            yaml.append("""
                      act_%02d:
                        title: 只读项 %d
                        description: 只读查询 %d
                        input:
                          q: { type: string, description: 查询词 }
                        steps:
                          - set:
                              marker: true
                        output:
                          marker: { type: boolean, description: 标记 }
                        examples:
                          - input: { q: a }
                            output: { marker: true }
                        notes: 只读
                        idempotent: true
                        mcp: { read_only: true }
                    """.formatted(i, i, i));
        }
        return yaml.toString();
    }

    private static final class Harness implements AutoCloseable {
        final LegacyOaFixture fixture;
        final CrabRuntime runtime;
        final CrabHttpServer server;
        final CreatedApiKey apiKey;
        final String base;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        private Harness(LegacyOaFixture fixture, CrabRuntime runtime, CrabHttpServer server, CreatedApiKey apiKey) {
            this.fixture = fixture;
            this.runtime = runtime;
            this.server = server;
            this.apiKey = apiKey;
            this.base = "http://127.0.0.1:" + server.port();
        }

        static Harness open(Path root, String password) throws Exception {
            LegacyOaFixture fixture = new LegacyOaFixture(password);
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(root), CrabConfig.defaults().withListen("127.0.0.1", 0), name -> null);
            try {
                runtime.registry().put(ShellParser.parse(legacyYaml()));
                runtime.registry().put(ShellParser.parse(accountShell()));
                CreatedApiKey apiKey = runtime.admin().createApiKey(
                        "mcp", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
                CrabHttpServer server = new CrabHttpServer(runtime);
                server.start();
                return new Harness(fixture, runtime, server, apiKey);
            } catch (RuntimeException ex) {
                runtime.close();
                fixture.close();
                throw ex;
            }
        }

        void configure(String name) {
            if (runtime.admin().listAccounts("legacy_oa").stream().noneMatch(account -> account.account().equals(name))) {
                runtime.admin().createAccount("legacy_oa", name, Actors.CLI);
            }
            ShellDoc shell = runtime.registry().find("legacy_oa").orElseThrow();
            runtime.admin().putConfig(shell, "base_url", fixture.baseUrl(), Actors.CLI);
            runtime.admin().writeCredentials(shell, name, Map.of("username", name, "password", PASSWORD), Actors.CLI);
        }

        Reply post(String key, String session, String account, String body) throws Exception {
            return post(key, session, account, body, false);
        }

        Reply post(String key, String session, String account, String body, boolean headerKey) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (key != null) {
                if (headerKey) {
                    builder.header("X-Hermit-Key", key);
                } else {
                    builder.header("Authorization", "Bearer " + key);
                }
            }
            if (session != null) {
                builder.header("Mcp-Session-Id", session);
            }
            if (account != null) {
                builder.header("X-Hermit-Account", account);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        }

        @Override
        public void close() {
            server.close();
            runtime.close();
            fixture.close();
        }
    }

    private static String legacyYaml() throws Exception {
        String sample;
        try (InputStream in = McpHttpTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String action = """
                save_note:
                  title: 保存备注
                  description: 保存一条备注。
                  method: POST
                  input:
                    text: { type: string, description: 备注正文 }
                  steps:
                    - set:
                        saved: true
                  output:
                    saved: { type: boolean, description: 是否已保存 }
                  examples:
                    - input: { text: hello }
                      output: { saved: true }
                  notes: 测试写能力。
                  mcp: { read_only: false }
                """.stripIndent().indent(2);
        return sample.replace("health_check:", action + "health_check:");
    }

    private static String accountShell() {
        return """
                shell: account_shell
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: "http://127.0.0.1:9"
                title: 账号冲突
                description: 参数名 account 与选择账号冲突。
                credentials: {}
                actions:
                  lookup:
                    title: 按业务账号查询
                    description: 查询一个业务账号
                    input:
                      account: { type: string, description: 业务账号, required: true }
                    steps:
                      - set:
                          seen: "{{input.account}}"
                    output:
                      seen: { type: string, description: 回显的业务账号 }
                    examples:
                      - input: { account: biz }
                        output: { seen: biz }
                    notes: 选择 Hermit 账号时用 _account
                    idempotent: true
                    mcp: { read_only: true }
                  hidden:
                    title: 不暴露
                    description: 这个能力不对 MCP 暴露
                    input:
                      q: { type: string, description: 查询词 }
                    steps:
                      - set:
                          marker: true
                    output:
                      marker: { type: boolean, description: 标记 }
                    examples:
                      - input: { q: a }
                        output: { marker: true }
                    notes: 隐藏
                    idempotent: true
                    mcp: { expose: false, read_only: true }
                """;
    }

    private record Reply(int status, String body, Map<String, List<String>> headers) {
        JsonNode json() {
            return ApiJson.tree(body);
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static final class Sse implements AutoCloseable {
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final InputStream body;
        private final Thread reader;

        private Sse(InputStream body, Thread reader) {
            this.body = body;
            this.reader = reader;
        }

        static Sse open(Harness server, String session) throws Exception {
            return open(server, session, server.apiKey.plaintext());
        }

        static Sse open(Harness server, String session, String key) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.base + "/mcp"))
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", session)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = server.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new AssertionError("sse status " + response.statusCode());
            }
            InputStream body = response.body();
            Thread reader = new Thread(() -> {
                try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        SseHolder.add(line);
                    }
                } catch (Exception ex) {
                    SseHolder.add("");
                }
            }, "mcp-sse-test");
            Sse sse = new Sse(body, reader);
            SseHolder.bind(sse);
            reader.setDaemon(true);
            reader.start();
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
            reader.join(2_000);
        }
    }

    /** The reader thread is created before the Sse instance can capture itself. */
    private static final class SseHolder {
        private static Sse current;

        static void bind(Sse sse) {
            current = sse;
        }

        static void add(String line) {
            if (current != null && line != null) {
                current.lines.add(line);
            }
        }
    }

    private static final class LogBag extends Handler {
        private final StringBuilder text = new StringBuilder();

        private LogBag() {
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() != null) {
                text.append(record.getMessage()).append('\n');
            }
            if (record.getThrown() != null) {
                text.append(record.getThrown().getClass().getName()).append('\n');
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String text() {
            return text.toString();
        }
    }

    private static final class SessionServer implements AutoCloseable {
        final CrabRuntime runtime;
        final McpEndpoint endpoint;
        final Javalin app;
        final CreatedApiKey apiKey;
        final String base;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        private SessionServer(CrabRuntime runtime, McpEndpoint endpoint, Javalin app, CreatedApiKey apiKey) {
            this.runtime = runtime;
            this.endpoint = endpoint;
            this.app = app;
            this.apiKey = apiKey;
            this.base = "http://127.0.0.1:" + app.port();
        }

        static SessionServer open(Path root, Clock clock, int maxSessions) {
            CrabRuntime runtime = CrabRuntime.open(
                    new CrabPaths(root), CrabConfig.defaults().withListen("127.0.0.1", 0), name -> null);
            McpEndpoint endpoint = new McpEndpoint(runtime, clock, McpEndpoint.IDLE_LIMIT, maxSessions);
            Javalin app = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.http.maxRequestSize = RequestBodies.MAX_BYTES;
            });
            endpoint.register(app);
            try {
                CreatedApiKey apiKey = runtime.admin().createApiKey(
                        "mcp-sessions", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
                app.start("127.0.0.1", 0);
                return new SessionServer(runtime, endpoint, app, apiKey);
            } catch (RuntimeException ex) {
                endpoint.close();
                runtime.close();
                throw ex;
            }
        }

        String initialize(String key) throws Exception {
            Reply reply = post(key, null, McpHttpTest.initialize("{}"));
            if (reply.status() != 200 || reply.header("Mcp-Session-Id") == null) {
                throw new AssertionError("initialize failed: " + reply.status() + " " + reply.body());
            }
            return reply.header("Mcp-Session-Id");
        }

        Reply ping(String key, String session) throws Exception {
            return post(key, session, McpHttpTest.request(1, "ping", null));
        }

        private Reply post(String key, String session, String body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (session != null) {
                builder.header("Mcp-Session-Id", session);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        }

        @Override
        public void close() {
            endpoint.close();
            app.stop();
            runtime.close();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
