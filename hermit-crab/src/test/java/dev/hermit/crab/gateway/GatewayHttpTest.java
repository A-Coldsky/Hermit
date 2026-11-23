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

import com.fasterxml.jackson.databind.JsonNode;

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
    void anUpstreamFailureStillReturnsALookupableTrace() throws Exception {
        com.sun.net.httpserver.HttpServer upstream = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "upstream-down".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try (TestServer server = TestServer.open(
                temp.resolve("upstream-502"), PASSWORD, CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            server.configureAccount("alice");
            var doc = server.runtime.registry().find("legacy_oa").orElseThrow();
            server.runtime.admin().putConfig(
                    doc,
                    "base_url",
                    "http://127.0.0.1:" + upstream.getAddress().getPort() + "/",
                    Actors.CLI);
            TestServer.Reply failed = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", server.apiKey.plaintext(), null, null, null);
            String traceId = failed.json().path("traceId").asText();
            assertThat(traceId).isNotBlank();
            assertThat(failed.json().has("requestId")).isFalse();
            assertThat(failed.header("X-Hermit-Trace")).isEqualTo(traceId);
            assertThat(server.runtime.callLogs().findByTraceId(traceId)).isPresent();
        } finally {
            upstream.stop(0);
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
            assertThat(missingAccount.json().path("error").path("message").asText())
                    .isEqualTo("还没有账号。请到控制台为这个规则包新建账号并填写凭证。");
            assertThat(missingAccount.header("X-Hermit-Trace")).isNull();
            assertThat(missingAccount.json().has("traceId")).isFalse();
            assertThat(missingAccount.json().path("requestId").asText()).isNotBlank();
            assertThat(missingAccount.json().path("requestNote").asText()).isEqualTo("请求编号，不可查追踪");

            server.runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
            TestServer.Reply missingCredentials = server.call(
                    "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), null, null, null);
            assertThat(missingCredentials.status()).isEqualTo(409);
            String missing = missingCredentials.json().path("error").path("message").asText();
            assertThat(missing).contains("用户名（username）").contains("密码（password）");
            assertThat(missing.indexOf("用户名（username）")).isLessThan(missing.indexOf("密码（password）"));
            assertThat(missingCredentials.header("X-Hermit-Trace")).isNull();
            assertThat(missingCredentials.json().has("traceId")).isFalse();
            assertThat(missingCredentials.json().path("requestId").asText()).isNotBlank();
            assertThat(missingCredentials.json().path("requestNote").asText()).isEqualTo("请求编号，不可查追踪");
            SecretChecks.absent(missing, PASSWORD);

            server.configureAccount("alice");
            TestServer.Reply listed = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", server.apiKey.plaintext(), null, null, null);
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.json().path("success").asBoolean()).isTrue();
            assertThat(listed.json().path("data").path("items").isArray()).isTrue();
            assertThat(server.runtime.callLogs().findByTraceId(listed.json().path("traceId").asText())
                    .orElseThrow().call().channel()).isEqualTo("HTTP");
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
            assertThat(unknown.json().path("error").path("message").asText()).isEqualTo("没有这个账号。");

            TestServer.Reply noKey = server.call("GET", "/api/legacy_oa/list_todo", null, null, null, null);
            assertThat(noKey.status()).isEqualTo(401);
            assertThat(noKey.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(noKey.json().path("error").path("message").asText()).isEqualTo(UserMessages.MISSING_KEY);
            assertThat(noKey.json().has("traceId")).isFalse();
            assertThat(noKey.json().path("requestId").asText()).isNotBlank();
            assertThat(noKey.header("X-Hermit-Trace")).isNull();

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
            assertThat(wrongMethod.json().path("error").path("message").asText()).isEqualTo("这个能力要用 GET 调用。");
            assertThat(wrongMethod.header("Allow")).isEqualTo("GET");
            assertThat(wrongMethod.json().has("traceId")).isFalse();
            assertThat(wrongMethod.json().path("requestId").asText()).isNotBlank();

            TestServer.Reply badJson = server.call(
                    "POST", "/notes", server.apiKey.plaintext(), "alice", null, "not-json " + PASSWORD);
            assertThat(badJson.status()).isEqualTo(400);
            assertThat(badJson.json().path("error").path("message").asText()).isEqualTo("请求体不是合法的 JSON。");
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
    void accountQueryParameterSelectsTheAccountAndDoesNotEnterInput() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("account-query"), PASSWORD, config)) {
            server.configureAccount("alice");
            server.configureAccount("bob");
            String key = server.apiKey.plaintext();

            TestServer.Reply bob = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=bob", key, null, null, null);
            assertThat(bob.status()).isEqualTo(200);
            assertThat(bob.json().path("success").asBoolean()).isTrue();
            assertThat(bob.json().path("error").path("message").asText()).doesNotContain("输入无效");
            assertThat(callAccount(server, "legacy_oa", "list_todo")).isEqualTo("bob");
            assertThat(callOrigin(server, "legacy_oa", "list_todo")).isEqualTo("OFFICIAL");
            assertThat(cookieOwner(server.fixture, "/todo/list.aspx", "bob")).isTrue();
            assertThat(cookieOwner(server.fixture, "/todo/list.aspx", "alice")).isFalse();

            TestServer.Reply paged = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1&account=bob", key, null, null, null);
            assertThat(paged.status()).isEqualTo(200);
            assertThat(callAccount(server, "legacy_oa", "list_todo")).isEqualTo("bob");

            TestServer.Reply headerOnly = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1", key, "alice", null, null);
            assertThat(headerOnly.status()).isEqualTo(200);
            assertThat(callAccount(server, "legacy_oa", "list_todo")).isEqualTo("alice");
            assertThat(cookieOwner(server.fixture, "/todo/list.aspx", "alice")).isTrue();

            int lists = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply unknown = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=nobody", key, null, null, null);
            assertThat(unknown.status()).isEqualTo(404);
            assertThat(unknown.json().path("error").path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(unknown.json().path("error").path("message").asText()).isEqualTo("没有这个账号。");
            assertThat(unknown.json().path("error").path("message").asText()).doesNotContain("输入无效");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            TestServer.Reply conflict = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1&account=bob", key, "alice", null, null);
            assertThat(conflict.status()).isEqualTo(400);
            assertThat(conflict.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(conflict.json().path("error").path("message").asText())
                    .isEqualTo("请求头 X-Hermit-Account 与查询参数 account 不一致。");
            assertThat(conflict.json().path("data").isNull()).isTrue();
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            TestServer.Reply agreed = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=bob", key, "bob", null, null);
            assertThat(agreed.status()).isEqualTo(200);
            assertThat(callAccount(server, "legacy_oa", "list_todo")).isEqualTo("bob");

            TestServer.Reply blankQuery = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1&account=", key, "bob", null, null);
            assertThat(blankQuery.status()).isEqualTo(200);
            assertThat(callAccount(server, "legacy_oa", "list_todo")).isEqualTo("bob");

            TestServer.Reply defaultAccount = server.call(
                    "GET", "/api/legacy_oa/list_todo?page=1&account=", key, null, null, null);
            assertThat(defaultAccount.status()).isEqualTo(200);
            assertThat(callAccount(server, "legacy_oa", "list_todo")).isEqualTo("alice");

            lists = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply posted = server.call(
                    "POST", "/api/legacy_oa/save_note?account=bob", key, null, null, "{\"text\":\"hello\"}");
            assertThat(posted.status()).isEqualTo(200);
            assertThat(posted.json().path("data").path("saved").asBoolean()).isTrue();
            assertThat(callAccount(server, "legacy_oa", "save_note")).isEqualTo("bob");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists + 1);

            lists = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply postConflict = server.call(
                    "POST", "/api/legacy_oa/save_note?account=bob", key, "alice", null, "{\"text\":\"hello\"}");
            assertThat(postConflict.status()).isEqualTo(400);
            assertThat(postConflict.json().path("error").path("message").asText())
                    .isEqualTo("请求头 X-Hermit-Account 与查询参数 account 不一致。");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            server.runtime.disabledConches().mark("other", "1.0.0", Instant.parse("2026-10-30T00:00:00Z"));
            var limited = server.runtime.admin().createApiKey(
                    "narrow", new ApiKeyScope(Set.of("other"), Set.of(), false), Actors.CLI);
            lists = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply forbidden = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=bob", limited.plaintext(), null, null, null);
            assertThat(forbidden.status()).isEqualTo(403);
            assertThat(forbidden.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            TestServer.Reply forbiddenConflict = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=bob", limited.plaintext(), "alice", null, null);
            assertThat(forbiddenConflict.status()).isEqualTo(403);
            assertThat(forbiddenConflict.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            TestServer.Reply openapi = server.call("GET", "/openapi.json", null, null, null, null);
            JsonNode listTodo = openapi.json().path("paths").path("/api/legacy_oa/list_todo").path("get");
            JsonNode account = queryParameter(listTodo, "account");
            assertThat(account.path("required").asBoolean()).isFalse();
            assertThat(account.path("schema").path("description").asText()).contains("命名账号");
            assertThat(headerParameter(listTodo, "X-Hermit-Account")).isNotNull();
            JsonNode save = openapi.json().path("paths").path("/api/legacy_oa/save_note").path("post");
            assertThat(queryParameter(save, "account").path("in").asText()).isEqualTo("query");

            server.runtime.registry().put(ConchParser.parse("""
                    conch: account_conch
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: "http://127.0.0.1:9"
                    title: 账号字段
                    description: 能力入参占用 account。
                    credentials: {}
                    actions:
                      lookup:
                        title: 按业务账号查询
                        description: 查询一个业务账号。
                        input:
                          account: { type: string, required: true, description: 业务账号 }
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
                      echo:
                        title: 回显正文
                        description: 正文里的 account 是业务字段。
                        method: POST
                        input:
                          account: { type: string, required: true, description: 业务账号 }
                        steps:
                          - set:
                              seen: "{{input.account}}"
                        output:
                          seen: { type: string, description: 回显的业务账号 }
                        examples:
                          - input: { account: biz }
                            output: { seen: biz }
                        notes: 查询参数选择 Hermit 账号。
                        mcp: { read_only: false }
                    """));
            server.runtime.admin().createAccount("account_conch", "alice", Actors.CLI);
            server.runtime.admin().createAccount("account_conch", "bob", Actors.CLI);

            TestServer.Reply looked = server.call(
                    "GET", "/api/account_conch/lookup?account=biz&_account=bob", key, null, null, null);
            assertThat(looked.status()).isEqualTo(200);
            assertThat(looked.json().path("data").path("seen").asText()).isEqualTo("biz");
            assertThat(callAccount(server, "account_conch", "lookup")).isEqualTo("bob");

            TestServer.Reply lookedDefault = server.call(
                    "GET", "/api/account_conch/lookup?account=biz", key, null, null, null);
            assertThat(lookedDefault.status()).isEqualTo(200);
            assertThat(lookedDefault.json().path("data").path("seen").asText()).isEqualTo("biz");
            assertThat(callAccount(server, "account_conch", "lookup")).isEqualTo("alice");

            int lookups = server.runtime.callLogs().list("account_conch", "lookup", 20).size();
            TestServer.Reply lookupConflict = server.call(
                    "GET", "/api/account_conch/lookup?account=biz&_account=bob", key, "alice", null, null);
            assertThat(lookupConflict.status()).isEqualTo(400);
            assertThat(lookupConflict.json().path("error").path("message").asText())
                    .isEqualTo("请求头 X-Hermit-Account 与查询参数 _account 不一致。");
            assertThat(server.runtime.callLogs().list("account_conch", "lookup", 20)).hasSize(lookups);

            TestServer.Reply echoed = server.call(
                    "POST", "/api/account_conch/echo?account=bob", key, null, null, "{\"account\":\"biz\"}");
            assertThat(echoed.status()).isEqualTo(200);
            assertThat(echoed.json().path("data").path("seen").asText()).isEqualTo("biz");
            assertThat(callAccount(server, "account_conch", "echo")).isEqualTo("bob");

            TestServer.Reply documented = server.call("GET", "/openapi.json", null, null, null, null);
            JsonNode lookup = documented.json().path("paths").path("/api/account_conch/lookup").path("get");
            assertThat(queryParameter(lookup, "account").path("required").asBoolean()).isTrue();
            assertThat(queryParameter(lookup, "_account").path("required").asBoolean()).isFalse();
            assertThat(queryParameter(lookup, "_account").path("schema").path("description").asText()).contains("命名账号");
            JsonNode echo = documented.json().path("paths").path("/api/account_conch/echo").path("post");
            assertThat(queryParameter(echo, "account").path("required").asBoolean()).isFalse();
            assertThat(echo.path("requestBody").path("content").path("application/json")
                    .path("schema").path("properties").has("account")).isTrue();

            server.runtime.setCapabilityDisabled("legacy_oa", "list_todo", true, Actors.CLI);
            lists = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply disabled = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=bob", key, null, null, null);
            assertThat(disabled.status()).isEqualTo(404);
            assertThat(disabled.json().path("error").path("code").asText()).isEqualTo("CAPABILITY_DISABLED");
            assertThat(disabled.json().path("error").path("message").asText())
                    .isEqualTo(dev.hermit.crab.runtime.Invoker.CAPABILITY_DISABLED_MESSAGE);
            TestServer.Reply disabledConflict = server.call(
                    "GET", "/api/legacy_oa/list_todo?account=bob", key, "alice", null, null);
            assertThat(disabledConflict.status()).isEqualTo(404);
            assertThat(disabledConflict.json().path("error").path("code").asText()).isEqualTo("CAPABILITY_DISABLED");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);
        }
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

    @Test
    void idempotencyKeyConflictsWhenTheBodyChangesAndReplaysTheSameBody() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("idem"), PASSWORD, config)) {
            server.configureAccount("alice");
            String same = "{\"text\":\"hello\"}";
            TestServer.Reply first = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-a", same);
            assertThat(first.status()).isEqualTo(200);
            int lists = server.fixture.count("GET", "/todo/list.aspx");

            TestServer.Reply replay = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-a", same);
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.body()).isEqualTo(first.body());
            assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            String spaced = "{ \"text\" : \"hello\" }";
            TestServer.Reply spacedConflict = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-a", spaced);
            assertThat(spacedConflict.status()).isEqualTo(409);
            assertThat(spacedConflict.json().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(spacedConflict.json().path("error").path("message").asText())
                    .isEqualTo(dev.hermit.crab.runtime.Invoker.IDEMPOTENCY_CONFLICT);
            assertThat(spacedConflict.header("Idempotent-Replayed")).isNull();
            assertThat(spacedConflict.body()).doesNotContain("hello");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            TestServer.Reply other = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-a", "{\"text\":\"other\"}");
            assertThat(other.status()).isEqualTo(409);
            assertThat(other.json().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(other.body()).doesNotContain("other");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);

            TestServer.Reply again = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-a", same);
            assertThat(again.body()).isEqualTo(first.body());
            assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");

            try (Connection connection = server.runtime.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(
                        "UPDATE idempotency SET body_sha256 = NULL WHERE \"key\" = 'note-a'")).isEqualTo(1);
            }
            TestServer.Reply legacy = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", "note-a", same);
            assertThat(legacy.status()).isEqualTo(409);
            assertThat(legacy.json().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(lists);
        }
    }

    @Test
    void postAdmissionOrderAndConcurrentEnableDoNotDropBodyIdempotencyOrAccount() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("post-toctou"), PASSWORD, config)) {
            server.configureAccount("alice");
            server.configureAccount("bob");
            String key = server.apiKey.plaintext();

            // 能力停用或不存在时，404/CAPABILITY_DISABLED 优先于非法 JSON 与非法 Idempotency-Key。
            server.runtime.setCapabilityDisabled("legacy_oa", "save_note", true, Actors.CONSOLE);
            TestServer.Reply disabledBadBody = server.call(
                    "POST", "/api/legacy_oa/save_note", key, "alice", "bad key", "not-json");
            assertThat(disabledBadBody.status()).isEqualTo(404);
            assertThat(disabledBadBody.json().path("error").path("code").asText()).isEqualTo("CAPABILITY_DISABLED");

            TestServer.Reply missingBadBody = server.call(
                    "POST", "/api/legacy_oa/no_such_action", key, "alice", "bad key", "not-json");
            assertThat(missingBadBody.status()).isEqualTo(404);
            assertThat(missingBadBody.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            server.runtime.setCapabilityDisabled("legacy_oa", "save_note", false, Actors.CONSOLE);

            // 模拟首次查询时能力尚未启用、进入 Invoker 前恰好启用的竞态：仍需完整读取正文、Idempotency-Key 和 ?account=。
            java.util.concurrent.atomic.AtomicBoolean hideOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
            server.server.setActionLookup((conch, action) -> {
                if ("legacy_oa".equals(conch) && "save_note".equals(action) && hideOnce.compareAndSet(true, false)) {
                    return java.util.Optional.empty();
                }
                return server.runtime.enabledAction(conch, action);
            });

            int listsBefore = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply raced = server.call(
                    "POST", "/api/legacy_oa/save_note?account=bob", key, null, "race-1", "{\"text\":\"hello\"}");
            assertThat(raced.status()).as(raced.body()).isEqualTo(200);
            assertThat(raced.json().path("data").path("saved").asBoolean()).isTrue();
            assertThat(callAccount(server, "legacy_oa", "save_note")).isEqualTo("bob");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 1);

            TestServer.Reply replay = server.call(
                    "POST", "/api/legacy_oa/save_note?account=bob", key, null, "race-1", "{\"text\":\"hello\"}");
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.body()).isEqualTo(raced.body());
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 1);

            TestServer.Reply conflict = server.call(
                    "POST", "/api/legacy_oa/save_note?account=bob", key, null, "race-1", "{\"text\":\"other\"}");
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.json().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

            // 同样的竞态下如果正文是非法 JSON，必须拒绝而不是带空入参执行。
            hideOnce.set(true);
            TestServer.Reply racedBadJson = server.call(
                    "POST", "/api/legacy_oa/save_note?account=bob", key, null, null, "not-json");
            assertThat(racedBadJson.status()).isEqualTo(400);
            assertThat(racedBadJson.json().path("error").path("message").asText()).isEqualTo("请求体不是合法的 JSON。");
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 1);
        }
    }

    @Test
    void idempotencyIsIsolatedByApiKeyAndSkipsDeadDownloadLinks() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("idem-key-iso"), PASSWORD, config)) {
            server.configureAccount("alice");
            String keyA = server.apiKey.plaintext();
            String keyB = server.runtime.admin().createApiKey(
                    "second-key", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CLI).plaintext();

            int listsBefore = server.fixture.count("GET", "/todo/list.aspx");
            TestServer.Reply firstA = server.call(
                    "POST", "/api/legacy_oa/save_note", keyA, "alice", "shared-idem-1", "{\"text\":\"from-a\"}");
            assertThat(firstA.status()).isEqualTo(200);
            assertThat(firstA.header("Idempotent-Replayed")).isNull();
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 1);

            // 另一把 API Key 使用相同的 Idempotency-Key 必须完全隔离：既不回放 keyA 的响应也不产生 409 冲突。
            TestServer.Reply firstB = server.call(
                    "POST", "/api/legacy_oa/save_note", keyB, "alice", "shared-idem-1", "{\"text\":\"from-b\"}");
            assertThat(firstB.status()).as(firstB.body()).isEqualTo(200);
            assertThat(firstB.header("Idempotent-Replayed")).isNull();
            assertThat(firstB.json().path("traceId").asText()).isNotEqualTo(firstA.json().path("traceId").asText());
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 2);

            // 各自重试同一请求体仍正常回放自己的缓存。
            TestServer.Reply replayA = server.call(
                    "POST", "/api/legacy_oa/save_note", keyA, "alice", "shared-idem-1", "{\"text\":\"from-a\"}");
            assertThat(replayA.status()).isEqualTo(200);
            assertThat(replayA.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replayA.body()).isEqualTo(firstA.body());

            TestServer.Reply replayB = server.call(
                    "POST", "/api/legacy_oa/save_note", keyB, "alice", "shared-idem-1", "{\"text\":\"from-b\"}");
            assertThat(replayB.status()).isEqualTo(200);
            assertThat(replayB.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replayB.body()).isEqualTo(firstB.body());
            assertThat(server.fixture.count("GET", "/todo/list.aspx")).isEqualTo(listsBefore + 2);

            // 含 download: true 的 POST 动作：暂存文件仍存活时回放，文件被清掉/过期后不返回死链接而重新执行。
            server.runtime.registry().put(ConchParser.parse("""
                    conch: dl_idem
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.8.0
                    base_url: "%s"
                    title: 下载幂等
                    description: 测试 POST 下载幂等。
                    credentials: {}
                    actions:
                      export_post:
                        title: 导出
                        description: 导出报表。
                        method: POST
                        steps:
                          - request: { method: GET, path: /files/report.bin, download: true }
                          - set: { file: "{{ response.file }}" }
                        output:
                          file: { type: object, description: 报表文件 }
                        examples:
                          - input: {}
                            output: { file: { fileId: f_1 } }
                        notes: 测试
                    """.formatted(server.fixture.baseUrl())));
            server.runtime.admin().createAccount("dl_idem", "alice", Actors.CLI);

            TestServer.Reply dl1 = server.call("POST", "/api/dl_idem/export_post", keyA, "alice", "dl-k1", "{}");
            assertThat(dl1.status()).as(dl1.body()).isEqualTo(200);
            String fileId1 = dl1.json().path("data").path("file").path("fileId").asText();
            assertThat(fileId1).startsWith("f_");

            TestServer.Reply dlReplay = server.call("POST", "/api/dl_idem/export_post", keyA, "alice", "dl-k1", "{}");
            assertThat(dlReplay.status()).isEqualTo(200);
            assertThat(dlReplay.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(dlReplay.json().path("data").path("file").path("fileId").asText()).isEqualTo(fileId1);

            assertThat(server.runtime.fileStage().delete(fileId1)).isTrue();
            TestServer.Reply dlFresh = server.call("POST", "/api/dl_idem/export_post", keyA, "alice", "dl-k1", "{}");
            assertThat(dlFresh.status()).as(dlFresh.body()).isEqualTo(200);
            assertThat(dlFresh.header("Idempotent-Replayed")).isNull();
            String fileId2 = dlFresh.json().path("data").path("file").path("fileId").asText();
            assertThat(fileId2).startsWith("f_").isNotEqualTo(fileId1);
            assertThat(server.runtime.fileStage().find(fileId2, server.apiKey.id())).isPresent();
        }
    }

    @Test
    void uploadFileRejectsReadOnlyKeysAndPublicBaseBlocksForeignHostHeader() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("files-hardening"), PASSWORD, config)) {
            String readOnlyKey = server.runtime.admin().createApiKey(
                    "ro-upload", new ApiKeyScope(Set.of(), Set.of(), true, true), Actors.CLI).plaintext();
            java.net.http.HttpResponse<String> denied = server.http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.base() + "/api/-/files?name=ro.txt"))
                            .header("Authorization", "Bearer " + readOnlyKey)
                            .header("Content-Type", "text/plain")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("hello"))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("FORBIDDEN").contains(CrabHttpServer.READ_ONLY_UPLOAD);
            assertThat(server.runtime.fileStage().list()).isEmpty();

            // 伪造外部 Host 头不能投毒下载链接。
            String payload = "safe-bytes";
            String rawReq = "POST /api/-/files?name=host.txt HTTP/1.1\r\n"
                    + "Host: attacker.example:9999\r\n"
                    + "Authorization: Bearer " + server.apiKey.plaintext() + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + payload.length() + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + payload;
            String rawResp;
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.server.port())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(rawReq.getBytes(StandardCharsets.UTF_8));
                rawResp = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(rawResp).startsWith("HTTP/1.1 200");
            assertThat(rawResp).doesNotContain("attacker.example");
            assertThat(rawResp).contains(server.runtime.fileBase() + "/api/-/files/");

            // resolvePublicBase 边界覆盖：显式 public_url 优先，回环与配置监听主机放行，非法/外部/重复 Host 回退。
            assertThat(CrabHttpServer.resolvePublicBase(
                    CrabConfig.defaults().withListen("0.0.0.0", 7777),
                    "http://127.0.0.1:7777",
                    "http",
                    List.of("attacker.example:7777")))
                    .isEqualTo("http://127.0.0.1:7777");
            assertThat(CrabHttpServer.resolvePublicBase(
                    CrabConfig.defaults().withListen("192.168.1.10", 7777),
                    "http://192.168.1.10:7777",
                    "http",
                    List.of("192.168.1.10:7777")))
                    .isEqualTo("http://192.168.1.10:7777");
            assertThat(CrabHttpServer.resolvePublicBase(
                    CrabConfig.defaults().withListen("127.0.0.1", 7777),
                    "http://127.0.0.1:7777",
                    "http",
                    List.of("localhost:9000")))
                    .isEqualTo("http://localhost:9000");
            assertThat(CrabHttpServer.resolvePublicBase(
                    CrabConfig.defaults().withListen("127.0.0.1", 7777),
                    "http://127.0.0.1:7777",
                    "http",
                    List.of("127.0.0.1:7777", "attacker.example")))
                    .isEqualTo("http://127.0.0.1:7777");
        }
    }

    @Test
    void openApiFiltersByApiKeyAndRejectsUnauthenticatedNonLoopbackRequests() throws Exception {
        CrabConfig config = CrabConfig.defaults()
                .withListen("127.0.0.1", 0)
                .withAliases(Map.of("/todo", "legacy_oa.list_todo", "/notes", "legacy_oa.save_note"));
        try (TestServer server = TestServer.open(temp.resolve("openapi-scope"), PASSWORD, config)) {
            server.runtime.registry().put(ConchParser.parse("""
                    conch: grouped_oa
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.10.0
                    base_url: "http://127.0.0.1:9"
                    title: 分组系统
                    description: 测试 OpenAPI 过滤。
                    credentials: {}
                    groups:
                      hr:
                        title: 人事管理
                      admin:
                        title: 系统管理
                    actions:
                      list_staff:
                        group: hr
                        title: 查员工
                        description: 只读。
                        idempotent: true
                        mcp: { read_only: true }
                        steps:
                          - set: { ok: true }
                        output:
                          ok: { type: boolean, description: 结果 }
                        examples:
                          - input: {}
                            output: { ok: true }
                        notes: 无
                      wipe_all:
                        group: admin
                        title: 清空
                        description: 写操作。
                        method: POST
                        mcp: { read_only: false }
                        steps:
                          - set: { ok: true }
                        output:
                          ok: { type: boolean, description: 结果 }
                        examples:
                          - input: {}
                            output: { ok: true }
                        notes: 无
                    """));
            server.runtime.registry().put(ConchParser.parse("""
                    conch: local_pkg
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    base_url: "http://127.0.0.1:9"
                    title: 本地包
                    description: 仅点名可见。
                    credentials: {}
                    actions:
                      ping:
                        title: 探测
                        description: 只读。
                        idempotent: true
                        mcp: { read_only: true }
                        steps:
                          - set: { ok: true }
                        output:
                          ok: { type: boolean, description: 结果 }
                        examples:
                          - input: {}
                            output: { ok: true }
                        notes: 无
                    """), dev.hermit.crab.store.Origin.LOCAL);

            // 1) 回环无 Key 保持兼容返回全量目录。
            TestServer.Reply loopbackAll = server.call("GET", "/openapi.json", null, null, null, null);
            assertThat(loopbackAll.status()).isEqualTo(200);
            assertThat(loopbackAll.body())
                    .contains("/api/legacy_oa/save_note")
                    .contains("/api/grouped_oa/list_staff")
                    .contains("/api/grouped_oa/wipe_all")
                    .contains("/api/local_pkg/ping");

            // 2) 带无效 Key 即使在回环也返回 401。
            TestServer.Reply invalidKey = server.call("GET", "/openapi.json", "hk_invalid_token", null, null, null);
            assertThat(invalidKey.status()).isEqualTo(401);
            assertThat(invalidKey.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");

            // 3) 只读 Key 只看得到只读能力与只读别名。
            String roKey = server.runtime.admin().createApiKey(
                    "ro-openapi", new ApiKeyScope(Set.of(), Set.of(), true, true), Actors.CLI).plaintext();
            TestServer.Reply roOpenApi = server.call("GET", "/openapi.json", roKey, null, null, null);
            assertThat(roOpenApi.status()).isEqualTo(200);
            assertThat(roOpenApi.body())
                    .contains("/api/legacy_oa/list_todo")
                    .contains("\"/todo\"")
                    .contains("/api/grouped_oa/list_staff")
                    .contains("/api/local_pkg/ping")
                    .doesNotContain("/api/legacy_oa/save_note")
                    .doesNotContain("\"/notes\"")
                    .doesNotContain("/api/grouped_oa/wipe_all")
                    .doesNotContain("系统管理");

            // 4) 分组窄 Key 只看到授权分组、标签，不泄漏未授权分组和未点名的本地包。
            String hrKey = server.runtime.admin().createApiKey(
                    "hr-openapi",
                    ApiKeyScope.explicit(false, Set.of("grouped_oa"), Set.of("grouped_oa:hr"), Set.of(), false),
                    Actors.CLI).plaintext();
            TestServer.Reply hrOpenApi = server.call("GET", "/openapi.json", hrKey, null, null, null);
            assertThat(hrOpenApi.status()).isEqualTo(200);
            assertThat(hrOpenApi.body())
                    .contains("/api/grouped_oa/list_staff")
                    .contains("人事管理")
                    .doesNotContain("/api/grouped_oa/wipe_all")
                    .doesNotContain("系统管理")
                    .doesNotContain("/api/legacy_oa/")
                    .doesNotContain("/api/local_pkg/");
        }

        // 5) 非回环远端请求未带 Key 返回 401；带有效 Key 返回 200 并按范围过滤。
        String nonLoopback = findNonLoopbackIpv4();
        if (nonLoopback != null) {
            CrabConfig remoteConfig = CrabConfig.defaults().withListen(nonLoopback, 0);
            try (dev.hermit.crab.runtime.CrabRuntime remoteRuntime = dev.hermit.crab.runtime.CrabRuntime.open(
                    new dev.hermit.crab.store.CrabPaths(temp.resolve("openapi-remote")), remoteConfig, name -> null);
                 CrabHttpServer remoteServer = new CrabHttpServer(remoteRuntime)) {
                remoteRuntime.registry().put(ConchParser.parse(TestServer.conchYaml()));
                String roKey = remoteRuntime.admin().createApiKey(
                        "remote-ro", new ApiKeyScope(Set.of(), Set.of(), true, true), Actors.CLI).plaintext();
                remoteServer.start();
                java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                String url = "http://" + nonLoopback + ":" + remoteServer.port() + "/openapi.json";

                java.net.http.HttpResponse<String> unauth = http.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(unauth.statusCode()).isEqualTo(401);
                assertThat(unauth.body()).contains("AUTH_FAILED");

                java.net.http.HttpResponse<String> authed = http.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                                .header("Authorization", "Bearer " + roKey)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(authed.statusCode()).isEqualTo(200);
                assertThat(authed.body())
                        .contains("/api/legacy_oa/list_todo")
                        .doesNotContain("/api/legacy_oa/save_note");
            }
        }
    }

    private static String findNonLoopbackIpv4() throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
        while (nics != null && nics.hasMoreElements()) {
            java.net.NetworkInterface nic = nics.nextElement();
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            java.util.Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                java.net.InetAddress address = addresses.nextElement();
                if (address instanceof java.net.Inet4Address
                        && !address.isLoopbackAddress()
                        && !address.isAnyLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
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

    private static String callAccount(TestServer server, String conch, String action) {
        return server.runtime.callLogs().list(conch, action, 5).get(0).account();
    }

    private static String callOrigin(TestServer server, String conch, String action) {
        return server.runtime.callLogs().list(conch, action, 5).get(0).origin();
    }

    private static JsonNode queryParameter(JsonNode operation, String name) {
        return parameter(operation, name, "query");
    }

    private static JsonNode headerParameter(JsonNode operation, String name) {
        return parameter(operation, name, "header");
    }

    private static JsonNode parameter(JsonNode operation, String name, String location) {
        JsonNode found = null;
        int seen = 0;
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText()) && location.equals(parameter.path("in").asText())) {
                found = parameter;
                seen++;
            }
        }
        assertThat(seen).as(name + " in " + location).isEqualTo(1);
        return found;
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
