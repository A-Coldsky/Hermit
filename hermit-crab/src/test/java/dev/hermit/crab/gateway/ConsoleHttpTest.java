package dev.hermit.crab.gateway;

import java.nio.file.Path;
import java.util.List;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleHttpTest {
    private static final String PASSWORD = "HermitConsoleSecret-a91c0e77b2";

    @TempDir
    Path temp;

    @Test
    void requiresLoopbackHeaderAndHidesSecrets() throws Exception {
        CrabConfig config = CrabConfig.defaults()
                .withListen("127.0.0.1", 0)
                .withDisabled(List.of("legacy_oa.list_todo"));
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            TestServer.Reply denied = server.console("GET", "/console/api/conches", null, false);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(denied.json().path("error").path("message").asText()).isEqualTo("console access denied");

            server.configureAccount("alice");
            TestServer.Reply names = server.console(
                    "GET", "/console/api/conches/legacy_oa/accounts/alice/credentials", null, true);
            assertThat(names.status()).isEqualTo(200);
            assertThat(names.json().path("data").path("fields").toString()).contains("password");
            SecretChecks.absent(names.body(), PASSWORD);

            TestServer.Reply created = server.console(
                    "POST", "/console/api/apikeys", "{\"name\":\"desk\",\"readOnly\":true}", true);
            assertThat(created.status()).isEqualTo(200);
            String plaintext = created.json().path("data").path("plaintext").asText();
            if (!plaintext.startsWith("hk_") || plaintext.length() < 20) {
                throw new AssertionError("create response did not include an api key plaintext");
            }
            TestServer.Reply listed = server.console("GET", "/console/api/apikeys", null, true);
            SecretChecks.absent(listed.body(), plaintext);
            assertThat(listed.json().path("data").path("apiKeys").get(1).path("prefix").asText()).hasSize(8);

            TestServer.Reply sync = server.console("POST", "/console/api/sync", "{}", true);
            assertThat(sync.status()).isEqualTo(409);
            assertThat(sync.json().path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            TestServer.Reply syncStatus = server.console("GET", "/console/api/sync", null, true);
            assertThat(syncStatus.status()).isEqualTo(409);
            TestServer.Reply preview = server.console("GET", "/console/api/reports/preview", null, true);
            assertThat(preview.status()).isEqualTo(409);
            assertThat(preview.json().path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            TestServer.Reply send = server.console("POST", "/console/api/reports/send", "{}", true);
            assertThat(send.status()).isEqualTo(409);

            TestServer.Reply enabled = server.console(
                    "POST", "/console/api/capabilities/legacy_oa/list_todo/enable", "{}", true);
            assertThat(enabled.status()).isEqualTo(200);
            assertThat(enabled.json().path("data").path("disabled").asBoolean()).isTrue();
            TestServer.Reply hidden = server.call(
                    "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), "alice", null, null);
            assertThat(hidden.status()).isEqualTo(404);
            assertThat(hidden.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");

            TestServer.Reply debug = server.console("POST", "/console/api/debug", "{\"enabled\":true}", true);
            assertThat(debug.status()).isEqualTo(200);
            assertThat(debug.json().path("data").path("enabled").asBoolean()).isTrue();
            assertThat(server.console("GET", "/console/api/debug", null, true).json().path("data").path("enabled").asBoolean())
                    .isTrue();

            TestServer.Reply login = server.console(
                    "POST", "/console/api/conches/legacy_oa/accounts/alice/test-login", "{}", true);
            assertThat(login.status()).isEqualTo(200);
            assertThat(login.json().path("data").path("ok").asBoolean()).isTrue();
            SecretChecks.absent(login.body(), PASSWORD);
        }
    }

    @Test
    void conchSwitchCanDisableACapability() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("switch"), PASSWORD, CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            server.configureAccount("alice");
            server.runtime.setCapabilityDisabled("legacy_oa", "save_note", true, Actors.CONSOLE);
            TestServer.Reply missing = server.call(
                    "POST", "/api/legacy_oa/save_note", server.apiKey.plaintext(), "alice", null, "{\"text\":\"hello\"}");
            assertThat(missing.status()).isEqualTo(404);
            TestServer.Reply openapi = server.call("GET", "/openapi.json", null, null, null, null);
            assertThat(openapi.body()).doesNotContain("save_note");
        }
    }
}
