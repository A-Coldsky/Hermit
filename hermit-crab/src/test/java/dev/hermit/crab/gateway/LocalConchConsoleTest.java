package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.sync.LocalConchBehaviorTest;

class LocalConchConsoleTest {
    private static final String PASSWORD = "HermitLocalConsole-91ab";

    @TempDir
    Path temp;

    @Test
    void consoleImportExportAndExplicitKeyCall() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            byte[] yaml = LocalConchBehaviorTest.ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> imported = post(
                    server, "/console/api/local-conches", "text/yaml; charset=utf-8", yaml);
            assertThat(imported.statusCode()).isEqualTo(200);
            assertThat(imported.body()).contains("\"conch\":\"local_ping\"").contains("\"replaced\":false");

            HttpResponse<String> clash = post(
                    server,
                    "/console/api/local-conches",
                    "application/yaml",
                    LocalConchBehaviorTest.ping("legacy_oa", "1.0.0").getBytes(StandardCharsets.UTF_8));
            assertThat(clash.statusCode()).isEqualTo(409);
            assertThat(clash.body()).contains("INVALID_INPUT").contains("与已安装的正式规则包重名");

            HttpResponse<String> invalid = post(
                    server, "/console/api/local-conches", "text/yaml", "conch: [\n".getBytes(StandardCharsets.UTF_8));
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("\"errors\"");

            String boundary = "----hermitlocal";
            byte[] other = LocalConchBehaviorTest.ping("local_two", "1.0.0").getBytes(StandardCharsets.UTF_8);
            byte[] multipart = multipart(boundary, "local_two.yaml", other);
            HttpResponse<String> uploaded = post(
                    server, "/console/api/local-conches", "multipart/form-data; boundary=" + boundary, multipart);
            assertThat(uploaded.statusCode()).isEqualTo(200);
            assertThat(uploaded.body()).contains("local_two");

            TestServer.Reply listed = server.console("GET", "/console/api/conches", null, true);
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.body()).contains("\"conch\":\"legacy_oa\"").contains("\"source\":\"REEF\"");
            assertThat(listed.body()).contains("\"conch\":\"local_ping\"").contains("\"source\":\"LOCAL\"");

            HttpResponse<byte[]> exported = server.http.send(
                    HttpRequest.newBuilder(URI.create(server.base() + "/console/api/local-conches/local_ping/export"))
                            .header("X-Hermit-Console", "1")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(exported.headers().firstValue("Content-Type").orElse("")).startsWith("application/yaml");
            assertThat(exported.headers().firstValue("Content-Disposition").orElse(""))
                    .isEqualTo("attachment; filename=\"local_ping-1.0.0.yaml\"");
            assertThat(exported.body()).isEqualTo(yaml);
            assertThat(exported.body()).isEqualTo(Files.readAllBytes(
                    server.paths.localConches().resolve("local_ping.yaml")));

            TestServer.Reply missing = server.console("DELETE", "/console/api/local-conches/legacy_oa", null, true);
            assertThat(missing.status()).isEqualTo(404);

            server.runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            CreatedApiKey named = server.runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping"), Set.of(), false), Actors.CLI);
            CreatedApiKey narrow = server.runtime.admin().createApiKey(
                    "narrow", new ApiKeyScope(Set.of(server.conch.conch()), Set.of(), false), Actors.CLI);
            TestServer.Reply wide = server.call("GET", "/api/local_ping/ping", server.apiKey.plaintext(), null, null, null);
            TestServer.Reply denied = server.call("GET", "/api/local_ping/ping", narrow.plaintext(), null, null, null);
            TestServer.Reply allowed = server.call("GET", "/api/local_ping/ping", named.plaintext(), null, null, null);
            assertThat(wide.status()).isEqualTo(200);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(allowed.status()).isEqualTo(200);

            TestServer.Reply calls = server.console("GET", "/console/api/calls?conch=local_ping", null, true);
            assertThat(calls.body()).contains("\"source\":\"LOCAL\"");

            TestServer.Reply removed = server.console("DELETE", "/console/api/local-conches/local_two", null, true);
            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.body()).contains("\"deleted\":true");
            assertThat(server.runtime.registry().find("local_two")).isEmpty();
        }
    }

    @Test
    void overwriteNeedsConfirmAndBrokenPackageStaysListedUntilDelete() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("confirm"), PASSWORD, config)) {
            byte[] yaml = LocalConchBehaviorTest.ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> imported = post(
                    server, "/console/api/local-conches", "text/yaml; charset=utf-8", yaml);
            assertThat(imported.statusCode()).isEqualTo(200);
            Path installed = server.paths.localConches().resolve("local_ping.yaml");
            byte[] stored = Files.readAllBytes(installed);

            byte[] next = LocalConchBehaviorTest.ping("local_ping", "1.1.0").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> denied = post(
                    server, "/console/api/local-conches", "text/yaml", next);
            assertThat(denied.statusCode()).isEqualTo(409);
            assertThat(denied.body()).contains("\"success\":false");
            assertThat(denied.body()).contains("\"code\":\"CONFLICT\"");
            assertThat(denied.body()).contains("将覆盖已有的本地规则包 local_ping。确认后再导入。");
            assertThat(denied.body()).contains("\"conch\":\"local_ping\"");
            assertThat(denied.body()).contains("\"confirm\":true");
            assertThat(Files.readAllBytes(installed)).isEqualTo(stored);
            assertThat(server.runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.0.0");

            HttpResponse<String> badFlag = post(
                    server, "/console/api/local-conches?confirm=yes", "text/yaml", next);
            assertThat(badFlag.statusCode()).isEqualTo(400);
            assertThat(badFlag.body()).contains("confirm 只能是 true 或 false。");
            assertThat(Files.readAllBytes(installed)).isEqualTo(stored);

            HttpResponse<String> confirmed = post(
                    server, "/console/api/local-conches?confirm=true", "text/yaml", next);
            assertThat(confirmed.statusCode()).isEqualTo(200);
            assertThat(confirmed.body()).contains("\"replaced\":true").contains("\"version\":\"1.1.0\"");
            assertThat(server.runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.1.0");

            Files.writeString(installed, ":\n");
            TestServer.Reply reloaded = server.console("POST", "/console/api/local-conches/reload", "", true);
            assertThat(reloaded.status()).isEqualTo(200);
            assertThat(reloaded.body()).contains("\"file\":\"local_ping.yaml\"");
            assertThat(reloaded.body()).contains("\"ok\":false");
            assertThat(reloaded.body()).contains("文件 local_ping.yaml");
            assertThat(reloaded.body()).contains("规则包 local_ping");
            assertThat(reloaded.body()).contains("无法解析");
            assertThat(reloaded.body()).doesNotContain("conch validation failed");
            assertThat(reloaded.body()).doesNotContain("file name does not match conch");

            TestServer.Reply loaded = server.console("GET", "/console/api/conches", null, true);
            assertThat(loaded.status()).isEqualTo(200);
            assertThat(loaded.body()).doesNotContain("\"conch\":\"local_ping\"");
            assertThat(server.runtime.registry().find("local_ping")).isEmpty();

            TestServer.Reply listed = server.console("GET", "/console/api/local-conches", null, true);
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.body()).contains("\"conch\":\"local_ping\"");
            assertThat(listed.body()).contains("\"file\":\"local_ping.yaml\"");
            assertThat(listed.body()).contains("\"version\":null");
            assertThat(listed.body()).contains("\"loaded\":false");
            assertThat(listed.body()).contains("\"broken\":true");
            assertThat(listed.body()).contains("文件 local_ping.yaml");
            assertThat(listed.body()).contains("规则包 local_ping");
            assertThat(listed.body()).contains("无法解析");

            TestServer.Reply exported = server.console("GET", "/console/api/local-conches/local_ping/export", null, true);
            assertThat(exported.status()).isEqualTo(404);

            TestServer.Reply removed = server.console("DELETE", "/console/api/local-conches/local_ping", null, true);
            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.body()).contains("\"conch\":\"local_ping\"").contains("\"deleted\":true");
            assertThat(installed).doesNotExist();
            TestServer.Reply afterDelete = server.console("GET", "/console/api/local-conches", null, true);
            assertThat(afterDelete.body()).doesNotContain("\"conch\":\"local_ping\"");

            HttpResponse<String> restored = post(
                    server, "/console/api/local-conches", "text/yaml", yaml);
            assertThat(restored.statusCode()).isEqualTo(200);
            assertThat(restored.body()).contains("\"replaced\":false").contains("\"version\":\"1.0.0\"");
            server.runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            CreatedApiKey named = server.runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping"), Set.of(), false), Actors.CLI);
            TestServer.Reply allowed = server.call("GET", "/api/local_ping/ping", named.plaintext(), null, null, null);
            assertThat(allowed.status()).isEqualTo(200);

            TestServer.Reply missing = server.console("DELETE", "/console/api/local-conches/missing_ping", null, true);
            assertThat(missing.status()).isEqualTo(404);
        }
    }

    @Test
    void detailFlagsMatchTheListForSubscribedPendingAndLocalReady() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("detail-flags"), PASSWORD, config)) {
            server.runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
            try (Connection connection = server.runtime.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO subscriptions (conch, subscribed_at, actor) VALUES (?, CURRENT_TIMESTAMP, ?)")) {
                statement.setString(1, "legacy_oa");
                statement.setString(2, "test");
                statement.executeUpdate();
            }
            HttpResponse<String> imported = post(
                    server,
                    "/console/api/local-conches",
                    "text/yaml; charset=utf-8",
                    LocalConchBehaviorTest.ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8));
            assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
            server.runtime.admin().createAccount("local_ping", "ada", Actors.CLI);

            TestServer.Reply listed = server.console("GET", "/console/api/conches", null, true);
            assertThat(listed.status()).isEqualTo(200);
            JsonNode rows = listed.json().path("data").path("conches");
            assertFlagsMatch(server, rows, "legacy_oa", true, true);
            assertFlagsMatch(server, rows, "local_ping", false, false);

            TestServer.Reply accounts = server.console("GET", "/console/api/conches/legacy_oa/accounts", null, true);
            JsonNode alice = null;
            for (JsonNode row : accounts.json().path("data").path("accounts")) {
                if ("alice".equals(row.path("name").asText())) {
                    alice = row;
                }
            }
            assertThat(alice).isNotNull();
            assertThat(alice.path("needsConfiguration").asBoolean()).isTrue();
            assertThat(alice.path("missingFields").size()).isPositive();
            assertThat(alice.path("loginPaused").asBoolean()).isFalse();
        }
    }

    @Test
    void importFailureIncludesALineWhenTheFieldExistsAndDoesNotStore() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("lines"), PASSWORD, config)) {
            ObjectMapper mapper = new ObjectMapper();
            String marker = "zz_body_marker_should_not_leak";
            String missing = LocalConchBehaviorTest.ping("local_ping", "1.0.0")
                    .replace("min_crab_version: 1.0.0\n", "")
                    .replace("title: 本地探测", "title: " + marker);
            HttpResponse<String> rejected = post(
                    server, "/console/api/local-conches", "text/yaml", missing.getBytes(StandardCharsets.UTF_8));
            assertThat(rejected.statusCode()).isEqualTo(400);
            JsonNode issue = mapper.readTree(rejected.body()).path("data").path("errors").get(0);
            assertThat(issue.path("code").asText()).isEqualTo("PARSE");
            assertThat(issue.path("path").asText()).isEqualTo("min_crab_version");
            assertThat(issue.path("message").asText()).isEqualTo("min_crab_version: 缺少 min_crab_version");
            assertThat(issue.has("line")).isFalse();
            assertThat(rejected.body()).doesNotContain(marker).doesNotContain("com.fasterxml");
            assertThat(server.paths.localConches().resolve("local_ping.yaml")).doesNotExist();
            assertThat(server.runtime.registry().find("local_ping")).isEmpty();

            String driver = LocalConchBehaviorTest.ping("bad_driver", "1.0.0")
                    .replace("driver: http", "driver: shell")
                    .replace("title: 本地探测", "title: " + marker);
            HttpResponse<String> badDriver = post(
                    server, "/console/api/local-conches", "text/yaml", driver.getBytes(StandardCharsets.UTF_8));
            assertThat(badDriver.statusCode()).isEqualTo(400);
            JsonNode driverIssue = null;
            for (JsonNode row : mapper.readTree(badDriver.body()).path("data").path("errors")) {
                if ("DRIVER".equals(row.path("code").asText())) {
                    driverIssue = row;
                }
            }
            assertThat(driverIssue).isNotNull();
            assertThat(driverIssue.path("path").asText()).isEqualTo("driver");
            assertThat(driverIssue.path("message").asText()).isEqualTo("driver 只允许 http 或 mcp");
            assertThat(driverIssue.path("line").asInt()).isEqualTo(sourceLine(driver, "driver:"));
            assertThat(badDriver.body()).doesNotContain(marker).doesNotContain("shell").doesNotContain("com.fasterxml");
            assertThat(server.paths.localConches().resolve("bad_driver.yaml")).doesNotExist();
        }
    }

    private static int sourceLine(String text, String token) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(token)) {
                return i + 1;
            }
        }
        throw new AssertionError(token);
    }

    @Test
    void zeroAccountsNeedConfigurationUntilAnAccountExists() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("no-account"), PASSWORD, config)) {
            TestServer.Reply listed = server.console("GET", "/console/api/conches", null, true);
            assertThat(listed.status()).isEqualTo(200);
            JsonNode rows = listed.json().path("data").path("conches");
            assertFlagsMatch(server, rows, "legacy_oa", false, true);
            assertThat(summary(rows, "legacy_oa").path("accountCount").asInt()).isZero();
            assertThat(accounts(server, "legacy_oa")).isEmpty();
            TestServer.Reply blocked = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", server.apiKey.plaintext(), null, null, null);
            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blocked.json().path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(blocked.json().path("error").path("message").asText()).isEqualTo("还没有账号。请到控制台为这个规则包新建账号并填写凭证。");

            HttpResponse<String> optional = post(
                    server, "/console/api/local-conches", "text/yaml; charset=utf-8", optionalNote());
            assertThat(optional.statusCode()).as(optional.body()).isEqualTo(200);
            HttpResponse<String> bare = post(
                    server,
                    "/console/api/local-conches",
                    "text/yaml; charset=utf-8",
                    LocalConchBehaviorTest.ping("bare_ping", "1.0.0").getBytes(StandardCharsets.UTF_8));
            assertThat(bare.statusCode()).as(bare.body()).isEqualTo(200);

            CreatedApiKey key = server.runtime.admin().createApiKey(
                    "local", new ApiKeyScope(Set.of("optional_note", "bare_ping"), Set.of(), false), Actors.CLI);
            listed = server.console("GET", "/console/api/conches", null, true);
            rows = listed.json().path("data").path("conches");
            assertFlagsMatch(server, rows, "optional_note", false, true);
            assertFlagsMatch(server, rows, "bare_ping", false, true);
            assertThat(summary(rows, "optional_note").path("accountCount").asInt()).isZero();
            assertThat(summary(rows, "bare_ping").path("accountCount").asInt()).isZero();
            assertThat(accounts(server, "optional_note")).isEmpty();
            assertThat(accounts(server, "bare_ping")).isEmpty();

            TestServer.Reply optionalCall = server.call(
                    "GET", "/api/optional_note/ping", key.plaintext(), null, null, null);
            assertThat(optionalCall.status()).isEqualTo(409);
            assertThat(optionalCall.json().path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(optionalCall.json().path("error").path("message").asText()).isEqualTo("还没有账号。请到控制台为这个规则包新建账号并填写凭证。");
            TestServer.Reply bareCall = server.call(
                    "GET", "/api/bare_ping/ping", key.plaintext(), null, null, null);
            assertThat(bareCall.status()).isEqualTo(409);
            assertThat(bareCall.json().path("error").path("message").asText()).isEqualTo("还没有账号。请到控制台为这个规则包新建账号并填写凭证。");

            server.runtime.admin().createAccount("optional_note", "ada", Actors.CLI);
            server.runtime.admin().createAccount("bare_ping", "ada", Actors.CLI);
            listed = server.console("GET", "/console/api/conches", null, true);
            rows = listed.json().path("data").path("conches");
            assertFlagsMatch(server, rows, "optional_note", false, false);
            assertFlagsMatch(server, rows, "bare_ping", false, false);
            assertThat(summary(rows, "optional_note").path("accountCount").asInt()).isEqualTo(1);
            JsonNode ada = account(accounts(server, "optional_note"), "ada");
            assertThat(ada.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(ada.path("missingFields")).isEmpty();
            assertThat(ada.path("credentialsUnreadable").asBoolean()).isFalse();
            JsonNode bareAccount = account(accounts(server, "bare_ping"), "ada");
            assertThat(bareAccount.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(bareAccount.path("missingFields")).isEmpty();

            TestServer.Reply ready = server.call(
                    "GET", "/api/optional_note/ping", key.plaintext(), null, null, null);
            assertThat(ready.status()).as(ready.body()).isEqualTo(200);
            TestServer.Reply bareReady = server.call(
                    "GET", "/api/bare_ping/ping", key.plaintext(), null, null, null);
            assertThat(bareReady.status()).as(bareReady.body()).isEqualTo(200);
        }
    }

    private static byte[] optionalNote() {
        return LocalConchBehaviorTest.ping("optional_note", "1.0.0")
                .replace(
                        "credentials: {}",
                        """
                        credentials:
                          note:
                            type: text
                            label: 备注
                            required: false
                        """)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode summary(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.path("conch").asText())) {
                return row;
            }
        }
        throw new AssertionError("missing " + name);
    }

    private static JsonNode accounts(TestServer server, String conch) throws Exception {
        TestServer.Reply listed = server.console("GET", "/console/api/conches/" + conch + "/accounts", null, true);
        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        return listed.json().path("data").path("accounts");
    }

    private static JsonNode account(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.path("name").asText())) {
                return row;
            }
        }
        throw new AssertionError("missing account " + name);
    }

    private static void assertFlagsMatch(
            TestServer server, JsonNode rows, String name, boolean subscribed, boolean needsConfiguration) throws Exception {
        JsonNode summary = null;
        for (JsonNode row : rows) {
            if (name.equals(row.path("conch").asText())) {
                summary = row;
            }
        }
        assertThat(summary).as(name).isNotNull();
        assertThat(summary.path("subscribed").isBoolean()).isTrue();
        assertThat(summary.path("needsConfiguration").isBoolean()).isTrue();
        assertThat(summary.path("subscribed").asBoolean()).isEqualTo(subscribed);
        assertThat(summary.path("needsConfiguration").asBoolean()).isEqualTo(needsConfiguration);

        TestServer.Reply detail = server.console("GET", "/console/api/conches/" + name, null, true);
        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        JsonNode body = detail.json().path("data");
        assertThat(body.path("subscribed").isBoolean()).isTrue();
        assertThat(body.path("needsConfiguration").isBoolean()).isTrue();
        assertThat(body.path("subscribed").asBoolean()).isEqualTo(summary.path("subscribed").asBoolean());
        assertThat(body.path("needsConfiguration").asBoolean()).isEqualTo(summary.path("needsConfiguration").asBoolean());
    }

    private static HttpResponse<String> post(TestServer server, String path, String type, byte[] body) throws Exception {
        return server.http.send(
                HttpRequest.newBuilder(URI.create(server.base() + path))
                        .header("X-Hermit-Console", "1")
                        .header("Content-Type", type)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] multipart(String boundary, String filename, byte[] yaml) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] prefix = head.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + yaml.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(yaml, 0, body, prefix.length, yaml.length);
        System.arraycopy(suffix, 0, body, prefix.length + yaml.length, suffix.length);
        return body;
    }
}
