package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.sync.LocalConchException;
import dev.hermit.crab.sync.LocalConches;
import io.javalin.Javalin;

class McpRefreshTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "crm-token-aaaa";
    private static final String SESSION = "upstream-session-fixed-77";
    private static final String INSTRUCTIONS = "SECRET-INSTRUCTIONS";

    @TempDir
    Path temp;

    @Test
    void refreshRewritesLocalPackageAndTrialRecordsJsonRpc() throws Exception {
        try (Upstream upstream = new Upstream()) {
            Instant started = Instant.now().minusSeconds(2);
            Lab lab = Lab.open(temp.resolve("crab"));
            try {
                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                lab.runtime.localConches().importBytes(mcpYaml(upstream.base()).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
                lab.runtime.admin().createAccount("vendor_crm", "alice", Actors.CONSOLE);
                ConchDoc doc = lab.runtime.registry().find("vendor_crm").orElseThrow();
                lab.runtime.admin().writeCredentials(doc, "alice", Map.of("token", TOKEN), Actors.CONSOLE);
                int queued = count(lab.runtime, "SELECT COUNT(*) FROM report_queue");

                Reply refresh = lab.post("/console/api/local-conches/vendor_crm/mcp/refresh?account=alice", "{}");
                assertThat(refresh.status).as(refresh.body).isEqualTo(200);
                JsonNode data = refresh.json.path("data");
                assertThat(data.path("version").asText()).isEqualTo("1.4.2");
                assertThat(data.path("traceId").asText()).isEqualTo(refresh.json.path("traceId").asText());
                assertThat(texts(data.path("added"))).contains("gzip_file", "purge_file");
                assertThat(texts(data.path("removed"))).containsExactly("create_ticket");
                assertThat(texts(data.path("changed"))).containsExactly("search_customer");
                assertThat(upstream.initializes.get()).isGreaterThan(0);
                assertThat(upstream.emptyCapabilities).isTrue();
                assertThat(upstream.lists.get()).isEqualTo(2);

                ConchDoc updated = ConchParser.parse(new String(
                        lab.runtime.localConches().export("vendor_crm").bytes(), StandardCharsets.UTF_8));
                assertThat(updated.version().toString()).isEqualTo("1.4.2");
                assertThat(Instant.parse(updated.snapshot().fetchedAt())).isAfter(started);
                assertThat(updated.snapshot().protocolVersion()).isEqualTo("2025-11-25");
                assertThat(updated.snapshot().server().name()).isEqualTo("crm-mcp");
                assertThat(updated.snapshot().toolsSha256()).isEqualTo(ToolSnapshots.toolsSha256(List.of(
                        searchTool(), adminTool(), gzipTool(), purgeTool())));
                var search = updated.actions().get("search_customer");
                assertThat(search.description()).isEqualTo("按名称或手机号搜索客户。");
                assertThat(search.title()).isEqualTo("搜索客户");
                assertThat(search.notes()).isEqualTo("只读查询。");
                assertThat(search.idempotent()).isTrue();
                assertThat(search.mcp().readOnly()).isTrue();
                assertThat(search.mcp().destructive()).isFalse();
                assertThat(search.tool().confirm()).isFalse();
                assertThat(search.tool().inputSchema()).containsKey("properties");
                assertThat(search.tool().schemaSha256()).isEqualTo(
                        ToolSnapshots.schemaSha256(search.tool().inputSchema(), search.tool().outputSchema()));
                assertThat(updated.actions().get("gzip_file").description()).contains("https://example.test/a")
                        .doesNotContain("\u001B").doesNotContain("\u200B");
                assertThat(updated.actions().get("gzip_file").mcp().destructive()).isFalse();
                assertThat(updated.actions().get("purge_file").mcp().destructive()).isTrue();
                assertThat(updated.actions().get("purge_file").tool().confirm()).isTrue();
                assertThat(updated.actions()).doesNotContainKey("admin_drop");

                String traceId = data.path("traceId").asText();
                String trace = lab.runtime.devTraces().find(traceId).orElseThrow().body();
                assertThat(trace).contains("\"kind\":\"jsonrpc\"").contains("initialize").contains("tools/list");
                assertThat(trace).doesNotContain(TOKEN).doesNotContain(SESSION).doesNotContain(INSTRUCTIONS);
                assertAbsentHeaders(ApiJson.tree(trace));
                assertThat(lab.runtime.callLogs().findByTraceId(traceId)).isEmpty();
                assertThat(count(lab.runtime, "SELECT COUNT(*) FROM report_queue")).isEqualTo(queued);

                Reply trial = lab.post("/console/api/local-conches/vendor_crm/actions/search_customer/run",
                        "{\"account\":\"alice\",\"input\":{\"q\":\"张三\"}}");
                assertThat(trial.status).as(trial.body).isEqualTo(200);
                assertThat(trial.json.path("data").path("ok").asBoolean()).isTrue();
                var call = lab.runtime.callLogs().list("vendor_crm", "search_customer", "CONSOLE", 5).get(0);
                assertThat(call.source()).isEqualTo("LOCAL");
                assertThat(call.channel()).isEqualTo("CONSOLE");
                assertThat(call.apiKeyId()).isNull();
                String trialTrace = lab.runtime.devTraces().find(trial.json.path("traceId").asText()).orElseThrow().body();
                assertThat(trialTrace).contains("tools/call").doesNotContain(TOKEN).doesNotContain(SESSION);

                int callsBefore = upstream.calls.get();
                Reply destructive = lab.post("/console/api/local-conches/vendor_crm/actions/purge_file/run",
                        "{\"account\":\"alice\",\"input\":{}}");
                assertThat(destructive.status).as(destructive.body).isEqualTo(200);
                assertThat(upstream.calls.get()).isGreaterThan(callsBefore);

                CreatedApiKey key = lab.runtime.admin().createApiKey(
                        "local", new ApiKeyScope(Set.of("vendor_crm"), Set.of(), false), Actors.CONSOLE);
                String session = mcpInitialize(lab, key.plaintext());
                Reply called = mcp(lab, key.plaintext(), session, "vendor_crm__search_customer");
                assertThat(called.status).as(called.body).isEqualTo(200);
                assertThat(called.json.path("result").path("isError").asBoolean(false)).isFalse();
                String apiTrace = lab.runtime.devTraces().find(called.traceId()).orElseThrow().body();
                assertThat(apiTrace).contains("tools/call").doesNotContain(key.plaintext()).doesNotContain(TOKEN)
                        .doesNotContain(SESSION);
            } finally {
                lab.close();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = new CrabCommand().run(
                    new String[] {"mcp", "refresh", "vendor_crm", "--account", "alice"},
                    name -> "HERMIT_HOME".equals(name) ? temp.resolve("crab").toString() : null,
                    new ByteArrayInputStream(new byte[0]),
                    new PrintStream(out),
                    new PrintStream(err));
            assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
            String printed = out.toString(StandardCharsets.UTF_8);
            assertThat(printed).contains("version=1.4.2").contains("added=\n").contains("removed=\n").contains("changed=\n")
                    .contains("traceId=");
        }
    }

    @Test
    void gatesRejectBeforeUpstreamAndKeepTheFile() throws Exception {
        try (Upstream upstream = new Upstream()) {
            Lab lab = Lab.open(temp.resolve("gates"));
            try {
                byte[] yaml = mcpYaml(upstream.base()).getBytes(StandardCharsets.UTF_8);
                lab.runtime.localConches().importBytes(yaml, Actors.CONSOLE);
                lab.runtime.admin().createAccount("vendor_crm", "alice", Actors.CONSOLE);
                lab.runtime.admin().writeCredentials(
                        lab.runtime.registry().find("vendor_crm").orElseThrow(), "alice", Map.of("token", TOKEN), Actors.CONSOLE);
                Reply off = lab.post("/console/api/local-conches/vendor_crm/mcp/refresh?account=alice", "{}");
                assertThat(off.status).isEqualTo(409);
                assertThat(off.json.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
                assertThat(off.json.path("error").path("message").asText()).isEqualTo("先打开调试开关。");
                assertThat(upstream.initializes.get()).isZero();
                assertThat(lab.runtime.devTraces().count()).isZero();

                lab.runtime.registry().put(ConchParser.parse(mcpYaml(upstream.base()).replace("conch: vendor_crm", "conch: signed_crm")));
                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                Reply official = lab.post("/console/api/local-conches/signed_crm/mcp/refresh", "{}");
                assertThat(official.status).isEqualTo(409);
                assertThat(official.json.path("error").path("code").asText()).isEqualTo("CONFLICT");
                assertThat(official.json.path("error").path("message").asText()).isEqualTo("先在本地包上开发，再导出上传。");
                assertThat(upstream.initializes.get()).isZero();
                assertThat(lab.runtime.devTraces().count()).isZero();

                lab.runtime.localConches().importBytes(httpYaml().getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
                lab.runtime.admin().createAccount("local_http", "alice", Actors.CONSOLE);
                Reply http = lab.post("/console/api/local-conches/local_http/mcp/refresh?account=alice", "{}");
                assertThat(http.status).isEqualTo(409);
                assertThat(http.json.path("error").path("message").asText()).isEqualTo("只支持刷新 MCP 规则包。");

                lab.runtime.localConches().importBytes(oauthYaml().getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
                lab.runtime.admin().createAccount("vendor_oauth", "alice", Actors.CONSOLE);
                Reply oauth = lab.post("/console/api/local-conches/vendor_oauth/mcp/refresh?account=alice", "{}");
                assertThat(oauth.status).isEqualTo(409);
                assertThat(oauth.json.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
                assertThat(oauth.json.path("error").path("message").asText()).isEqualTo(McpForwarder.OAUTH_NOT_CONNECTED);
                assertThat(upstream.initializes.get()).isZero();
                assertThat(Files.readAllBytes(lab.runtime.paths().localConches().resolve("vendor_crm.yaml"))).isEqualTo(yaml);
            } finally {
                lab.close();
            }
        }
    }

    @Test
    void refreshUsesConnectedOauthTokenAndRetriesUnauthorized() throws Exception {
        try (OauthUpstream upstream = new OauthUpstream()) {
            Lab lab = Lab.open(temp.resolve("oauth-refresh"));
            try {
                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                lab.runtime.localConches().importBytes(
                        oauthConnectedYaml(upstream.base()).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
                lab.runtime.admin().createAccount("vendor_oauth", "alice", Actors.CONSOLE);
                Reply start = lab.post("/console/api/conches/vendor_oauth/accounts/alice/oauth/start", "{}");
                assertThat(start.status).isEqualTo(200);
                String state = query(start.json.path("data").path("authorizationUrl").asText(), "state");
                Reply callback = lab.get("/console/oauth/callback?code=code-alice&state=" + state);
                assertThat(callback.status).isEqualTo(200);
                assertThat(callback.body).contains(McpOAuth.CALLBACK_OK);
                assertThat(upstream.grants).containsExactly("authorization_code");

                upstream.listUnauthorized.set(1);
                Reply refresh = lab.post("/console/api/local-conches/vendor_oauth/mcp/refresh?account=alice", "{}");
                assertThat(refresh.status).isEqualTo(200);
                JsonNode data = refresh.json.path("data");
                assertThat(data.path("version").asText()).isEqualTo("1.0.0");
                assertThat(data.path("added")).isEmpty();
                assertThat(data.path("removed")).isEmpty();
                assertThat(data.path("changed")).isEmpty();
                assertThat(upstream.listAuthorizations).containsExactly(
                        "Bearer access-1-aaaa", "Bearer access-2-aaaa");
                assertThat(upstream.grants).containsExactly("authorization_code", "refresh_token");
                String trace = lab.runtime.devTraces().find(data.path("traceId").asText()).orElseThrow().body();
                assertThat(trace).contains("tools/list").doesNotContain("access-1-aaaa", "access-2-aaaa", "refresh-1-bbbb");
                assertThat(refresh.body).doesNotContain("access-1-aaaa", "access-2-aaaa", "refresh-1-bbbb");
                assertThat(lab.runtime.callLogs().findByTraceId(data.path("traceId").asText())).isEmpty();
                byte[] written = Files.readAllBytes(lab.runtime.paths().localConches().resolve("vendor_oauth.yaml"));

                upstream.listUnauthorized.set(8);
                Reply rejected = lab.post("/console/api/local-conches/vendor_oauth/mcp/refresh?account=alice", "{}");
                assertThat(rejected.status).isEqualTo(422);
                assertThat(rejected.json.path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
                assertThat(rejected.json.path("error").path("message").asText()).isEqualTo(McpOAuth.REJECTED);
                assertThat(rejected.body).doesNotContain("access-", "refresh-");
                assertThat(Files.readAllBytes(lab.runtime.paths().localConches().resolve("vendor_oauth.yaml"))).isEqualTo(written);
                assertThat(count(lab.runtime, "SELECT COUNT(*) FROM call_logs")).isZero();
            } finally {
                lab.close();
            }
        }
    }

    @Test
    void emptySkeletonImportsOnlyWhileDebugIsOn() throws Exception {
        try (Upstream upstream = new Upstream()) {
            Lab lab = Lab.open(temp.resolve("skeleton"));
            try {
                byte[] skeleton = skeletonYaml(upstream.base()).getBytes(StandardCharsets.UTF_8);
                assertThatThrownBy(() -> lab.runtime.registry().put(ConchParser.parse(new String(skeleton, StandardCharsets.UTF_8))))
                        .isInstanceOf(ConchRejectedException.class);
                assertThatThrownBy(() -> lab.runtime.localConches().importBytes(skeleton, Actors.CONSOLE))
                        .isInstanceOf(LocalConchException.class)
                        .matches(ex -> ((LocalConchException) ex).errors().stream()
                                .anyMatch(issue -> ValidationCodes.MCP_NO_ACTIONS.equals(issue.code())));
                assertThat(Files.exists(lab.runtime.paths().localConches().resolve("vendor_crm.yaml"))).isFalse();
                assertThat(count(lab.runtime, "SELECT COUNT(*) FROM accounts")).isZero();

                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                LocalConches.ImportResult imported = lab.runtime.localConches().importBytes(skeleton, Actors.CONSOLE);
                assertThat(imported.warnings()).extracting(issue -> issue.code()).contains(ValidationCodes.MCP_NO_ACTIONS);
                assertThat(count(lab.runtime, "SELECT COUNT(*) FROM accounts")).isZero();
                lab.runtime.admin().createAccount("vendor_crm", "alice", Actors.CONSOLE);
                assertThat(text(lab.runtime, "SELECT origin FROM accounts WHERE conch = 'vendor_crm'")).isEqualTo("LOCAL");
                lab.runtime.admin().writeCredentials(
                        lab.runtime.registry().find("vendor_crm").orElseThrow(), "alice", Map.of("token", TOKEN), Actors.CONSOLE);
                Reply refresh = lab.post("/console/api/local-conches/vendor_crm/mcp/refresh?account=alice", "{}");
                assertThat(refresh.status).as(refresh.body).isEqualTo(200);
                assertThat(lab.runtime.registry().find("vendor_crm").orElseThrow().actions()).isNotEmpty();
                assertThat(lab.runtime.registry().find("vendor_crm").orElseThrow().version().toString()).isEqualTo("1.0.0");
            } finally {
                lab.close();
            }
        }
    }

    @Test
    void upstreamLimitsFailWithoutWritingYaml() throws Exception {
        try (Upstream upstream = new Upstream()) {
            Lab lab = Lab.open(temp.resolve("limits"));
            try {
                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                byte[] yaml = mcpYaml(upstream.base()).getBytes(StandardCharsets.UTF_8);
                lab.runtime.localConches().importBytes(yaml, Actors.CONSOLE);
                lab.runtime.admin().createAccount("vendor_crm", "alice", Actors.CONSOLE);
                lab.runtime.admin().writeCredentials(
                        lab.runtime.registry().find("vendor_crm").orElseThrow(), "alice", Map.of("token", TOKEN), Actors.CONSOLE);
                upstream.mode = Upstream.Mode.STUCK;
                Reply stuck = lab.post("/console/api/local-conches/vendor_crm/mcp/refresh?account=alice", "{}");
                assertThat(stuck.status).isEqualTo(502);
                assertThat(stuck.json.path("error").path("message").asText()).isEqualTo(McpUpstreamSessions.CURSOR_STUCK);
                assertThat(Files.readAllBytes(lab.runtime.paths().localConches().resolve("vendor_crm.yaml"))).isEqualTo(yaml);
                assertThat(lab.runtime.devTraces().find(stuck.json.path("traceId").asText()).orElseThrow().body())
                        .contains("tools/list").doesNotContain(SESSION);
                assertThat(lab.runtime.callLogs().findByTraceId(stuck.json.path("traceId").asText())).isEmpty();

                upstream.mode = Upstream.Mode.TOO_MANY;
                upstream.lists.set(0);
                Reply many = lab.post("/console/api/local-conches/vendor_crm/mcp/refresh?account=alice", "{}");
                assertThat(many.status).isEqualTo(502);
                assertThat(many.json.path("error").path("message").asText()).isEqualTo(McpUpstreamSessions.TOO_MANY_TOOLS);
                assertThat(Files.readAllBytes(lab.runtime.paths().localConches().resolve("vendor_crm.yaml"))).isEqualTo(yaml);

                upstream.mode = Upstream.Mode.MANY_PAGES;
                upstream.lists.set(0);
                Reply pages = lab.post("/console/api/local-conches/vendor_crm/mcp/refresh?account=alice", "{}");
                assertThat(pages.status).isEqualTo(502);
                assertThat(pages.json.path("error").path("message").asText()).isEqualTo(McpUpstreamSessions.TOO_MANY_PAGES);
                assertThat(upstream.lists.get()).isEqualTo(20);
                assertThat(Files.readAllBytes(lab.runtime.paths().localConches().resolve("vendor_crm.yaml"))).isEqualTo(yaml);
            } finally {
                lab.close();
            }
        }
    }

    @Test
    void apiKeyTraceFollowsDebugAndSkipsOfficialPackages() throws Exception {
        try (Upstream upstream = new Upstream()) {
            Lab lab = Lab.open(temp.resolve("keys"));
            try {
                lab.runtime.localConches().importBytes(mcpYaml(upstream.base()).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
                lab.runtime.admin().createAccount("vendor_crm", "alice", Actors.CONSOLE);
                lab.runtime.admin().writeCredentials(
                        lab.runtime.registry().find("vendor_crm").orElseThrow(), "alice", Map.of("token", TOKEN), Actors.CONSOLE);
                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                CreatedApiKey local = lab.runtime.admin().createApiKey(
                        "local", new ApiKeyScope(Set.of("vendor_crm"), Set.of(), false), Actors.CONSOLE);
                String session = mcpInitialize(lab, local.plaintext());
                Reply called = mcp(lab, local.plaintext(), session, "vendor_crm__search_customer");
                assertThat(called.status).as(called.body).isEqualTo(200);
                assertThat(lab.runtime.devTraces().find(called.traceId())).isPresent();

                lab.runtime.debug().setEnabled(false, Actors.CONSOLE);
                Reply quiet = mcp(lab, local.plaintext(), session, "vendor_crm__search_customer");
                assertThat(quiet.status).as(quiet.body).isEqualTo(200);
                assertThat(lab.runtime.devTraces().count()).isZero();

                ConchDoc official = ConchParser.parse(mcpYaml(upstream.base()).replace("conch: vendor_crm", "conch: signed_crm"));
                lab.runtime.registry().put(official);
                lab.runtime.admin().createAccount("signed_crm", "alice", Actors.CONSOLE);
                lab.runtime.admin().writeCredentials(official, "alice", Map.of("token", TOKEN), Actors.CONSOLE);
                lab.runtime.debug().setEnabled(true, Actors.CONSOLE);
                CreatedApiKey signed = lab.runtime.admin().createApiKey(
                        "signed", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CONSOLE);
                String signedSession = mcpInitialize(lab, signed.plaintext());
                int traces = lab.runtime.devTraces().count();
                Reply officialCall = mcp(lab, signed.plaintext(), signedSession, "signed_crm__search_customer");
                assertThat(officialCall.status).as(officialCall.body).isEqualTo(200);
                assertThat(lab.runtime.devTraces().count()).isEqualTo(traces);
            } finally {
                lab.close();
            }
        }
    }

    private static String mcpInitialize(Lab lab, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(lab.base + "/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                        """))
                .build();
        HttpResponse<String> response = lab.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private static Reply mcp(Lab lab, String key, String session, String tool) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of("name", tool, "arguments", Map.of())));
        HttpRequest request = HttpRequest.newBuilder(URI.create(lab.base + "/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + key)
                .header("Mcp-Session-Id", session)
                .header("X-Hermit-Account", "alice")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = lab.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body(), ApiJson.tree(response.body()),
                response.headers().firstValue("X-Hermit-Trace").orElse(""));
    }

    private static List<String> texts(JsonNode array) {
        ArrayList<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static int count(CrabRuntime runtime, String sql) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String text(CrabRuntime runtime, String sql) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void assertAbsentHeaders(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                assertThat(name.toLowerCase(java.util.Locale.ROOT))
                        .isNotIn("authorization", "cookie", "set-cookie", "mcp-session-id", "instructions");
                assertAbsentHeaders(node.get(name));
            });
        } else if (node.isArray()) {
            node.forEach(McpRefreshTest::assertAbsentHeaders);
        }
    }

    private static ObjectNode searchTool() {
        ObjectNode tool = baseTool("searchCustomer", "按名称或手机号搜索客户。");
        tool.set("inputSchema", objectSchema("q", "limit"));
        tool.set("outputSchema", JSON.createObjectNode().put("type", "object"));
        tool.putObject("annotations").put("readOnlyHint", true);
        return tool;
    }

    private static ObjectNode adminTool() {
        ObjectNode tool = baseTool("admin_drop", "不要导入");
        tool.set("inputSchema", objectSchema("id"));
        return tool;
    }

    private static ObjectNode gzipTool() {
        ObjectNode tool = baseTool("gzip-file", "打包\u001B[31m文件\u001B[0m\u200B。见 https://example.test/a");
        tool.put("title", "打包");
        tool.set("inputSchema", objectSchema("path"));
        ObjectNode hints = tool.putObject("annotations");
        hints.put("readOnlyHint", false);
        hints.put("destructiveHint", false);
        return tool;
    }

    private static ObjectNode purgeTool() {
        ObjectNode tool = baseTool("purge-file", "清掉一个文件");
        tool.set("inputSchema", JSON.createObjectNode().put("type", "object"));
        ObjectNode hints = tool.putObject("annotations");
        hints.put("readOnlyHint", false);
        hints.put("destructiveHint", true);
        return tool;
    }

    private static ObjectNode baseTool(String name, String description) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        return tool;
    }

    private static ObjectNode objectSchema(String... properties) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode fields = schema.putObject("properties");
        for (String property : properties) {
            fields.putObject(property).put("type", "string");
        }
        return schema;
    }

    private static String mcpYaml(String base) {
        return """
                conch: vendor_crm
                version: 1.4.2
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户
                description: 本地开发包。
                credentials:
                  token: {type: secret, label: 令牌, required: true, description: 令牌}
                upstream:
                  url: %s/mcp
                  transport: streamable_http
                  auth: {type: headers}
                  headers:
                    Authorization: 'Bearer {{credential.token}}'
                  timeout_ms: 30000
                tools:
                  include: ['*']
                  exclude: ['admin_*']
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称或手机号搜索客户。
                    mcp: {read_only: true, destructive: false}
                    confirm: false
                    idempotent: true
                    input_schema: {type: object}
                    output_schema: {type: object}
                    examples:
                      - {input: {q: 张三}, output: {ok: true}}
                    notes: 只读查询。
                    related: [search_customer]
                  create_ticket:
                    tool: createTicket
                    title: 创建工单
                    description: 将被删除。
                    mcp: {read_only: false, destructive: true}
                    confirm: true
                    input_schema: {type: object}
                """.formatted(base);
    }

    private static String skeletonYaml(String base) {
        return """
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户
                description: 还没有工具。
                credentials:
                  token: {type: secret, label: 令牌, required: true, description: 令牌}
                upstream:
                  url: %s/mcp
                  transport: streamable_http
                  auth: {type: headers}
                  headers:
                    Authorization: 'Bearer {{credential.token}}'
                tools:
                  exclude: ['admin_*']
                """.formatted(base);
    }

    private static String query(String url, String name) {
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(name);
    }

    private static String oauthConnectedYaml(String base) {
        return """
                conch: vendor_oauth
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统
                description: 上游 OAuth。
                credentials:
                  oauth_token: {type: secret, label: 访问令牌, required: true}
                upstream:
                  url: %s/mcp
                  transport: streamable_http
                  allowed_hosts: ["127.0.0.1"]
                  auth:
                    type: oauth
                    scopes: [mcp]
                    client_id: hermit-crab
                    authorization_endpoint: %s/authorize
                    token_endpoint: %s/token
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索。
                    mcp: {read_only: true, destructive: false}
                    confirm: false
                    input_schema:
                      type: object
                      properties:
                        q: {type: string}
                      required: [q]
                """.formatted(base, base, base);
    }

    private static String oauthYaml() {
        return """
                conch: vendor_oauth
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 标题
                description: 说明
                credentials: {}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth:
                    type: oauth
                    scopes: [mcp]
                    client_id: hermit-crab
                    authorization_endpoint: https://crm.intra.example.com/oauth/authorize
                    token_endpoint: https://crm.intra.example.com/oauth/token
                actions:
                  create_ticket:
                    tool: createTicket
                    title: 创建工单
                    description: 为客户创建服务工单。
                    mcp: {read_only: false, destructive: true}
                    confirm: true
                    input_schema: {type: object}
                """;
    }

    private static String httpYaml() {
        return """
                conch: local_http
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 本地探测
                description: 只在本机。
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                      - set:
                          marker: ok
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                """;
    }

    private record Reply(int status, String body, JsonNode json, String traceId) {
        Reply(int status, String body, JsonNode json) {
            this(status, body, json, "");
        }
    }

    private static final class Lab implements AutoCloseable {
        final CrabRuntime runtime;
        final CrabHttpServer server;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final String base;

        Lab(CrabRuntime runtime, CrabHttpServer server) {
            this.runtime = runtime;
            this.server = server;
            this.base = "http://127.0.0.1:" + server.port();
        }

        static Lab open(Path root) throws Exception {
            CrabRuntime runtime = CrabRuntime.open(
                    new CrabPaths(root), CrabConfig.defaults().withListen("127.0.0.1", 0), name -> null);
            CrabHttpServer server = new CrabHttpServer(runtime);
            server.start();
            return new Lab(runtime, server);
        }

        Reply post(String path, String body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(), ApiJson.tree(response.body()));
        }

        Reply get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-Hermit-Console", "1")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body();
            JsonNode json = body.startsWith("{") ? ApiJson.tree(body) : ApiJson.tree("{}");
            return new Reply(response.statusCode(), body, json);
        }

        @Override
        public void close() {
            server.close();
            runtime.close();
        }
    }

    private static final class Upstream implements AutoCloseable {
        enum Mode { NORMAL, STUCK, TOO_MANY, MANY_PAGES }

        final Javalin app;
        final List<String> sessions = new CopyOnWriteArrayList<>();
        final AtomicInteger initializes = new AtomicInteger();
        final AtomicInteger lists = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean emptyCapabilities = true;
        volatile Mode mode = Mode.NORMAL;

        Upstream() {
            app = Javalin.create(config -> config.showJavalinBanner = false);
            app.post("/mcp", this::post);
            app.delete("/mcp", ctx -> ctx.status(200));
            app.start(0);
        }

        String base() {
            return "http://127.0.0.1:" + app.port();
        }

        private void post(io.javalin.http.Context ctx) throws Exception {
            String authorization = ctx.header("Authorization");
            if (!("Bearer " + TOKEN).equals(authorization)) {
                ctx.status(401);
                return;
            }
            JsonNode node = JSON.readTree(ctx.body());
            String method = node.path("method").asText("");
            if ("initialize".equals(method)) {
                initializes.incrementAndGet();
                JsonNode capabilities = node.path("params").path("capabilities");
                if (!capabilities.isObject() || capabilities.size() != 0) {
                    emptyCapabilities = false;
                }
                sessions.add(SESSION);
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2025-11-25");
                result.putObject("capabilities");
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", "crm-mcp");
                info.put("version", "3.2.0");
                result.put("instructions", INSTRUCTIONS);
                write(ctx, node.get("id"), result);
                return;
            }
            if ("notifications/initialized".equals(method)) {
                ctx.status(202);
                return;
            }
            if ("tools/list".equals(method)) {
                int page = lists.incrementAndGet();
                write(ctx, node.get("id"), listResult(node.path("params").path("cursor").asText(""), page));
                return;
            }
            if ("tools/call".equals(method)) {
                calls.incrementAndGet();
                ObjectNode result = JSON.createObjectNode();
                result.putArray("content").addObject().put("type", "text").put("text", "找到了");
                result.putObject("structuredContent").put("ok", true);
                write(ctx, node.get("id"), result);
                return;
            }
            ctx.status(400);
        }

        private ObjectNode listResult(String cursor, int page) {
            ObjectNode result = JSON.createObjectNode();
            ArrayNode tools = result.putArray("tools");
            if (mode == Mode.TOO_MANY) {
                for (int i = 0; i < 501; i++) {
                    tools.add(baseTool("tool_" + i, "工具"));
                }
                return result;
            }
            if (mode == Mode.MANY_PAGES) {
                tools.add(baseTool("tool_" + page, "工具"));
                result.put("nextCursor", "page-" + page);
                return result;
            }
            if (mode == Mode.STUCK) {
                tools.add(baseTool("searchCustomer", "搜索"));
                result.put("nextCursor", "same");
                return result;
            }
            if (cursor.isBlank()) {
                tools.add(searchTool());
                tools.add(adminTool());
                result.put("nextCursor", "p2");
                return result;
            }
            tools.add(gzipTool());
            tools.add(purgeTool());
            return result;
        }

        private static void write(io.javalin.http.Context ctx, JsonNode id, ObjectNode result) throws Exception {
            ObjectNode message = JSON.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.set("id", id);
            message.set("result", result);
            ctx.status(200);
            ctx.contentType("application/json");
            ctx.header("Mcp-Session-Id", SESSION);
            ctx.result(JSON.writeValueAsString(message));
        }

        @Override
        public void close() {
            app.stop();
        }
    }

    private static final class OauthUpstream implements AutoCloseable {
        final Javalin app;
        final List<String> grants = new CopyOnWriteArrayList<>();
        final List<String> listAuthorizations = new CopyOnWriteArrayList<>();
        final AtomicInteger listUnauthorized = new AtomicInteger();
        private final AtomicInteger sequence = new AtomicInteger();

        OauthUpstream() {
            app = Javalin.create(config -> config.showJavalinBanner = false);
            app.post("/token", this::token);
            app.post("/mcp", this::mcp);
            app.delete("/mcp", ctx -> ctx.status(200));
            app.start(0);
        }

        String base() {
            return "http://127.0.0.1:" + app.port();
        }

        private void token(io.javalin.http.Context ctx) {
            String grant = "authorization_code";
            for (String pair : ctx.body().split("&")) {
                if (pair.startsWith("grant_type=")) {
                    grant = URLDecoder.decode(pair.substring("grant_type=".length()), StandardCharsets.UTF_8);
                }
            }
            grants.add(grant);
            int n = sequence.incrementAndGet();
            ctx.contentType("application/json");
            ctx.result("""
                    {"access_token":"access-%d-aaaa","refresh_token":"refresh-%d-bbbb","token_type":"Bearer","expires_in":7200}
                    """.formatted(n, n));
        }

        private void mcp(io.javalin.http.Context ctx) throws Exception {
            JsonNode node = JSON.readTree(ctx.body());
            String method = node.path("method").asText("");
            if ("notifications/initialized".equals(method)) {
                ctx.status(202);
                return;
            }
            if ("initialize".equals(method)) {
                ctx.status(200);
                ctx.header("Mcp-Session-Id", "oauth-session-fixed");
                ctx.contentType("application/json");
                ctx.result("""
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"oauth-mcp","version":"1.0.0"}}}
                        """.formatted(node.path("id").toString()));
                return;
            }
            if (!"tools/list".equals(method)) {
                ctx.status(400);
                return;
            }
            listAuthorizations.add(ctx.header("Authorization"));
            if (listUnauthorized.get() > 0) {
                listUnauthorized.decrementAndGet();
                ctx.status(401);
                return;
            }
            ctx.status(200);
            ctx.header("Mcp-Session-Id", "oauth-session-fixed");
            ctx.contentType("application/json");
            ctx.result("""
                    {"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"searchCustomer","description":"按名称搜索。","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]},"annotations":{"readOnlyHint":true,"destructiveHint":false}}]}}
                    """.formatted(node.path("id").toString()));
        }

        @Override
        public void close() {
            app.stop();
        }
    }
}
