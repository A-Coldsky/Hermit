package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.FakeReef;

class ReefBootstrapTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "hat_office_pc_token_0123456789";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path temp;

    @Test
    void rejectsInvalidJsonUnknownFieldsUnauthorizedAndUnreachableReef() throws Exception {
        try (FakeReef reef = new FakeReef(); CrabServer server = server(CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            Reply invalid = post(server, "{", 400);
            assertThat(invalid.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(invalid.path("error").path("message").asText()).isEqualTo("invalid bootstrap");

            Reply unknown = post(server, bootstrap(reef, TOKEN, reef.keys().getPublic()).put("extra", 1).toString(), 400);
            assertThat(unknown.path("error").path("message").asText()).isEqualTo("未知字段：extra。");

            ObjectNode nested = bootstrap(reef, TOKEN, reef.keys().getPublic());
            ((ObjectNode) nested.get("reef")).put("extra", true);
            Reply nestedUnknown = post(server, nested.toString(), 400);
            assertThat(nestedUnknown.path("error").path("message").asText()).isEqualTo("未知字段：reef.extra。");

            reef.expectToken("other-token-value-not-this");
            Reply rejected = post(server, bootstrap(reef, TOKEN, reef.keys().getPublic()).toString(), 400);
            assertThat(rejected.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(rejected.path("error").path("message").asText()).isEqualTo("reef rejected the token");
            assertThat(rejected.toString()).doesNotContain(TOKEN);

            reef.disconnect();
            Reply down = post(server, bootstrap(reef, TOKEN, reef.keys().getPublic()).toString(), 502);
            assertThat(down.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
            String message = down.path("error").path("message").asText();
            assertThat(message).isNotEqualTo("cannot reach reef");
            assertThat(message.startsWith("上游拒绝连接") || message.startsWith("上游连接失败")).isTrue();
            assertThat(Files.exists(temp.resolve("reef-bootstrap.json"))).isFalse();
        }
    }

    @Test
    void probeAcceptsManifestSuccessAndLeavesTheFileUntouchedOnOtherStatuses() throws Exception {
        HttpServer html = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        html.createContext("/agent/conches/manifest", exchange -> {
            byte[] page = "<html><body>not reef</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(404, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        html.start();
        try (FakeReef reef = new FakeReef(); CrabServer server = server(CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            reef.expectToken(TOKEN);
            PublicKey key = reef.keys().getPublic();
            String payload = bootstrap(reef, TOKEN, key).toString();
            Path file = temp.resolve("reef-bootstrap.json");

            reef.manifestStatus(403);
            Reply forbidden = post(server, payload, 400);
            assertThat(forbidden.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(forbidden.path("error").path("message").asText()).isEqualTo("reef rejected the token");
            assertThat(Files.exists(file)).isFalse();

            reef.manifestStatus(404);
            Reply missing = post(server, payload, 400);
            assertThat(missing.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(missing.path("error").path("message").asText())
                    .isEqualTo("reef url did not answer as Reef (HTTP 404)");
            assertThat(Files.exists(file)).isFalse();

            reef.manifestStatus(500);
            Reply broken = post(server, payload, 400);
            assertThat(broken.path("error").path("message").asText())
                    .isEqualTo("reef url did not answer as Reef (HTTP 500)");
            assertThat(Files.exists(file)).isFalse();

            reef.manifestStatus(201);
            Reply created = post(server, payload, 200);
            assertThat(created.path("data").path("source").asText()).isEqualTo("BOOTSTRAP");
            assertThat(Files.isRegularFile(file)).isTrue();

            reef.manifestStatus(304);
            Reply notModified = post(server, payload, 200);
            assertThat(notModified.path("success").asBoolean()).isTrue();
            byte[] kept = Files.readAllBytes(file);

            ObjectNode htmlBootstrap = bootstrap(reef, TOKEN, key);
            ((ObjectNode) htmlBootstrap.get("reef")).put("url", "http://127.0.0.1:" + html.getAddress().getPort());
            Reply htmlPage = post(server, htmlBootstrap.toString(), 400);
            assertThat(htmlPage.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(htmlPage.path("error").path("message").asText())
                    .isEqualTo("reef url did not answer as Reef (HTTP 404)");
            assertThat(Files.readAllBytes(file)).isEqualTo(kept);

            reef.redirectManifest();
            Reply redirect = post(server, payload, 400);
            assertThat(redirect.path("error").path("message").asText())
                    .isEqualTo("reef url did not answer as Reef (HTTP 302)");
            assertThat(Files.readAllBytes(file)).isEqualTo(kept);
        } finally {
            html.stop(0);
        }
    }

    @Test
    void appliesBootstrapWithoutRestartAndRefusesADifferentKey() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.expectToken(TOKEN);
            PublicKey installed = reef.keys().getPublic();
            PublicKey other = Ed25519Keys.generate().getPublic();
            CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
            try (CrabServer server = server(config)) {
                Reply before = send(server, "GET", "/console/api/sync", null);
                assertThat(before.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");

                Reply imported = post(server, bootstrap(reef, TOKEN, installed).toString(), 200);
                JsonNode data = imported.path("data");
                assertThat(data.path("configured").asBoolean()).isTrue();
                assertThat(data.path("source").asText()).isEqualTo("BOOTSTRAP");
                assertThat(data.path("url").asText()).isEqualTo(reef.base().toString());
                assertThat(data.path("owner").asText()).isEqualTo("alice");
                assertThat(data.path("tokenName").asText()).isEqualTo("办公室 PC");
                assertThat(data.path("tokenPrefix").asText()).isEqualTo(TOKEN.substring(0, 12));
                assertThat(data.path("publicKeyFingerprint").asText()).hasSize(64);
                assertThat(data.path("importedAt").asText()).isNotBlank();
                assertThat(imported.toString()).doesNotContain(TOKEN);
                assertThat(reef.tokenMatched()).isTrue();
                assertThat(reef.paths().stream().filter("/agent/conches/manifest"::equals).count()).isGreaterThanOrEqualTo(2);

                Path file = temp.resolve("reef-bootstrap.json");
                assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                assertThat(Files.readString(file)).contains("\"importedAt\"").doesNotContain("\"extra\"");

                Reply sync = send(server, "GET", "/console/api/sync", null);
                assertThat(sync.path("success").asBoolean()).isTrue();
                assertThat(server.runtime().conchSync()).isNotNull();
                assertThat(server.runtime().conchSync().currentSettings().reefUrl()).isEqualTo(reef.base());

                Path current = temp.resolve("conches/legacy_oa/current");
                Files.createDirectories(current.getParent());
                Files.writeString(current, "1.0.0\n");
                Reply conflict = post(server, bootstrap(reef, TOKEN, other).toString(), 409);
                assertThat(conflict.path("error").path("code").asText()).isEqualTo("CONFLICT");
                assertThat(conflict.path("error").path("message").asText()).isEqualTo("public key differs from installed conches");
                assertThat(Files.readString(file)).contains(Ed25519Keys.writePublicPem(installed).substring(0, 20));

                Reply same = post(server, bootstrap(reef, TOKEN, installed).toString(), 200);
                assertThat(same.path("data").path("source").asText()).isEqualTo("BOOTSTRAP");

                Reply settings = send(server, "GET", "/console/api/reports/settings", null);
                assertThat(settings.path("data").path("enabled").asBoolean()).isTrue();
                assertThat(settings.path("data").path("intervalSeconds").asInt()).isEqualTo(1800);
                Reply disabled = send(server, "PUT", "/console/api/reports/settings",
                        "{\"enabled\":false,\"intervalSeconds\":120}");
                assertThat(disabled.path("data").path("enabled").asBoolean()).isFalse();
                assertThat(disabled.path("data").path("intervalSeconds").asInt()).isEqualTo(120);
                Reply preview = send(server, "GET", "/console/api/reports/preview", null);
                assertThat(preview.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
                assertThat(preview.path("error").path("message").asText()).isEqualTo("上报未开启，或尚未连接 Reef。");
                Reply enabled = send(server, "PUT", "/console/api/reports/settings",
                        "{\"enabled\":true,\"intervalSeconds\":60}");
                assertThat(enabled.path("data").path("enabled").asBoolean()).isTrue();
                assertThat(send(server, "GET", "/console/api/reports/preview", null).path("success").asBoolean()).isTrue();
                Reply extra = send(server, "PUT", "/console/api/reports/settings",
                        "{\"enabled\":true,\"intervalSeconds\":60,\"extra\":1}");
                assertThat(extra.path("error").path("message").asText()).isEqualTo("未知字段：extra。");
                Reply low = send(server, "PUT", "/console/api/reports/settings",
                        "{\"enabled\":true,\"intervalSeconds\":30}");
                assertThat(low.status()).isEqualTo(400);

                redactImportedToken(server.runtime());
                assertAudit(server.runtime());
            }
            try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp));
                 Connection connection = database.dataSource().getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT success_count FROM hourly_counts")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isPositive();
            }
        }
    }

    @Test
    void commandLineImportWritesTheFile() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.expectToken(TOKEN);
            Path file = temp.resolve("boot.json");
            Files.writeString(file, bootstrap(reef, TOKEN, reef.keys().getPublic()).toString());
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            int code = new CrabCommand().run(
                    new String[] {"reef", "import", file.toString()},
                    name -> "HERMIT_HOME".equals(name) ? temp.toString() : null,
                    InputStreamNull.INSTANCE,
                    new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                    new PrintStream(errBytes, true, StandardCharsets.UTF_8));
            assertThat(code).as(errBytes.toString(StandardCharsets.UTF_8)).isZero();
            assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("imported");
            assertThat(Files.isRegularFile(temp.resolve("reef-bootstrap.json"))).isTrue();
            assertThat(errBytes.toString(StandardCharsets.UTF_8)).doesNotContain(TOKEN);
        }
    }

    private void redactImportedToken(CrabRuntime runtime) throws Exception {
        runtime.registry().put(ConchParser.parse(echoYaml()));
        runtime.admin().createAccount("echo_tok", "alice", Actors.CLI);
        CreatedApiKey key = runtime.admin().createApiKey(
                "redact", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
        var principal = runtime.apiKeys().authenticate(key.plaintext()).orElseThrow();
        InvokeOutcome outcome = runtime.invoker().invoke(
                principal, "echo_tok", "run", Map.of(), Optional.empty(), Optional.empty(), "trace-redact");
        String body = new String(outcome.body(), StandardCharsets.UTF_8);
        assertThat(outcome.status()).as(body).isEqualTo(200);
        assertThat(body).doesNotContain(TOKEN).contains("***");
    }

    private static void assertAudit(CrabRuntime runtime) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT kind, detail FROM config_audit WHERE kind IN ('REEF_BOOTSTRAPPED', 'REPORT_SETTINGS_CHANGED')")) {
            boolean boot = false;
            boolean settings = false;
            while (rows.next()) {
                String detail = rows.getString(2);
                assertThat(detail).doesNotContain(TOKEN);
                if ("REEF_BOOTSTRAPPED".equals(rows.getString(1))) {
                    boot = true;
                    assertThat(detail).contains("tokenPrefix").contains("alice");
                }
                if ("REPORT_SETTINGS_CHANGED".equals(rows.getString(1))) {
                    settings = true;
                    assertThat(detail).contains("intervalSeconds");
                }
            }
            assertThat(boot).isTrue();
            assertThat(settings).isTrue();
        }
    }

    private CrabServer server(CrabConfig config) {
        CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp), config, name -> null);
        try {
            return CrabServer.start(runtime);
        } catch (RuntimeException ex) {
            runtime.close();
            throw ex;
        }
    }

    private static ObjectNode bootstrap(FakeReef reef, String token, PublicKey key) {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", "hermit-crab-bootstrap");
        root.put("version", 1);
        ObjectNode reefNode = root.putObject("reef");
        reefNode.put("url", reef.base().toString());
        reefNode.put("token", token);
        reefNode.put("publicKey", Ed25519Keys.writePublicPem(key));
        root.put("owner", "alice");
        root.put("tokenName", "办公室 PC");
        root.put("createdAt", "2026-11-04T04:00:00Z");
        return root;
    }

    private static Reply post(CrabServer server, String body, int status) throws Exception {
        Reply reply = send(server, "POST", "/console/api/reef/bootstrap", body);
        assertThat(reply.status()).isEqualTo(status);
        return reply;
    }

    private static Reply send(CrabServer server, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), ApiJson.tree(response.body()));
    }

    private static String echoYaml() {
        return """
                conch: echo_tok
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 回显
                description: 把固定文本返回，用来确认令牌进入脱敏名单。
                base_url: http://127.0.0.1/
                credentials: {}
                actions:
                  run:
                    title: 回显
                    description: 返回一段固定文本。
                    method: GET
                    input: {}
                    output:
                      value: { type: string, description: 文本 }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 测试
                    steps:
                      - script:
                          code: |
                            return { value: "%s" };
                """.formatted(TOKEN);
    }

    private record Reply(int status, JsonNode json) {
        JsonNode path(String field) {
            return json.path(field);
        }

        @Override
        public String toString() {
            return json.toString();
        }
    }

    private static final class InputStreamNull extends java.io.InputStream {
        static final InputStreamNull INSTANCE = new InputStreamNull();

        @Override
        public int read() {
            return -1;
        }
    }
}
