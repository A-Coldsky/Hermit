package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.ServerSocket;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.testing.LegacyOaFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 第一期完成标准。Reef 以独立进程启动，管理员来自环境变量。告警走 {@code RULE_MISMATCH_SURGE}：
 * 连续 5 次失配会把 Crab 退回旧版本，凑不满默认的 10 次；测试把 {@code surge-min-count} 调到 5，
 * 并把 {@code quiet-hours} 调到 0（刚发布的版本否则至少静默 1 小时）。
 */
class Phase1AcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_USER = "phase1-admin";
    private static final String ADMIN_PASSWORD = "phase1-admin-secret";
    private static final String AGENT_TOKEN = "phase1-agent-token";
    private static final String OA_PASSWORD = "phase1-oa-secret";
    private static final String LITERAL_PASSWORD = "literal-password";
    private static final Duration POLL = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> secrets = new ArrayList<>(
            List.of(ADMIN_PASSWORD, AGENT_TOKEN, OA_PASSWORD, LITERAL_PASSWORD));

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void phaseOne() throws Exception {
        Path reefJar = Path.of(System.getProperty("hermit.reef.jar"));
        assertThat(reefJar).isRegularFile();

        try (LegacyOaFixture fixture = new LegacyOaFixture(OA_PASSWORD)) {
            int reefPort = freePort();
            Path reefData = temp.resolve("reef");
            Path reefLog = temp.resolve("reef.log");
            Process reef = startReef(reefJar, reefPort, reefData, reefLog);
            try {
                String reefBase = "http://127.0.0.1:" + reefPort;
                waitUntil(Duration.ofSeconds(90), () -> reef.isAlive() && loginStatus(reefBase) == 200, reef, reefLog);
                assertThat(reef.isAlive()).isTrue();
                String token = login(reefBase);
                String publicPem = signingKey(reefBase, token);
                Path pub = temp.resolve("reef.pub");
                Files.writeString(pub, publicPem);

                String sample = sampleYaml();
                upload(reefBase, token, "legacy_oa.yaml", sample.getBytes(StandardCharsets.UTF_8), 200);
                publish(reefBase, token, "legacy_oa", "1.0.0");

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
                        sync:
                          interval_seconds: 1
                        report:
                          enabled: true
                          interval_seconds: 1
                        """.formatted(reefBase, AGENT_TOKEN, pub.toAbsolutePath()));
                Function<String, String> env = name -> "HERMIT_HOME".equals(name) ? crabHome.toString() : null;
                try (CrabServer crab = CrabServer.start(env)) {
                    String crabBase = "http://127.0.0.1:" + crab.port();
                    JsonNode subscribed = console(
                            crabBase, "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                    assertThat(subscribed.path("data").path("subscribed").asBoolean()).isTrue();
                    assertThat(subscribed.path("data").path("outcome").path("kind").asText())
                            .isIn("INSTALLED", "SKIPPED_SAME");
                    waitUntil(POLL, () -> synced(crabBase, "1.0.0"));
                    assertThat(conchVersion(crabBase, "legacy_oa")).isEqualTo("1.0.0");

                    String apiKey = createApiKey(crabBase);
                    secrets.add(apiKey);
                    assertCode(call(crabBase, apiKey, null), 409, "NOT_CONFIGURED");

                    console(crabBase, "POST", "/console/api/conches/legacy_oa/accounts", "{\"name\":\"alice\"}");
                    assertCode(call(crabBase, apiKey, null), 409, "NOT_CONFIGURED");

                    console(crabBase, "PUT", "/console/api/conches/legacy_oa/config",
                            "{\"base_url\":\"" + fixture.baseUrl() + "\"}");
                    console(crabBase, "PUT", "/console/api/conches/legacy_oa/accounts/alice/credentials",
                            "{\"username\":\"alice\",\"password\":\"" + OA_PASSWORD + "\"}");
                    JsonNode alice = call(crabBase, apiKey, null);
                    assertThat(alice.path("success").asBoolean()).isTrue();
                    assertThat(alice.path("data").path("items").get(0).path("title").asText()).isEqualTo("张三的请假申请");
                    assertCookieOwner(fixture, "alice", "bob");

                    console(crabBase, "POST", "/console/api/conches/legacy_oa/accounts", "{\"name\":\"bob\"}");
                    console(crabBase, "PUT", "/console/api/conches/legacy_oa/accounts/bob/credentials",
                            "{\"username\":\"bob\",\"password\":\"" + OA_PASSWORD + "\"}");
                    JsonNode bob = call(crabBase, apiKey, "bob");
                    assertThat(bob.path("success").asBoolean()).isTrue();
                    assertCookieOwner(fixture, "bob", "alice");
                    assertCode(call(crabBase, apiKey, "nobody"), 404, "ACCOUNT_NOT_FOUND");

                    String forged = sample.replace("\nversion: 1.0.0\n", "\nversion: 1.1.0\n")
                            .replace("td:nth-child(1)", "td.forged-missing");
                    upload(reefBase, token, "legacy_oa.yaml", forged.getBytes(StandardCharsets.UTF_8), 200);
                    publish(reefBase, token, "legacy_oa", "1.1.0");
                    waitUntil(POLL, () -> synced(crabBase, "1.1.0"));

                    for (int i = 0; i < 5; i++) {
                        assertCode(call(crabBase, apiKey, null), 422, "RULE_MISMATCH");
                    }
                    waitUntil(POLL, () -> "1.0.0".equals(conchVersion(crabBase, "legacy_oa")));
                    JsonNode restored = call(crabBase, apiKey, null);
                    assertThat(restored.path("success").asBoolean()).isTrue();
                    assertThat(restored.path("data").path("items").get(0).path("title").asText()).isEqualTo("张三的请假申请");
                    assertCookieOwner(fixture, "alice", "bob");

                    JsonNode sent = console(crabBase, "POST", "/console/api/reports/send", "{}");
                    boolean delivered = sent.path("data").path("accepted").asBoolean()
                            || !sent.path("data").path("attempted").asBoolean();
                    assertThat(delivered).as(sent.toString()).isTrue();
                    waitUntil(POLL, () -> alertKinds(reefBase, token).contains("RULE_MISMATCH_SURGE"));
                    assertThat(alertKinds(reefBase, token)).contains("RULE_MISMATCH_SURGE");
                    assertThat(rollbackReasons(reefBase, token)).contains("RULE_MISMATCH");

                    HttpResponse<String> disabled = post(
                            reefBase, "/api/admin/conches/legacy_oa/disable", token, "{\"reason\":\"phase1 disable\"}");
                    assertThat(disabled.statusCode()).as(safe(disabled.body())).isEqualTo(200);
                    assertThat(json(disabled).path("data").path("disabled").asBoolean()).isTrue();
                    HttpResponse<String> blocked = post(
                            reefBase, "/api/admin/conches/legacy_oa/versions/1.0.0/publish", token, "");
                    assertThat(blocked.statusCode()).isEqualTo(409);
                    console(crabBase, "POST", "/console/api/sync", "{}");
                    JsonNode stillRunning = call(crabBase, apiKey, null);
                    assertThat(stillRunning.path("success").asBoolean()).as(stillRunning.toString()).isTrue();
                    JsonNode listed = console(crabBase, "GET", "/console/api/conches", null);
                    JsonNode listedRow = null;
                    for (JsonNode row : listed.path("data").path("conches")) {
                        if ("legacy_oa".equals(row.path("conch").asText())) {
                            listedRow = row;
                        }
                    }
                    assertThat(listedRow).isNotNull();
                    assertThat(listedRow.path("version").asText()).isEqualTo("1.0.0");
                    assertThat(listedRow.path("reefDisabled").asBoolean()).isTrue();
                    assertThat(listedRow.path("reefDisabledAt").asText()).contains("T");
                    JsonNode detail = console(crabBase, "GET", "/console/api/conches/legacy_oa", null);
                    assertThat(detail.path("data").path("reefDisabled").asBoolean()).isTrue();
                    assertThat(detail.path("data").path("reefDisabledAt").asText())
                            .isEqualTo(listedRow.path("reefDisabledAt").asText());
                    JsonNode catalog = console(crabBase, "GET", "/console/api/catalog", null);
                    JsonNode catalogRow = null;
                    for (JsonNode row : catalog.path("data").path("conches")) {
                        if ("legacy_oa".equals(row.path("conch").asText())) {
                            catalogRow = row;
                        }
                    }
                    assertThat(catalogRow).isNotNull();
                    assertThat(catalogRow.path("disabled").asBoolean()).isTrue();
                    assertThat(catalogRow.path("installedVersion").asText()).isEqualTo("1.0.0");
                    JsonNode syncBody = console(crabBase, "GET", "/console/api/sync", null);
                    JsonNode report = syncBody.path("data").path("report");
                    boolean shownDisabled = false;
                    for (JsonNode row : report.path("conches")) {
                        if ("legacy_oa".equals(row.path("conch").asText())
                                && "DISABLED".equals(row.path("kind").asText())) {
                            shownDisabled = true;
                        }
                    }
                    assertThat(shownDisabled).as(syncBody.toString()).isTrue();
                    assertThat(report.path("removed").toString()).doesNotContain("legacy_oa");
                    console(crabBase, "POST", "/console/api/sync", "{}");
                    assertThat(call(crabBase, apiKey, null).path("success").asBoolean()).isTrue();

                    HttpResponse<String> enabled = post(
                            reefBase, "/api/admin/conches/legacy_oa/enable", token, "{\"reason\":\"phase1 enable\"}");
                    assertThat(enabled.statusCode()).as(safe(enabled.body())).isEqualTo(200);
                    console(crabBase, "POST", "/console/api/sync", "{}");
                    assertThat(conchVersion(crabBase, "legacy_oa")).isEqualTo("1.0.0");
                    JsonNode reopened = console(crabBase, "GET", "/console/api/conches/legacy_oa", null);
                    assertThat(reopened.path("data").path("reefDisabled").asBoolean()).isFalse();
                    assertThat(reopened.path("data").path("reefDisabledAt").isNull()).isTrue();
                    assertThat(call(crabBase, apiKey, null).path("success").asBoolean()).isTrue();

                    String leaked = sample.replace("conch: legacy_oa", "conch: leaked_pw")
                            .replace("password: \"{{credential.password | md5}}\"",
                                    "password: \"" + LITERAL_PASSWORD + "\"");
                    HttpResponse<String> rejected = uploadRaw(
                            reefBase, token, "leaked.yaml", leaked.getBytes(StandardCharsets.UTF_8));
                    assertThat(rejected.statusCode()).isEqualTo(422);
                    assertThat(safe(rejected.body())).contains("PLAINTEXT_CREDENTIAL").doesNotContain(LITERAL_PASSWORD);

                    assertCode(get(crabBase, "/", null), 404, "NOT_FOUND");
                    assertCode(get(crabBase, "/dev/x", null), 404, "NOT_FOUND");
                    Assumptions.assumingThat(!"true".equals(System.getProperty("hermit.web.skip")), () -> {
                        HttpResponse<String> reefPage = get(reefBase, "/", null);
                        assertThat(reefPage.statusCode()).isEqualTo(200);
                        assertThat(reefPage.body()).contains("<div id=\"app\">");
                        HttpResponse<String> crabPage = get(crabBase, "/console/", null);
                        assertThat(crabPage.statusCode()).isEqualTo(200);
                        assertThat(crabPage.body()).contains("<div id=\"app\">");
                    });
                }
            } finally {
                reef.destroy();
                if (!reef.waitFor(10, TimeUnit.SECONDS)) {
                    reef.destroyForcibly();
                }
            }
        }
    }

    private static void assertCookieOwner(LegacyOaFixture fixture, String owner, String other) {
        boolean seen = false;
        for (LegacyOaFixture.Hit hit : fixture.hits()) {
            if (!"/todo/list.aspx".equals(hit.path()) || hit.cookie() == null) {
                continue;
            }
            if (!hit.cookie().contains("sid-" + owner + "-")) {
                continue;
            }
            seen = true;
            assertThat(hit.cookie()).doesNotContain("sid-" + other + "-");
        }
        assertThat(seen).as("todo cookie for " + owner).isTrue();
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
                "--hermit.reef.tide.surge-min-count=5",
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

    private void upload(String base, String token, String filename, byte[] body, int status) throws Exception {
        HttpResponse<String> response = uploadRaw(base, token, filename, body);
        assertThat(response.statusCode()).as(safe(response.body())).isEqualTo(status);
    }

    private HttpResponse<String> uploadRaw(String base, String token, String filename, byte[] body) throws Exception {
        String boundary = "hermit-phase1";
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
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
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void publish(String base, String token, String conch, String version) throws Exception {
        HttpResponse<String> response = post(base, "/api/admin/conches/" + conch + "/versions/" + version + "/publish", token, "");
        assertThat(response.statusCode()).as(safe(response.body())).isEqualTo(200);
    }

    private boolean synced(String base, String version) {
        try {
            postConsole(base, "/console/api/sync", "{}");
            return version.equals(conchVersion(base, "legacy_oa"));
        } catch (Exception ex) {
            return false;
        }
    }

    private String conchVersion(String base, String conch) {
        try {
            HttpResponse<String> response = consoleRaw(base, "GET", "/console/api/conches", null);
            if (response.statusCode() != 200) {
                return null;
            }
            for (JsonNode row : json(response).path("data").path("conches")) {
                if (conch.equals(row.path("conch").asText())) {
                    return row.path("version").asText();
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private HttpResponse<String> postConsole(String base, String path, String body) throws Exception {
        return consoleRaw(base, "POST", path, body);
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

    private String createApiKey(String base) throws Exception {
        JsonNode created = console(base, "POST", "/console/api/apikeys", "{\"name\":\"caller\"}");
        String plaintext = created.path("data").path("plaintext").asText();
        assertThat(plaintext).startsWith("hk_");
        return plaintext;
    }

    private JsonNode console(String base, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(safe(response.body())).isEqualTo(200);
        return json(response);
    }

    private JsonNode call(String base, String apiKey, String account) throws Exception {
        return json(get(base, "/api/legacy_oa/list_todo", apiKey, account));
    }

    private void assertCode(JsonNode body, int ignoredStatus, String code) {
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
    }

    private void assertCode(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo(code);
    }

    private List<String> alertKinds(String base, String token) {
        try {
            HttpResponse<String> response = get(base, "/api/admin/alerts?status=all", token);
            if (response.statusCode() != 200) {
                return List.of();
            }
            ArrayList<String> kinds = new ArrayList<>();
            for (JsonNode alert : json(response).path("data").path("alerts")) {
                kinds.add(alert.path("kind").asText());
            }
            return kinds;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> rollbackReasons(String base, String token) {
        try {
            HttpResponse<String> response = get(base, "/api/admin/tide/rollbacks?conch=legacy_oa", token);
            if (response.statusCode() != 200) {
                return List.of();
            }
            ArrayList<String> reasons = new ArrayList<>();
            for (JsonNode event : json(response).path("data").path("events")) {
                reasons.add(event.path("reason").asText());
            }
            return reasons;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private HttpResponse<String> get(String base, String path, String bearer) throws Exception {
        return get(base, path, bearer, null);
    }

    private HttpResponse<String> get(String base, String path, String bearer, String account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (account != null) {
            builder.header("X-Hermit-Account", account);
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

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
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

    private static String sampleYaml() throws Exception {
        try (InputStream in = Phase1AcceptanceTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            if (in == null) {
                throw new IllegalStateException("classpath is missing samples/legacy_oa.yaml");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
