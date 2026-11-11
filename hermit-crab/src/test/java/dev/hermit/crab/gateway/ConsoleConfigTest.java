package dev.hermit.crab.gateway;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.LoginPause;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleConfigTest {
    private static final String PASSWORD = "HermitConsoleSecret-a91c0e77b2";
    private static final String WRONG = "HermitWrongSecret-b18d";

    @TempDir
    Path temp;

    @Test
    void credentialPutRejectsEmptyBodyAndMissingRequiredFields() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            TestServer.Reply created = server.console(
                    "POST", "/console/api/conches/legacy_oa/accounts", "{\"name\":\"carol\"}", true);
            assertThat(created.status()).isEqualTo(200);
            String path = "/console/api/conches/legacy_oa/accounts/carol/credentials";

            TestServer.Reply empty = server.console("PUT", path, "{}", true);
            assertThat(empty.status()).isEqualTo(400);
            assertThat(empty.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(empty.json().path("error").path("message").asText())
                    .contains("用户名（username）")
                    .contains("密码（password）")
                    .doesNotContain("credential fields are required");
            assertThat(empty.json().path("error").path("message").asText().indexOf("用户名（username）"))
                    .isLessThan(empty.json().path("error").path("message").asText().indexOf("密码（password）"));
            assertThat(empty.json().has("traceId")).isFalse();
            assertThat(empty.json().path("requestId").asText()).isNotBlank();
            assertThat(empty.json().path("requestNote").asText()).isEqualTo("请求编号，不可查追踪");
            assertThat(empty.headers().keySet()).noneMatch(name -> name.equalsIgnoreCase("X-Hermit-Trace"));

            TestServer.Reply blank = server.console("PUT", path, "", true);
            assertThat(blank.status()).isEqualTo(400);
            assertThat(blank.json().path("error").path("message").asText())
                    .contains("用户名（username）")
                    .contains("密码（password）")
                    .doesNotContain("credential fields are required");

            TestServer.Reply partial = server.console("PUT", path, "{\"username\":\"carol\"}", true);
            assertThat(partial.status()).isEqualTo(400);
            assertThat(partial.json().path("error").path("message").asText())
                    .contains("密码（password）")
                    .contains("填写凭证")
                    .doesNotContain("missing required credentials");
            assertThat(partial.json().has("traceId")).isFalse();
            assertThat(partial.json().path("requestId").asText()).isNotBlank();
            assertThat(partial.json().path("requestNote").asText()).isEqualTo("请求编号，不可查追踪");
            assertThat(partial.headers().keySet()).noneMatch(name -> name.equalsIgnoreCase("X-Hermit-Trace"));

            TestServer.Reply full = server.console(
                    "PUT", path, "{\"username\":\"carol\",\"password\":\"" + PASSWORD + "\"}", true);
            assertThat(full.status()).isEqualTo(200);
            assertThat(full.json().path("data").path("updated").asBoolean()).isTrue();
            SecretChecks.absent(full.body(), PASSWORD);

            TestServer.Reply rename = server.console("PUT", path, "{\"username\":\"carol-2\"}", true);
            assertThat(rename.status()).isEqualTo(200);

            TestServer.Reply cleared = server.console("PUT", path, "{\"password\":\"\"}", true);
            assertThat(cleared.status()).isEqualTo(400);
            assertThat(cleared.json().path("error").path("message").asText()).contains("密码（password）");
            assertThat(cleared.json().has("traceId")).isFalse();
            assertThat(cleared.json().path("requestNote").asText()).contains("不可查追踪");
        }
    }

    @Test
    void apiKeyCreateRejectsBareNamesAndUnknownCapabilities() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            TestServer.Reply bare = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"bare\",\"actions\":[\"list_todo\"]}", true);
            assertThat(bare.status()).isEqualTo(400);
            assertThat(bare.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(bare.json().path("error").path("message").asText()).contains("conch.action");

            TestServer.Reply missing = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"ghost\",\"actions\":[\"legacy_oa.missing\"]}", true);
            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.json().path("error").path("message").asText()).contains("能力不存在");

            TestServer.Reply created = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"reader\",\"actions\":[\"legacy_oa.list_todo\"],\"readOnly\":true}",
                    true);
            assertThat(created.status()).isEqualTo(200);
            assertThat(created.json().path("data").path("scope").path("all").asBoolean()).isFalse();
            assertThat(created.json().path("data").path("scope").path("actions").get(0).asText())
                    .isEqualTo("legacy_oa.list_todo");

            TestServer.Reply listed = server.console("GET", "/console/api/apikeys", null, true);
            assertThat(names(listed)).contains("reader").doesNotContain("bare", "ghost");
        }
    }

    @Test
    void apiKeyCreateRejectsUnknownConchesAndAllowsDisabledTombstones() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            TestServer.Reply installed = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"scoped\",\"conches\":[\"legacy_oa\"]}", true);
            assertThat(installed.status()).isEqualTo(200);
            assertThat(installed.json().path("data").path("scope").path("all").asBoolean()).isFalse();

            TestServer.Reply unknown = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"ghost-pack\",\"conches\":[\"no_such_pack\"]}", true);
            assertThat(unknown.status()).isEqualTo(400);
            assertThat(unknown.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(unknown.json().path("error").path("message").asText())
                    .contains("规则包不存在")
                    .contains("no_such_pack");

            TestServer.Reply several = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"mixed\",\"conches\":[\"legacy_oa\",\"missing_pack\",\"another_missing\"]}",
                    true);
            assertThat(several.status()).isEqualTo(400);
            assertThat(several.json().path("error").path("message").asText())
                    .contains("another_missing")
                    .contains("missing_pack")
                    .doesNotContain("legacy_oa");

            server.runtime.registry().remove("legacy_oa");
            server.runtime.disabledConches().mark("legacy_oa", "1.0.0", Instant.parse("2026-10-30T00:00:00Z"));
            TestServer.Reply disabled = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"paused\",\"conches\":[\"legacy_oa\"]}", true);
            assertThat(disabled.status()).isEqualTo(200);
            assertThat(disabled.json().path("data").path("scope").path("conches").get(0).asText())
                    .isEqualTo("legacy_oa");

            TestServer.Reply bare = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"bare-still\",\"conches\":[\"legacy_oa\"],\"actions\":[\"list_todo\"]}",
                    true);
            assertThat(bare.status()).isEqualTo(400);
            assertThat(bare.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(bare.json().path("error").path("message").asText()).contains("conch.action");

            TestServer.Reply listed = server.console("GET", "/console/api/apikeys", null, true);
            assertThat(names(listed)).contains("scoped", "paused").doesNotContain("ghost-pack", "mixed", "bare-still");

            TestServer.Reply nested = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"nested\",\"scope\":{\"conches\":[\"legacy_oa\"]}}",
                    true);
            assertThat(nested.status()).isEqualTo(400);
            assertThat(nested.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(nested.json().path("error").path("message").asText()).isEqualTo("unknown field: scope");

            TestServer.Reply extra = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"extra\",\"readOnly\":false,\"comment\":\"nope\"}",
                    true);
            assertThat(extra.status()).isEqualTo(400);
            assertThat(extra.json().path("error").path("message").asText()).isEqualTo("unknown field: comment");

            TestServer.Reply open = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"open-reef\"}", true);
            assertThat(open.status()).isEqualTo(400);
            assertThat(open.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(open.json().path("error").path("message").asText())
                    .isEqualTo(dev.hermit.crab.store.ApiKeyScope.MISSING_SCOPE);

            TestServer.Reply emptyArrays = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"empty-arrays\",\"conches\":[],\"actions\":[],\"all\":false}",
                    true);
            assertThat(emptyArrays.status()).isEqualTo(400);
            assertThat(emptyArrays.json().path("error").path("message").asText())
                    .isEqualTo(dev.hermit.crab.store.ApiKeyScope.MISSING_SCOPE);

            TestServer.Reply mixed = server.console(
                    "POST",
                    "/console/api/apikeys",
                    "{\"name\":\"mixed-all\",\"all\":true,\"conches\":[\"legacy_oa\"]}",
                    true);
            assertThat(mixed.status()).isEqualTo(400);
            assertThat(mixed.json().path("error").path("message").asText())
                    .isEqualTo(dev.hermit.crab.store.ApiKeyScope.ALL_EXCLUDES_NAMES);

            TestServer.Reply badAll = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"bad-all\",\"all\":\"yes\"}", true);
            assertThat(badAll.status()).isEqualTo(400);
            assertThat(badAll.json().path("error").path("message").asText()).isEqualTo("all must be a boolean");

            TestServer.Reply createdAll = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"open-reef\",\"all\":true,\"readOnly\":true}", true);
            assertThat(createdAll.status()).isEqualTo(200);
            assertThat(createdAll.json().path("data").path("scope").path("all").asBoolean()).isTrue();
            assertThat(createdAll.json().path("data").path("scope").path("conches")).isEmpty();
            assertThat(createdAll.json().path("data").path("scope").path("actions")).isEmpty();
            assertThat(createdAll.json().path("data").path("scope").path("readOnly").asBoolean()).isTrue();

            TestServer.Reply afterRejects = server.console("GET", "/console/api/apikeys", null, true);
            assertThat(names(afterRejects))
                    .contains("open-reef")
                    .doesNotContain("nested", "extra", "empty-arrays", "mixed-all", "bad-all");
        }
    }

    @Test
    void loginPauseIsVisibleAndCountsAsPending() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            server.configureAccount("alice");
            JsonNode ready = conchRow(server);
            assertThat(ready.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(ready.path("loginPaused").asBoolean()).isFalse();
            JsonNode account = account(server, "alice");
            assertThat(account.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(account.path("loginPaused").asBoolean()).isFalse();
            assertThat(account.path("loginPauseReason").isNull()).isTrue();
            assertThat(account.path("loginPauseRecovery").isNull()).isTrue();

            server.runtime.admin().writeCredentials(server.conch, "alice", Map.of("password", WRONG), Actors.CLI);
            for (int i = 0; i < 3; i++) {
                TestServer.Reply failed = server.call(
                        "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), "alice", null, null);
                assertThat(failed.status()).isEqualTo(422);
                assertThat(failed.json().path("error").path("message").asText()).doesNotContain("暂停");
            }

            JsonNode paused = account(server, "alice");
            assertThat(paused.path("loginPaused").asBoolean()).isTrue();
            assertThat(paused.path("loginPauseReason").asText()).isEqualTo(LoginPause.REASON);
            assertThat(paused.path("loginPauseRecovery").asText()).isEqualTo(LoginPause.RECOVERY);
            assertThat(paused.path("loginPauseRecovery").asText()).contains("测试登录");
            assertThat(paused.path("needsConfiguration").asBoolean()).isFalse();
            JsonNode pending = conchRow(server);
            assertThat(pending.path("loginPaused").asBoolean()).isTrue();
            assertThat(pending.path("needsConfiguration").asBoolean()).isTrue();
            TestServer.Reply detail = server.console("GET", "/console/api/conches/legacy_oa", null, true);
            JsonNode detailData = detail.json().path("data");
            assertThat(detailData.path("loginPaused").asBoolean()).isTrue();
            assertThat(detailData.path("driver").asText()).isEqualTo("http");
            assertThat(detailData.path("subscribed").isBoolean()).isTrue();
            assertThat(detailData.path("needsConfiguration").isBoolean()).isTrue();
            assertThat(detailData.path("subscribed").asBoolean()).isEqualTo(pending.path("subscribed").asBoolean());
            assertThat(detailData.path("needsConfiguration").asBoolean()).isTrue();
            assertThat(detailData.path("needsConfiguration").asBoolean())
                    .isEqualTo(pending.path("needsConfiguration").asBoolean());
            assertThat(account(server, "alice").path("needsConfiguration").asBoolean()).isFalse();

            TestServer.Reply failedLogin = server.console(
                    "POST", "/console/api/conches/legacy_oa/accounts/alice/test-login", "{}", true);
            assertThat(failedLogin.status()).isEqualTo(422);
            assertThat(failedLogin.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(failedLogin.json().has("traceId")).isFalse();
            assertThat(failedLogin.json().path("requestId").asText()).isNotBlank();
            assertThat(failedLogin.json().path("requestNote").asText()).contains("不可查追踪");
            assertThat(failedLogin.headers().keySet()).noneMatch(name -> name.equalsIgnoreCase("X-Hermit-Trace"));
            assertThat(account(server, "alice").path("loginPaused").asBoolean()).isTrue();
            TestServer.Reply stillPaused = server.call(
                    "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), "alice", null, null);
            assertThat(stillPaused.status()).isEqualTo(422);
            assertThat(stillPaused.json().path("error").path("message").asText()).contains("暂停");

            server.runtime.admin().writeCredentials(server.conch, "alice", Map.of("password", PASSWORD), Actors.CLI);
            TestServer.Reply login = server.console(
                    "POST", "/console/api/conches/legacy_oa/accounts/alice/test-login", "{}", true);
            assertThat(login.status()).isEqualTo(200);
            assertThat(account(server, "alice").path("loginPaused").asBoolean()).isFalse();
            assertThat(conchRow(server).path("needsConfiguration").asBoolean()).isFalse();
            assertThat(conchRow(server).path("loginPaused").asBoolean()).isFalse();
        }
    }

    private static JsonNode conchRow(TestServer server) throws Exception {
        TestServer.Reply listed = server.console("GET", "/console/api/conches", null, true);
        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        return listed.json().path("data").path("conches").get(0);
    }

    private static JsonNode account(TestServer server, String name) throws Exception {
        TestServer.Reply listed = server.console("GET", "/console/api/conches/legacy_oa/accounts", null, true);
        assertThat(listed.status()).isEqualTo(200);
        for (JsonNode row : listed.json().path("data").path("accounts")) {
            if (name.equals(row.path("name").asText())) {
                return row;
            }
        }
        throw new AssertionError("account was not listed");
    }

    private static java.util.List<String> names(TestServer.Reply listed) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (JsonNode row : listed.json().path("data").path("apiKeys")) {
            names.add(row.path("name").asText());
        }
        return names;
    }
}
