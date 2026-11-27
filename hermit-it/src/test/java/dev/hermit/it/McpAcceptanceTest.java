package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.runtime.Invoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 已接通的 MCP 调用链。Reef 发布 {@code driver: mcp}，Crab 订阅并配置 headers 令牌，
 * 再经 MCP / HTTP 转发到本机 {@link MockMcpServer}。不含 OAuth、工具列表刷新和调试记录。
 */
class McpAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONCH = "desk_mcp";
    private static final String ADMIN_USER = "mcp-it-admin";
    private static final String ADMIN_PASSWORD = "mcp-it-admin-secret";
    private static final String AGENT_TOKEN = "mcp-it-agent-token";
    private static final String UPSTREAM_TOKEN = "mcp-it-upstream-token";
    private static final String V1_SHA = "a".repeat(64);
    private static final String V2_SHA = "b".repeat(64);
    private static final String V1_FETCHED = "2026-11-01T00:00:00Z";
    private static final String V2_FETCHED = "2026-11-05T08:00:00Z";
    private static final Duration POLL = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> secrets = new ArrayList<>(List.of(ADMIN_PASSWORD, AGENT_TOKEN, UPSTREAM_TOKEN));

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void publishedPackageForwardsThroughCrab() throws Exception {
        Path reefJar = Path.of(System.getProperty("hermit.reef.jar"));
        assertThat(reefJar).isRegularFile();

        try (MockMcpServer upstream = new MockMcpServer()) {
            int reefPort = freePort();
            Path reefData = temp.resolve("reef");
            Path reefLog = temp.resolve("reef.log");
            Process reef = startReef(reefJar, reefPort, reefData, reefLog);
            try {
                String reefBase = "http://127.0.0.1:" + reefPort;
                waitUntil(Duration.ofSeconds(90), () -> reef.isAlive() && loginStatus(reefBase) == 200, reef, reefLog);
                String token = login(reefBase);
                Path pub = temp.resolve("reef.pub");
                Files.writeString(pub, signingKey(reefBase, token));

                byte[] v1 = packageYaml(upstream.port(), "1.0.0", "echo", "把一句话原样送回。", V1_FETCHED, V1_SHA, "1.0.0")
                        .getBytes(StandardCharsets.UTF_8);
                upload(reefBase, token, v1);
                publish(reefBase, token, "1.0.0");
                assertPublished(reefBase, token, "1.0.0", "echo", V1_FETCHED, V1_SHA, "1.0.0", upstream.port());

                Path crabHome = temp.resolve("crab");
                Files.createDirectories(crabHome);
                Files.writeString(crabHome.resolve("config.yml"), """
                        reef:
                          url: %s
                          token: %s
                          public_key: %s
                        server:
                          host: 127.0.0.1
                          port: 0
                        mcp:
                          direct_limit: 40
                        sync:
                          interval_seconds: 1
                        report:
                          enabled: true
                          interval_seconds: 60
                        """.formatted(reefBase, AGENT_TOKEN, pub.toAbsolutePath()));
                Function<String, String> env = name -> "HERMIT_HOME".equals(name) ? crabHome.toString() : null;
                try (CrabServer crab = CrabServer.start(env)) {
                    String crabBase = "http://127.0.0.1:" + crab.port();
                    JsonNode subscribed = console(crabBase, "POST", "/console/api/conches/" + CONCH + "/subscription", "{}");
                    assertThat(subscribed.path("data").path("subscribed").asBoolean()).isTrue();
                    assertThat(subscribed.path("data").path("outcome").path("kind").asText())
                            .isIn("INSTALLED", "SKIPPED_SAME");
                    waitUntil(POLL, () -> versionEquals(crabBase, "1.0.0"));

                    console(crabBase, "POST", "/console/api/conches/" + CONCH + "/accounts", "{\"name\":\"alice\"}");
                    console(crabBase, "PUT", "/console/api/conches/" + CONCH + "/accounts/alice/credentials",
                            "{\"token\":\"" + UPSTREAM_TOKEN + "\"}");
                    String apiKey = createApiKey(crabBase, "{\"name\":\"caller\",\"all\":true}");
                    secrets.add(apiKey);

                    JsonNode openapi = json(get(crabBase, "/openapi.json", null));
                    JsonNode echoOp = openapi.path("paths").path("/api/" + CONCH + "/echo").path("post");
                    JsonNode ticketOp = openapi.path("paths").path("/api/" + CONCH + "/create_ticket").path("post");
                    assertThat(echoOp.path("x-hermit-driver").asText()).isEqualTo("mcp");
                    assertThat(ticketOp.path("x-hermit-driver").asText()).isEqualTo("mcp");
                    assertThat(parameter(echoOp, "X-Hermit-Confirm")).isNull();
                    assertThat(parameter(echoOp, "X-Hermit-Confirm-Token")).isNull();
                    assertThat(parameter(ticketOp, "X-Hermit-Confirm").path("required").asBoolean()).isTrue();
                    assertThat(parameter(ticketOp, "X-Hermit-Confirm-Token").path("required").asBoolean()).isTrue();

                    McpClient mcp = new McpClient(crabBase, apiKey);
                    JsonNode listed = mcp.call("tools/list", null);
                    JsonNode tools = listed.path("result").path("tools");
                    assertThat(toolNames(tools)).containsExactlyInAnyOrder(CONCH + "__echo", CONCH + "__create_ticket");
                    JsonNode echoTool = tool(tools, CONCH + "__echo");
                    JsonNode ticketTool = tool(tools, CONCH + "__create_ticket");
                    assertThat(echoTool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
                    assertThat(echoTool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
                    assertThat(ticketTool.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
                    assertThat(ticketTool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
                    assertThat(listed.toString()).doesNotContain("来源：");

                    JsonNode echoed = mcp.call("tools/call", callParams(CONCH + "__echo", "hello"));
                    assertThat(echoed.path("result").path("isError").asBoolean(false)).isFalse();
                    assertThat(echoed.path("result").path("structuredContent").path("text").asText()).isEqualTo("Echo: hello");
                    assertThat(echoed.path("result").path("content").get(0).path("text").asText()).isEqualTo("Echo: hello");
                    MockMcpServer.Hit forwarded = upstream.toolHits().get(0);
                    assertThat(forwarded.tool()).isEqualTo("echo");
                    assertThat(forwarded.authorization()).isEqualTo("Bearer " + UPSTREAM_TOKEN);
                    assertThat(forwarded.forwardedApiKey()).isFalse();
                    assertThat(forwarded.session()).isEqualTo("it-session-1");
                    assertThat(forwarded.protocol()).isEqualTo("2025-11-25");
                    assertThat(forwarded.accept()).contains("application/json").contains("text/event-stream");
                    assertThat(forwarded.body()).doesNotContain(apiKey).doesNotContain("hk_");
                    assertThat(upstream.initializes()).isEqualTo(1);

                    int beforeConfirm = upstream.toolHits().size();
                    HttpResponse<String> unconfirmed = postApi(
                            crabBase, "/api/" + CONCH + "/create_ticket", apiKey, "{}", null);
                    assertThat(unconfirmed.statusCode()).isEqualTo(409);
                    assertThat(json(unconfirmed).path("error").path("code").asText()).isEqualTo("CONFLICT");
                    String confirmToken = unconfirmed.headers().firstValue("X-Hermit-Confirm-Token").orElseThrow();
                    assertThat(json(unconfirmed).path("error").path("message").asText())
                            .isEqualTo(Invoker.confirmRequired(confirmToken));
                    assertThat(upstream.toolHits()).hasSize(beforeConfirm);

                    HttpResponse<String> confirmed = postApi(
                            crabBase, "/api/" + CONCH + "/create_ticket", apiKey, "{}", "true", confirmToken);
                    assertThat(confirmed.statusCode()).as(safe(confirmed.body())).isEqualTo(200);
                    assertThat(json(confirmed).path("success").asBoolean()).isTrue();
                    assertThat(json(confirmed).path("data").path("ticket").asText()).isEqualTo("T-1");
                    assertThat(upstream.toolHits()).hasSize(beforeConfirm + 1);
                    assertThat(upstream.toolHits().get(beforeConfirm).tool()).isEqualTo("create_ticket");
                    assertThat(upstream.toolHits().get(beforeConfirm).authorization()).isEqualTo("Bearer " + UPSTREAM_TOKEN);

                    // MCP 上的确认交给客户端的工具审批：没声明 elicitation 的客户端也能调敏感能力。
                    int beforeMcpWrite = upstream.toolHits().size();
                    JsonNode written = mcp.call("tools/call", callParams(CONCH + "__create_ticket", null));
                    assertThat(written.path("result").path("isError").asBoolean(false)).as(written.toString()).isFalse();
                    assertThat(upstream.toolHits()).hasSize(beforeMcpWrite + 1);

                    String readOnlyKey = createApiKey(crabBase, "{\"name\":\"reader\",\"all\":true,\"readOnly\":true}");
                    secrets.add(readOnlyKey);
                    int beforeReadOnly = upstream.toolHits().size();
                    HttpResponse<String> forbidden = postApi(
                            crabBase, "/api/" + CONCH + "/create_ticket", readOnlyKey, "{}", "true");
                    assertThat(forbidden.statusCode()).isEqualTo(403);
                    assertThat(json(forbidden).path("error").path("code").asText()).isEqualTo("FORBIDDEN");
                    assertThat(upstream.toolHits()).hasSize(beforeReadOnly);

                    HttpResponse<String> failed = postApi(
                            crabBase, "/api/" + CONCH + "/echo", apiKey,
                            "{\"message\":\"" + MockMcpServer.FAIL_MESSAGE + "\"}", null);
                    assertThat(failed.statusCode()).isEqualTo(502);
                    JsonNode failedBody = json(failed);
                    assertThat(failedBody.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
                    assertThat(failedBody.path("data").get(0).path("text").asText()).isEqualTo(MockMcpServer.FAILURE_TEXT);
                    assertThat(safe(failed.body())).doesNotContain(UPSTREAM_TOKEN);

                    JsonNode mcpFailed = mcp.call(
                            "tools/call", callParams(CONCH + "__echo", MockMcpServer.FAIL_MESSAGE));
                    assertThat(mcpFailed.path("result").path("isError").asBoolean()).isTrue();
                    assertThat(mcpFailed.path("result").path("content").get(0).path("text").asText())
                            .isEqualTo(MockMcpServer.FAILURE_TEXT);
                    assertThat(mcpFailed.path("result").path("structuredContent").path("error").path("code").asText())
                            .isEqualTo("UPSTREAM_ERROR");
                    assertThat(mcpFailed.path("result").path("structuredContent").path("error").path("upstreamToolError")
                            .asBoolean()).isTrue();

                    JsonNode sent = console(crabBase, "POST", "/console/api/reports/send", "{}");
                    assertThat(sent.path("data").path("accepted").asBoolean()).as(sent.toString()).isTrue();
                    String tideTo = Instant.now().plus(Duration.ofHours(1)).toString();
                    waitUntil(POLL, () -> tideFailure(reefBase, token, tideTo)
                            && alertKinds(reefBase, token).contains("UPSTREAM_ERROR_SURGE"));

                    byte[] v2 = packageYaml(upstream.port(), "1.1.0", "echo_v2", "按新包把一句话送回。", V2_FETCHED, V2_SHA, "1.1.0")
                            .getBytes(StandardCharsets.UTF_8);
                    upload(reefBase, token, v2);
                    publish(reefBase, token, "1.1.0");
                    assertPublished(reefBase, token, "1.1.0", "echo_v2", V2_FETCHED, V2_SHA, "1.1.0", upstream.port());
                    JsonNode diff = json(get(reefBase, "/api/admin/conches/" + CONCH + "/diff?from=1.0.0&to=1.1.0", token));
                    JsonNode echoChange = null;
                    for (JsonNode change : diff.path("data").path("toolChanges")) {
                        if ("echo".equals(change.path("name").asText())) {
                            echoChange = change;
                        }
                    }
                    assertThat(echoChange).isNotNull();
                    assertThat(echoChange.path("kind").asText()).isEqualTo("changed");
                    assertThat(echoChange.path("previousUpstreamName").asText()).isEqualTo("echo");
                    assertThat(echoChange.path("upstreamName").asText()).isEqualTo("echo_v2");
                    assertThat(diff.path("data").path("toolChanges").toString()).doesNotContain("create_ticket");

                    waitUntil(POLL, () -> updateAvailable(crabBase, "1.1.0"));
                    assertThat(conchVersion(crabBase)).isEqualTo("1.0.0");
                    int beforeOld = upstream.toolHits().size();
                    JsonNode stillOld = mcp.call("tools/call", callParams(CONCH + "__echo", "stay"));
                    assertThat(stillOld.path("result").path("structuredContent").path("text").asText()).isEqualTo("Echo: stay");
                    assertThat(upstream.toolHits().get(beforeOld).tool()).isEqualTo("echo");

                    JsonNode updated = console(crabBase, "POST", "/console/api/conches/" + CONCH + "/update", "{}");
                    assertThat(updated.path("data").path("outcome").path("kind").asText()).isEqualTo("INSTALLED");
                    assertThat(updated.path("data").path("outcome").path("version").asText()).isEqualTo("1.1.0");
                    assertThat(conchVersion(crabBase)).isEqualTo("1.1.0");
                    JsonNode revised = mcp.call("tools/call", callParams(CONCH + "__echo", "next"));
                    assertThat(revised.path("result").path("isError").asBoolean(false)).isFalse();
                    assertThat(revised.path("result").path("structuredContent").path("text").asText()).isEqualTo("Echo v2: next");
                    assertThat(upstream.toolHits().get(upstream.toolHits().size() - 1).tool()).isEqualTo("echo_v2");
                    assertThat(upstream.toolHits().get(upstream.toolHits().size() - 1).authorization())
                            .isEqualTo("Bearer " + UPSTREAM_TOKEN);
                    assertThat(upstream.deletes()).isGreaterThanOrEqualTo(1);
                    assertThat(upstream.initializes()).isGreaterThanOrEqualTo(2);
                    JsonNode relisted = mcp.call("tools/list", null);
                    assertThat(toolNames(relisted.path("result").path("tools")))
                            .containsExactlyInAnyOrder(CONCH + "__echo", CONCH + "__create_ticket");
                }
            } finally {
                reef.destroy();
                if (!reef.waitFor(10, TimeUnit.SECONDS)) {
                    reef.destroyForcibly();
                }
            }
        }
    }

    private void assertPublished(
            String base,
            String token,
            String version,
            String echoUpstream,
            String fetchedAt,
            String toolsSha,
            String serverVersion,
            int port
    ) throws Exception {
        JsonNode list = json(get(base, "/api/admin/conches", token));
        JsonNode summary = null;
        for (JsonNode row : list.path("data")) {
            if (CONCH.equals(row.path("conch").asText())) {
                summary = row;
            }
        }
        assertThat(summary).isNotNull();
        assertThat(summary.path("driver").asText()).isEqualTo("mcp");
        assertThat(summary.path("version").asText()).isEqualTo(version);
        assertThat(summary.path("upstream").path("auth").path("type").asText()).isEqualTo("headers");
        assertThat(summary.path("upstream").path("url").asText()).isEqualTo("http://127.0.0.1:" + port + "/mcp");
        assertThat(tool(summary.path("tools"), "echo").path("upstreamName").asText()).isEqualTo(echoUpstream);

        JsonNode detail = json(get(base, "/api/admin/conches/" + CONCH, token)).path("data");
        assertThat(detail.path("driver").asText()).isEqualTo("mcp");
        assertThat(detail.path("version").asText()).isEqualTo(version);
        JsonNode upstream = detail.path("upstream");
        assertThat(upstream.path("url").asText()).isEqualTo("http://127.0.0.1:" + port + "/mcp");
        assertThat(upstream.path("transport").asText()).isEqualTo("streamable_http");
        assertThat(upstream.path("auth").path("type").asText()).isEqualTo("headers");
        assertThat(upstream.path("allowedHosts").get(0).asText()).isEqualTo("127.0.0.1");
        assertThat(upstream.toString()).doesNotContain(UPSTREAM_TOKEN).doesNotContain("Authorization");
        JsonNode snapshot = detail.path("snapshot");
        assertThat(snapshot.path("fetchedAt").asText()).isEqualTo(fetchedAt);
        assertThat(snapshot.path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(snapshot.path("toolsSha256").asText()).isEqualTo(toolsSha);
        assertThat(snapshot.path("server").path("name").asText()).isEqualTo("mock-mcp");
        assertThat(snapshot.path("server").path("version").asText()).isEqualTo(serverVersion);
        JsonNode echo = tool(detail.path("tools"), "echo");
        JsonNode ticket = tool(detail.path("tools"), "create_ticket");
        assertThat(echo.path("upstreamName").asText()).isEqualTo(echoUpstream);
        assertThat(echo.path("readOnly").asBoolean()).isTrue();
        assertThat(echo.path("destructive").asBoolean()).isFalse();
        assertThat(ticket.path("upstreamName").asText()).isEqualTo("create_ticket");
        assertThat(ticket.path("readOnly").asBoolean()).isFalse();
        assertThat(ticket.path("destructive").asBoolean()).isTrue();

        HttpResponse<String> manifestResponse = get(base, "/agent/conches/manifest", null, AGENT_TOKEN);
        assertThat(manifestResponse.statusCode()).as(safe(manifestResponse.body())).isEqualTo(200);
        JsonNode manifest = json(manifestResponse).path("data");
        JsonNode entry = null;
        for (JsonNode row : manifest) {
            if (CONCH.equals(row.path("conch").asText())) {
                entry = row;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("version").asText()).isEqualTo(version);
        assertThat(entry.path("driver").asText()).isEqualTo("mcp");
        assertThat(entry.path("upstreamHost").asText()).isEqualTo("127.0.0.1");
    }

    private static ObjectNode callParams(String name, String message) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        ObjectNode arguments = params.putObject("arguments");
        if (message != null) {
            arguments.put("message", message);
        }
        return params;
    }

    private static String packageYaml(
            int port,
            String version,
            String echoTool,
            String echoDescription,
            String fetchedAt,
            String toolsSha,
            String serverVersion
    ) {
        return """
                conch: desk_mcp
                version: %s
                driver: mcp
                min_crab_version: 1.1.0
                title: 模拟上游
                description: 验收用的本机 MCP 服务，Hermit 只做权限和转发。
                credentials:
                  token: {type: secret, label: 令牌, required: true}
                upstream:
                  url: http://127.0.0.1:%d/mcp
                  transport: streamable_http
                  auth: {type: headers}
                  headers:
                    Authorization: "Bearer {{credential.token}}"
                snapshot:
                  fetched_at: %s
                  protocol_version: 2025-11-25
                  tools_sha256: %s
                  server: {name: mock-mcp, version: "%s"}
                actions:
                  echo:
                    tool: %s
                    title: 回声
                    description: %s
                    mcp: {read_only: true}
                    input_schema:
                      type: object
                      properties:
                        message: {type: string}
                      required: [message]
                  create_ticket:
                    tool: create_ticket
                    title: 创建工单
                    description: 创建一张工单。
                    mcp: {read_only: false, destructive: true}
                    input_schema: {type: object}
                """.formatted(version, port, fetchedAt, toolsSha, serverVersion, echoTool, echoDescription);
    }

    private Process startReef(Path jar, int port, Path dataDir, Path log) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "-jar",
                jar.toString(),
                "--server.port=" + port,
                "--hermit.reef.data-dir=" + dataDir,
                "--hermit.reef.tide.quiet-hours=0",
                "--hermit.reef.tide.surge-min-count=1",
                "--hermit.reef.tide.evaluate-cron=*/1 * * * * *");
        builder.environment().put("HERMIT_REEF_ADMIN_USER", ADMIN_USER);
        builder.environment().put("HERMIT_REEF_ADMIN_PASSWORD", ADMIN_PASSWORD);
        builder.environment().put("HERMIT_REEF_AGENT_TOKEN", AGENT_TOKEN);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private int loginStatus(String base) {
        try {
            return post(base, "/api/admin/login", null,
                    "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}").statusCode();
        } catch (Exception ex) {
            return 0;
        }
    }

    private String login(String base) throws Exception {
        HttpResponse<String> response = post(base, "/api/admin/login", null,
                "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).path("data").path("token").asText();
    }

    private String signingKey(String base, String token) throws Exception {
        HttpResponse<String> response = get(base, "/api/admin/signing-key", token);
        assertThat(response.statusCode()).isEqualTo(200);
        String pem = json(response).path("data").path("publicKey").asText();
        assertThat(pem).contains("BEGIN PUBLIC KEY");
        return pem;
    }

    private void upload(String base, String token, byte[] body) throws Exception {
        String boundary = "hermit-mcp-it";
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"desk_mcp.yaml\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[head.length + body.length + tail.length];
        System.arraycopy(head, 0, payload, 0, head.length);
        System.arraycopy(body, 0, payload, head.length, body.length);
        System.arraycopy(tail, 0, payload, head.length + body.length, tail.length);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/admin/conches"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(safe(response.body())).isEqualTo(200);
    }

    private void publish(String base, String token, String version) throws Exception {
        HttpResponse<String> response = post(
                base, "/api/admin/conches/" + CONCH + "/versions/" + version + "/publish", token, "");
        assertThat(response.statusCode()).as(safe(response.body())).isEqualTo(200);
    }

    private boolean updateAvailable(String base, String version) {
        try {
            console(base, "POST", "/console/api/sync", "{}");
            HttpResponse<String> response = consoleRaw(base, "GET", "/console/api/catalog", null);
            if (response.statusCode() != 200) {
                return false;
            }
            for (JsonNode row : json(response).path("data").path("conches")) {
                if (!CONCH.equals(row.path("conch").asText())) {
                    continue;
                }
                return row.path("updateAvailable").asBoolean()
                        && version.equals(row.path("latestVersion").asText())
                        && !version.equals(conchVersion(base));
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean versionEquals(String base, String version) {
        try {
            console(base, "POST", "/console/api/sync", "{}");
            return version.equals(conchVersion(base));
        } catch (Exception ex) {
            return false;
        }
    }

    private String conchVersion(String base) {
        try {
            HttpResponse<String> response = consoleRaw(base, "GET", "/console/api/conches", null);
            if (response.statusCode() != 200) {
                return null;
            }
            for (JsonNode row : json(response).path("data").path("conches")) {
                if (CONCH.equals(row.path("conch").asText())) {
                    return row.path("version").asText();
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean tideFailure(String base, String token, String to) {
        try {
            HttpResponse<String> response = get(
                    base, "/api/admin/tide/failures?conch=" + CONCH + "&to=" + to, token);
            if (response.statusCode() != 200) {
                return false;
            }
            for (JsonNode group : json(response).path("data").path("groups")) {
                if (CONCH.equals(group.path("conch").asText())
                        && "1.0.0".equals(group.path("conchVersion").asText())
                        && "echo".equals(group.path("action").asText())
                        && group.path("stepIndex").asInt(-1) == 0
                        && "mcp_tool_error".equals(group.path("stepType").asText())
                        && "UPSTREAM_ERROR".equals(group.path("errorCode").asText())
                        && group.path("count").asInt(0) >= 2) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> alertKinds(String base, String token) {
        try {
            HttpResponse<String> response = get(base, "/api/admin/alerts?status=all", token);
            if (response.statusCode() != 200) {
                return List.of();
            }
            ArrayList<String> kinds = new ArrayList<>();
            for (JsonNode alert : json(response).path("data").path("alerts")) {
                if (CONCH.equals(alert.path("conch").asText())) {
                    kinds.add(alert.path("kind").asText());
                }
            }
            return kinds;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String createApiKey(String base, String body) throws Exception {
        JsonNode created = console(base, "POST", "/console/api/apikeys", body);
        String plaintext = created.path("data").path("plaintext").asText();
        assertThat(plaintext).startsWith("hk_");
        return plaintext;
    }

    private JsonNode console(String base, String method, String path, String body) throws Exception {
        HttpResponse<String> response = consoleRaw(base, method, path, body);
        assertThat(response.statusCode()).as(safe(response.body())).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> consoleRaw(String base, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String base, String path, String bearer) throws Exception {
        return get(base, path, bearer, null);
    }

    private HttpResponse<String> get(String base, String path, String bearer, String agentToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (agentToken != null) {
            builder.header("X-Hermit-Token", agentToken);
            builder.header("X-Hermit-Instance", "123e4567-e89b-12d3-a456-426614174000");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String base, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postApi(String base, String path, String apiKey, String body, String confirm)
            throws Exception {
        return postApi(base, path, apiKey, body, confirm, null);
    }

    private HttpResponse<String> postApi(
            String base, String path, String apiKey, String body, String confirm, String confirmToken
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (confirm != null) {
            builder.header("X-Hermit-Confirm", confirm);
        }
        if (confirmToken != null) {
            builder.header("X-Hermit-Confirm-Token", confirmToken);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return JSON.missingNode();
    }

    private static List<String> toolNames(JsonNode tools) {
        ArrayList<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        return null;
    }

    private String safe(String body) {
        String text = body == null ? "" : body;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                text = text.replace(secret, "[redacted]");
            }
        }
        return text;
    }

    private void waitUntil(Duration limit, BooleanSupplier condition) throws Exception {
        waitUntil(limit, condition, null, null);
    }

    private void waitUntil(Duration limit, BooleanSupplier condition, Process process, Path log) throws Exception {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        String detail = "";
        if (log != null && Files.isRegularFile(log)) {
            String text = Files.readString(log);
            detail = safe(text.length() > 4000 ? text.substring(text.length() - 4000) : text);
        }
        if (process != null && !process.isAlive()) {
            detail = "reef exited " + process.exitValue() + "\n" + detail;
        }
        throw new AssertionError("timed out\n" + detail);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private final class McpClient {
        private final String base;
        private final String apiKey;
        private String session;

        private McpClient(String base, String apiKey) {
            this.base = base;
            this.apiKey = apiKey;
        }

        private JsonNode call(String method, JsonNode params) throws Exception {
            if (session == null) {
                JsonNode opened = post("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"hermit-it","version":"0"}}}
                        """);
                assertThat(opened.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
                post("""
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        """);
            }
            ObjectNode body = JSON.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", 2);
            body.put("method", method);
            if (params != null) {
                body.set("params", params);
            }
            return post(JSON.writeValueAsString(body));
        }

        private JsonNode post(String body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (session != null) {
                builder.header("Mcp-Session-Id", session);
            }
            HttpResponse<String> response = http.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
            assertThat(response.statusCode()).as(safe(response.body())).isIn(200, 202);
            if (response.body() == null || response.body().isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(response.body());
        }
    }
}
