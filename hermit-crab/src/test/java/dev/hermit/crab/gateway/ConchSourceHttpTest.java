package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ConchSourceStore;
import dev.hermit.crab.store.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地包和正式包同名：两份都在，只有一份生效，可以切换，凭证只在用户同意时复制。 */
class ConchSourceHttpTest {
    private static final String PASSWORD = "S3cr3t-source";
    private static final String CALL = "/api/legacy_oa/list_todo?page=1";

    @TempDir
    Path temp;

    @Test
    void sameNameCopiesSwitchAndCopyOnlyWhenAsked() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("source"), PASSWORD, config)) {
            String key = server.apiKey.plaintext();
            String official = server.conch.version().toString();
            String localYaml = TestServer.conchYaml().replaceFirst("(?m)^version:.*$", "version: 9.9.9");
            assertThat(detail(server).path("localVersion").isNull()).isTrue();

            // 导入同名本地包：正式那份继续生效。
            server.runtime.localConches().importBytes(localYaml.getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            JsonNode both = detail(server);
            assertThat(both.path("source").asText()).isEqualTo("REEF");
            assertThat(both.path("version").asText()).isEqualTo(official);
            assertThat(both.path("localVersion").asText()).isEqualTo("9.9.9");
            assertThat(both.path("reefVersion").asText()).isEqualTo(official);
            assertThat(server.console("GET", "/console/api/conches", null, true).json()
                    .path("data").path("conches")).hasSize(1);

            // 切到本地：同一个包名、同一把 Key，账号要在本地这份上单独配置。
            TestServer.Reply toLocal = switchTo(server, "LOCAL", null);
            assertThat(toLocal.status()).as(toLocal.body()).isEqualTo(200);
            assertThat(toLocal.json().path("data").path("source").asText()).isEqualTo("LOCAL");
            assertThat(detail(server).path("version").asText()).isEqualTo("9.9.9");
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isNotEqualTo(200);
            server.configureAccount("alice");
            assertThat(server.runtime.admin().listAccounts("legacy_oa")).hasSize(1);
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isEqualTo(200);
            assertThat(new ConchSourceStore(server.runtime.dataSource()).load()).containsEntry("legacy_oa", Origin.LOCAL);

            // 重启后载入顺序是先正式后本地，仍然按记录选本地。
            ConchRegistry restarted = new ConchRegistry();
            ConchSourceStore store = new ConchSourceStore(server.runtime.dataSource());
            restarted.bindSelections(new ConchRegistry.Selections() {
                @Override
                public java.util.Map<String, Origin> load() {
                    return store.load();
                }

                @Override
                public void save(String conch, Origin origin) {
                }
            });
            restarted.scoped(Origin.OFFICIAL).put(server.conch);
            restarted.scoped(Origin.LOCAL).put(server.runtime.registry().find("legacy_oa", Origin.LOCAL).orElseThrow());
            assertThat(restarted.origin("legacy_oa")).isEqualTo(Origin.LOCAL);

            // 切回正式、不复制：正式这份没有账号，本地的凭证没有带过去。
            TestServer.Reply plain = switchTo(server, "REEF", false);
            assertThat(plain.json().path("data").path("copied").asBoolean()).isFalse();
            assertThat(detail(server).path("source").asText()).isEqualTo("REEF");
            assertThat(server.runtime.admin().listAccounts("legacy_oa")).isEmpty();
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isNotEqualTo(200);

            // 整包开关按包名，切换来源不会绕过它。
            assertThat(server.console("POST", "/console/api/conches/legacy_oa/disable", "{}", true).status()).isEqualTo(200);
            switchTo(server, "LOCAL", null);
            TestServer.Reply off = server.call("GET", CALL, key, "alice", null, null);
            assertThat(off.status()).isEqualTo(409);
            assertThat(off.json().path("error").path("code").asText()).isEqualTo("CONCH_DISABLED");
            assertThat(server.console("POST", "/console/api/conches/legacy_oa/enable", "{}", true).status()).isEqualTo(200);
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isEqualTo(200);

            // 本地这份再加一个账号并设为默认；在本地停用一个能力。
            server.configureAccount("bob");
            new dev.hermit.crab.store.AccountStore(server.runtime.dataSource())
                    .setDefault(Origin.LOCAL, "legacy_oa", "bob", Actors.CONSOLE);
            server.runtime.setCapabilityDisabled("legacy_oa", "list_todo", true, Actors.CONSOLE);

            // 切到正式并复制：账号、凭证和配置带过去，直接能调。
            TestServer.Reply copied = switchTo(server, "REEF", true);
            assertThat(copied.status()).as(copied.body()).isEqualTo(200);
            assertThat(copied.json().path("data").path("copied").asBoolean()).isTrue();
            assertThat(copied.json().path("data").path("copiedAccounts").asInt()).isEqualTo(2);
            // 默认账号跟本地包一致，不是按创建先后。
            assertThat(new dev.hermit.crab.store.AccountStore(server.runtime.dataSource())
                    .resolve(Origin.OFFICIAL, "legacy_oa", java.util.Optional.empty()).account()).isEqualTo("bob");
            // 能力开关按名字生效；在正式包上启用后，本地来源留下的停用记录也清掉，重启不会再停用。
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isNotEqualTo(200);
            server.runtime.setCapabilityDisabled("legacy_oa", "list_todo", false, Actors.CONSOLE);
            dev.hermit.crab.store.CapabilitySwitchStore switches =
                    new dev.hermit.crab.store.CapabilitySwitchStore(server.runtime.dataSource());
            assertThat(switches.disabledKeys(Origin.LOCAL)).isEmpty();
            assertThat(switches.disabledKeys(Origin.OFFICIAL)).isEmpty();
            assertThat(copied.json().path("data").path("copiedConfigKeys").asInt()).isEqualTo(1);
            assertThat(copied.body()).doesNotContain(PASSWORD);
            assertThat(server.runtime.admin().listAccounts("legacy_oa")).hasSize(2);
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isEqualTo(200);

            // 参数不对或没有那个来源时拒绝，不改生效来源。
            assertThat(switchTo(server, "OTHER", null).status()).isEqualTo(400);
            assertThat(switchTo(server, "LOCAL", true).status()).isEqualTo(400);
            assertThat(detail(server).path("source").asText()).isEqualTo("REEF");

            // 删掉本地那份：正式包继续生效，记录清掉，不能再切到本地。
            server.runtime.localConches().delete("legacy_oa", Actors.CONSOLE);
            assertThat(detail(server).path("localVersion").isNull()).isTrue();
            assertThat(new ConchSourceStore(server.runtime.dataSource()).load()).isEmpty();
            assertThat(switchTo(server, "LOCAL", null).status()).isEqualTo(400);
            assertThat(server.call("GET", CALL, key, "alice", null, null).status()).isEqualTo(200);
        }
    }

    private static TestServer.Reply switchTo(TestServer server, String source, Boolean copy) throws Exception {
        String body = copy == null
                ? "{\"source\":\"" + source + "\"}"
                : "{\"source\":\"" + source + "\",\"copy\":" + copy + "}";
        return server.console("POST", "/console/api/conches/legacy_oa/source", body, true);
    }

    private static JsonNode detail(TestServer server) throws Exception {
        return server.console("GET", "/console/api/conches/legacy_oa", null, true).json().path("data");
    }
}
