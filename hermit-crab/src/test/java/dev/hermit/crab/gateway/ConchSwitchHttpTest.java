package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.Invoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 整个规则包的本机开关：停用后全权限 Key 也调不了，启用后恢复，重启后仍在。 */
class ConchSwitchHttpTest {
    private static final String PASSWORD = "S3cr3t-switch";

    @TempDir
    Path temp;

    @Test
    void switchedOffConchRejectsCallsUntilEnabledAgain() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("switch"), PASSWORD, config)) {
            String key = server.apiKey.plaintext();
            server.configureAccount("alice");
            TestServer.Reply before = server.call("GET", "/api/legacy_oa/list_todo?page=1", key, "alice", null, null);
            assertThat(before.status()).isEqualTo(200);
            assertThat(server.call("GET", "/openapi.json", key, null, null, null).body()).contains("/api/legacy_oa/list_todo");
            assertThat(detail(server).path("switchedOff").asBoolean()).isFalse();

            TestServer.Reply off = server.console("POST", "/console/api/conches/legacy_oa/disable", "{}", true);
            assertThat(off.status()).isEqualTo(200);
            assertThat(off.json().path("data").path("switchedOff").asBoolean()).isTrue();

            // 全权限 Key 也被拒，且在 Key 权限检查之前。
            TestServer.Reply rejected = server.call("GET", "/api/legacy_oa/list_todo?page=1", key, "alice", null, null);
            assertThat(rejected.status()).isEqualTo(409);
            assertThat(rejected.json().path("error").path("code").asText()).isEqualTo("CONCH_DISABLED");
            assertThat(rejected.json().path("error").path("message").asText()).isEqualTo(Invoker.CONCH_SWITCHED_OFF_CALL);
            assertThat(server.call("GET", "/openapi.json", key, null, null, null).body())
                    .doesNotContain("/api/legacy_oa/list_todo");
            assertThat(detail(server).path("switchedOff").asBoolean()).isTrue();
            // 每个能力自己的开关不受整包开关影响。
            assertThat(detail(server).path("capabilities").get(0).path("disabled").asBoolean()).isFalse();
            assertThat(server.console("GET", "/console/api/conches", null, true).json()
                    .path("data").path("conches").get(0).path("switchedOff").asBoolean()).isTrue();
            assertThat(server.runtime.conchSwitchedOffNames()).containsExactly("legacy_oa");

            TestServer.Reply on = server.console("POST", "/console/api/conches/legacy_oa/enable", "{}", true);
            assertThat(on.status()).isEqualTo(200);
            assertThat(on.json().path("data").path("switchedOff").asBoolean()).isFalse();
            TestServer.Reply after = server.call("GET", "/api/legacy_oa/list_todo?page=1", key, "alice", null, null);
            assertThat(after.status()).isEqualTo(200);
            assertThat(server.call("GET", "/openapi.json", key, null, null, null).body()).contains("/api/legacy_oa/list_todo");

            TestServer.Reply missing = server.console("POST", "/console/api/conches/no_such/disable", "{}", true);
            assertThat(missing.status()).isGreaterThanOrEqualTo(400);
            assertThat(server.runtime.conchSwitchedOffNames()).isEmpty();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode detail(TestServer server) throws Exception {
        return server.console("GET", "/console/api/conches/legacy_oa", null, true).json().path("data");
    }
}
