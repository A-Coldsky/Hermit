package dev.hermit.crab.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.testing.LegacyOaFixture;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayHttpTest {
    private static final String PASSWORD = "HermitGatewaySecret-b7e1c04a9d2f48e3";

    @TempDir
    Path temp;

    @Test
    void rejectsOutOfRangeInputInsteadOfClamping() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("bounds"), PASSWORD, CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            server.runtime.registry().put(ConchParser.parse("""
                    conch: bounds_conch
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: "http://127.0.0.1:9"
                    title: 分页边界
                    description: 用来确认越界参数会被拒绝。
                    credentials: {}
                    actions:
                      page_items:
                        title: 分页
                        description: 按页读取。
                        input:
                          page: { type: integer, minimum: 1, maximum: 100, required: true, description: 页码 }
                          pageSize: { type: integer, minimum: 1, maximum: 50, description: 每页条数 }
                          label: { type: string, required: true, description: 标签 }
                        steps:
                          - set:
                              seen: true
                        output:
                          seen: { type: boolean, description: 是否执行 }
                        examples:
                          - input: { page: 1, label: a }
                            output: { seen: true }
                        notes: 越界必须拒绝
                        idempotent: true
                        mcp: { read_only: true }
                    """));
            server.runtime.admin().createAccount("bounds_conch", "main", Actors.CLI);

            TestServer.Reply negative = server.call(
                    "GET", "/api/bounds_conch/page_items?page=-1&pageSize=20&label=a",
                    server.apiKey.plaintext(), null, null, null);
            assertThat(negative.status()).isEqualTo(400);
            assertThat(negative.json().path("success").asBoolean()).isFalse();
            assertThat(negative.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(negative.json().path("error").path("message").asText()).contains("page");
            assertThat(negative.json().path("data").isNull()).isTrue();

            TestServer.Reply huge = server.call(
                    "GET", "/api/bounds_conch/page_items?page=1&pageSize=1000&label=a",
                    server.apiKey.plaintext(), null, null, null);
            assertThat(huge.status()).isEqualTo(400);
            assertThat(huge.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(huge.json().path("error").path("message").asText()).contains("pageSize");

            TestServer.Reply missing = server.call(
                    "GET", "/api/bounds_conch/page_items?page=1&pageSize=20",
                    server.apiKey.plaintext(), null, null, null);
            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(missing.json().path("error").path("message").asText()).contains("label");

            TestServer.Reply typed = server.call(
                    "GET", "/api/bounds_conch/page_items?page=no&pageSize=20&label=a",
                    server.apiKey.plaintext(), null, null, null);
            assertThat(typed.status()).isEqualTo(400);
            assertThat(typed.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(typed.json().path("error").path("message").asText()).contains("page").doesNotContain("no");

            TestServer.Reply accepted = server.call(
                    "GET", "/api/bounds_conch/page_items?page=1&pageSize=20&label=a",
                    server.apiKey.plaintext(), null, null, null);
            assertThat(accepted.status()).isEqualTo(200);
            assertThat(accepted.json().path("success").asBoolean()).isTrue();
            assertThat(accepted.json().path("data").path("seen").asBoolean()).isTrue();
        }
    }

    @Test
    void callsCapabilitiesAccountsIdempotencyAndRestoredSessions() throws Exception {
        LogBag logs = new LogBag();
        Logger.getLogger("").addHandler(logs);
        String apiKeyPlaintext = null;
        CrabConfig config = CrabConfig.defaults()
                .withListen("127.0.0.1", 0)
                .withAliases(Map.of("/todo", "legacy_oa.list_todo", "/notes", "legacy_oa.save_note"));
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            apiKeyPlaintext = server.apiKey.plaintext();
            TestServer.Reply missingAccount = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", server.apiKey.plaintext(), null, null, null);
            assertThat(missingAccount.status()).isEqualTo(409);
            assertThat(missingAccount.json().path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(missingAccount.json().path("error").path("message").asText()).isEqualTo("no account configured");
            assertThat(missingAccount.header("X-Hermit-Trace")).isNotBlank();

            server.runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
            TestServer.Reply missingCredentials = server.call(
                    "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), null, null, null);
            assertThat(missingCredentials.status()).isEqualTo(409);
            String missing = missingCredentials.json().path("error").path("message").asText();
            assertThat(missing).contains("password").contains("username");
            SecretChecks.absent(missing, PASSWORD);

            server.configureAccount("alice");
            TestServer.Reply listed = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", server.apiKey.plaintext(), null, null, null);
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.json().path("success").asBoolean()).isTrue();
            assertThat(listed.json().path("data").path("items").isArray()).isTrue();
            assertThat(server.fixture.count("POST", "/login.aspx")).isEqualTo(1);

            server.configureAccount("bob");
            TestServer.Reply bob = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", server.apiKey.plaintext(), "bob", null, null);
            assertThat(bob.status()).isEqualTo(200);
            assertThat(cookieOwner(server.fixture, "/todo/list.aspx", "alice")).isTrue();
            assertThat(cookieOwner(server.fixture, "/todo/list.aspx", "bob")).isTrue();
            assertThat(sharedCookie(server.fixture)).isFalse();

            TestServer.Reply unknown = server.call(
                    "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), "nobody", null, null);
            assertThat(unknown.status()).isEqualTo(404);
            assertThat(unknown.json().path("error").path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");

            TestServer.Reply noKey = server.call("GET", "/api/legacy_oa/list_todo", null, null, null, null);
            assertThat(noKey.status()).isEqualTo(401);
            assertThat(noKey.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(noKey.json().path("error").path("message").asText()).isEqualTo("invalid api key");

            TestServer.Reply aliased = headerKey(server);
            assertThat(aliased.status()).isEqualTo(200);

            // 未加载的包名不能建 Key。墓碑里的名字可以，范围仍不含 legacy_oa。
            server.runtime.disabledConches().mark("other", "1.0.0", Instant.parse("2026-10-30T00:00:00Z"));
            var limited = server.runtime.admin().createApiKey(
                    "narrow", new ApiKeyScope(Set.of("other"), Set.of(), false), Actors.CLI);
            TestServer.Reply forbidden = server.call(
                    "GET", "/api/legacy_oa/list_todo", limited.plaintext(), null, null, null);
            assertThat(forbidden.status()).isEqualTo(403);
            assertThat(forbidden.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");

            var readOnly = server.runtime.admin().createApiKey(
                    "reader", new ApiKeyScope(Set.of(), Set.of(), true), Actors.CLI);
            TestServer.Reply readOnlyWrite = server.call(
                    "POST", "/api/legacy_oa/save_note", readOnly.plaintext(), "alice", null, "{\"text\":\"hello\"}");
            assertThat(readOnlyWrite.status()).isEqualTo(403);

            TestServer.Reply wrongMethod = server.call(
                    "POST", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), "alice", null, "{}");
            assertThat(wrongMethod.status()).isEqualTo(405);
            assertThat(wrongMethod.json().path("error").path("message").asText()).isEqualTo("method not allowed");

            TestServer.Reply badJson = server.call(
                    "POST", "/notes", server.apiKey.plaintext(), "alice", null, "not-json " + PASSWORD);
            assertThat(badJson.status()).isEqualTo(400);
            assertThat(badJson.json().path("error").path("message").asText()).isEqualTo("invalid json");
            SecretChecks.absent(badJson.body(), PASSWORD);

            int listsBefore = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply first = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-1", "{\"text\":\"hello\"}");
            TestServer.Reply replay = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-1", "{\"text\":\"hello\"}");
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.json().path("data").path("saved").asBoolean()).isTrue();
            assertThat(first.header("Idempotent-Replayed")).isNull();
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.body()).isEqualTo(first.body());
            assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.header("X-Hermit-Trace")).isEqualTo(first.json().path("traceId").asText());
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 1);

            TestServer.Reply openapi = server.call("GET", "/openapi.json", null, null, null, null);
            assertThat(openapi.status()).isEqualTo(200);
            assertThat(openapi.body()).contains("legacy_oa__list_todo");
            assertThat(openapi.body()).contains("\"/todo\"");
            assertThat(openapi.body()).contains("\"/notes\"");
            SecretChecks.absent(openapi.body(), PASSWORD);
            SecretChecks.absent(openapi.body(), server.apiKey.plaintext());
            SecretChecks.absent(logs.text(), PASSWORD);
            SecretChecks.absent(logs.text(), server.apiKey.plaintext());

            int logins = server.fixture.count("POST", "/login.aspx");
            server.server.close();
            server.runtime.close();
            try (var restarted = dev.hermit.crab.runtime.CrabRuntime.open(server.paths, config, name -> null)) {
                restarted.registry().put(server.conch);
                try (CrabHttpServer again = new CrabHttpServer(restarted)) {
                    again.start();
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + again.port() + "/api/legacy_oa/list_todo?page=1"))
                            .timeout(java.time.Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + server.apiKey.plaintext())
                            .header("X-Hermit-Account", "alice")
                            .GET()
                            .build();
                    java.net.http.HttpResponse<String> after = server.http.send(
                            request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    assertThat(after.statusCode()).isEqualTo(200);
                    assertThat(server.fixture.count("POST", "/login.aspx")).isEqualTo(logins);
                }
            }
        } finally {
            Logger.getLogger("").removeHandler(logs);
        }
        assertDatabaseHides(temp, PASSWORD, apiKeyPlaintext);
    }

    @Test
    void concurrentSameKeyCallsUpstreamOnce() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("concurrent"), PASSWORD, config)) {
            server.configureAccount("alice");
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<TestServer.Reply> left = new AtomicReference<>();
            AtomicReference<TestServer.Reply> right = new AtomicReference<>();
            Thread first = new Thread(() -> callSave(server, start, left));
            Thread second = new Thread(() -> callSave(server, start, right));
            first.start();
            second.start();
            start.countDown();
            first.join(20_000);
            second.join(20_000);
            assertThat(left.get().status()).isEqualTo(200);
            assertThat(right.get().body()).isEqualTo(left.get().body());
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);
        }
    }

    private static void callSave(TestServer server, CountDownLatch start, AtomicReference<TestServer.Reply> target) {
        try {
            start.await();
            target.set(server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "same", "{\"text\":\"hello\"}"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getClass().getName());
        }
    }

    private static TestServer.Reply headerKey(TestServer server) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(server.base() + "/todo?page=1"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("X-Hermit-Key", server.apiKey.plaintext())
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = server.http.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new TestServer.Reply(response.statusCode(), response.body(), response.headers().map());
    }

    private static boolean cookieOwner(LegacyOaFixture fixture, String path, String owner) {
        for (LegacyOaFixture.Hit hit : fixture.hits()) {
            if (path.equals(hit.path()) && hit.cookie() != null && hit.cookie().contains("sid-" + owner + "-")) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharedCookie(LegacyOaFixture fixture) {
        String alice = null;
        String bob = null;
        for (LegacyOaFixture.Hit hit : fixture.hits()) {
            if (!"/todo/list.aspx".equals(hit.path()) || hit.cookie() == null) {
                continue;
            }
            if (hit.cookie().contains("sid-alice-")) {
                alice = hit.cookie();
            }
            if (hit.cookie().contains("sid-bob-")) {
                bob = hit.cookie();
            }
        }
        return alice != null && alice.equals(bob);
    }

    private static void assertDatabaseHides(Path root, String... secrets) throws Exception {
        try (CrabDatabase database = new CrabDatabase(new dev.hermit.crab.store.CrabPaths(root))) {
            try (Connection connection = database.dataSource().getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet columns = statement.executeQuery("""
                         SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE
                         FROM INFORMATION_SCHEMA.COLUMNS
                         WHERE TABLE_SCHEMA = 'PUBLIC'
                         """)) {
                List<String[]> text = new ArrayList<>();
                List<String[]> binary = new ArrayList<>();
                while (columns.next()) {
                    String table = columns.getString(1);
                    String column = columns.getString(2);
                    String type = columns.getString(3);
                    if (!table.matches("[A-Za-z0-9_]+") || !column.matches("[A-Za-z0-9_]+")) {
                        continue;
                    }
                    if (type != null && (type.contains("CHAR") || type.contains("CLOB"))) {
                        text.add(new String[] {table, column});
                    } else if (type != null && (type.contains("BINARY") || type.contains("BLOB"))) {
                        binary.add(new String[] {table, column});
                    }
                }
                for (String[] column : text) {
                    try (Statement query = connection.createStatement();
                         ResultSet rows = query.executeQuery("SELECT \"" + column[1] + "\" FROM \"" + column[0] + "\"")) {
                        while (rows.next()) {
                            for (String secret : secrets) {
                                SecretChecks.absent(rows.getString(1), secret);
                            }
                        }
                    }
                }
                for (String[] column : binary) {
                    try (Statement query = connection.createStatement();
                         ResultSet rows = query.executeQuery("SELECT \"" + column[1] + "\" FROM \"" + column[0] + "\"")) {
                        while (rows.next()) {
                            byte[] value = rows.getBytes(1);
                            if (value == null) {
                                continue;
                            }
                            for (String secret : secrets) {
                                if (SecretChecks.indexOf(value, secret.getBytes(StandardCharsets.UTF_8)) >= 0) {
                                    throw new AssertionError("secret appeared in binary column " + column[0]);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (Files.isDirectory(root.resolve("logs"))) {
            try (var files = Files.walk(root.resolve("logs"))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    for (String secret : secrets) {
                        SecretChecks.absent(Files.readString(file), secret);
                    }
                }
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

}
