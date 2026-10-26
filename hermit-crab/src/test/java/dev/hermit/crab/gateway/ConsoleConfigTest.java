package dev.hermit.crab.gateway;

import java.nio.file.Path;
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
                    "POST", "/console/api/shells/legacy_oa/accounts", "{\"name\":\"carol\"}", true);
            assertThat(created.status()).isEqualTo(200);
            String path = "/console/api/shells/legacy_oa/accounts/carol/credentials";

            TestServer.Reply empty = server.console("PUT", path, "{}", true);
            assertThat(empty.status()).isEqualTo(400);
            assertThat(empty.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(empty.json().path("error").path("message").asText()).contains("credential fields are required");

            TestServer.Reply blank = server.console("PUT", path, "", true);
            assertThat(blank.status()).isEqualTo(400);
            assertThat(blank.json().path("error").path("message").asText()).contains("credential fields are required");

            TestServer.Reply partial = server.console("PUT", path, "{\"username\":\"carol\"}", true);
            assertThat(partial.status()).isEqualTo(400);
            assertThat(partial.json().path("error").path("message").asText()).contains("password");

            TestServer.Reply full = server.console(
                    "PUT", path, "{\"username\":\"carol\",\"password\":\"" + PASSWORD + "\"}", true);
            assertThat(full.status()).isEqualTo(200);
            assertThat(full.json().path("data").path("updated").asBoolean()).isTrue();
            SecretChecks.absent(full.body(), PASSWORD);

            TestServer.Reply rename = server.console("PUT", path, "{\"username\":\"carol-2\"}", true);
            assertThat(rename.status()).isEqualTo(200);

            TestServer.Reply cleared = server.console("PUT", path, "{\"password\":\"\"}", true);
            assertThat(cleared.status()).isEqualTo(400);
            assertThat(cleared.json().path("error").path("message").asText()).contains("password");
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
            assertThat(bare.json().path("error").path("message").asText()).contains("shell.action");

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
            assertThat(created.json().path("data").path("scope").path("actions").get(0).asText())
                    .isEqualTo("legacy_oa.list_todo");

            TestServer.Reply listed = server.console("GET", "/console/api/apikeys", null, true);
            assertThat(names(listed)).contains("reader").doesNotContain("bare", "ghost");
        }
    }

    @Test
    void loginPauseIsVisibleAndCountsAsPending() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            server.configureAccount("alice");
            JsonNode ready = shellRow(server);
            assertThat(ready.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(ready.path("loginPaused").asBoolean()).isFalse();
            JsonNode account = account(server, "alice");
            assertThat(account.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(account.path("loginPaused").asBoolean()).isFalse();
            assertThat(account.path("loginPauseReason").isNull()).isTrue();
            assertThat(account.path("loginPauseRecovery").isNull()).isTrue();

            server.runtime.admin().writeCredentials(server.shell, "alice", Map.of("password", WRONG), Actors.CLI);
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
            JsonNode pending = shellRow(server);
            assertThat(pending.path("loginPaused").asBoolean()).isTrue();
            assertThat(pending.path("needsConfiguration").asBoolean()).isTrue();
            TestServer.Reply detail = server.console("GET", "/console/api/shells/legacy_oa", null, true);
            assertThat(detail.json().path("data").path("loginPaused").asBoolean()).isTrue();

            server.runtime.admin().writeCredentials(server.shell, "alice", Map.of("password", PASSWORD), Actors.CLI);
            TestServer.Reply login = server.console(
                    "POST", "/console/api/shells/legacy_oa/accounts/alice/test-login", "{}", true);
            assertThat(login.status()).isEqualTo(200);
            assertThat(account(server, "alice").path("loginPaused").asBoolean()).isFalse();
            assertThat(shellRow(server).path("needsConfiguration").asBoolean()).isFalse();
            assertThat(shellRow(server).path("loginPaused").asBoolean()).isFalse();
        }
    }

    private static JsonNode shellRow(TestServer server) throws Exception {
        TestServer.Reply listed = server.console("GET", "/console/api/shells", null, true);
        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        return listed.json().path("data").path("shells").get(0);
    }

    private static JsonNode account(TestServer server, String name) throws Exception {
        TestServer.Reply listed = server.console("GET", "/console/api/shells/legacy_oa/accounts", null, true);
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
