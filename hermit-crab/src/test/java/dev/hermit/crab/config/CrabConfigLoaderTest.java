package dev.hermit.crab.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrabConfigLoaderTest {
    private static final String TOKEN = "HermitReefToken-91ab33c0";

    @TempDir
    Path temp;

    @Test
    void missingFileUsesDefaults() {
        CrabConfig config = CrabConfigLoader.load(new CrabPaths(temp));
        assertThat(config.serverHost()).isEqualTo("127.0.0.1");
        assertThat(config.serverPort()).isEqualTo(7777);
        assertThat(config.syncIntervalSeconds()).isEqualTo(300);
        assertThat(config.reportEnabled()).isTrue();
        assertThat(config.reportIntervalSeconds()).isEqualTo(1800);
        assertThat(config.capabilityTimeoutSeconds()).isEqualTo(60);
        assertThat(config.sessionIdleMinutes()).isEqualTo(10);
        assertThat(config.maxLoginFailures()).isEqualTo(3);
        assertThat(config.mcpMaxConcurrencyPerAccount()).isEqualTo(4);
        assertThat(config.reefUrl()).isNull();
        assertThat(config.mcpAllowedOrigins()).isEmpty();
        assertThat(config.tlsCaDir()).isNull();
        assertThat(config.tlsSystemTrust()).isTrue();
    }

    @Test
    void mcpAllowedOriginsNormalizeAndRejectABadEntry() throws Exception {
        Path file = new CrabPaths(temp).configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                mcp:
                  allowed_origins:
                    - https://MCP.Example:443
                    - http://10.0.0.8:7777
                """);
        CrabConfig config = CrabConfigLoader.load(file);
        assertThat(config.mcpAllowedOrigins()).containsExactly("https://mcp.example", "http://10.0.0.8:7777");

        Files.writeString(file, """
                mcp:
                  allowed_origins:
                    - "http://evil.example/%s"
                """.formatted(TOKEN));
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .satisfies(ex -> {
                    SecretChecks.absent(ex.getMessage(), TOKEN);
                    assertThat(ex.getMessage()).contains("mcp.allowed_origins[0]").contains("origin");
                });

        Files.writeString(file, """
                mcp:
                  other: 1
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("mcp.other")
                .hasMessageContaining("unknown key");
    }

    @Test
    void unknownKeyNamesThePathAndNotTheToken() throws Exception {
        Path file = new CrabPaths(temp).configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                reef:
                  token: %s
                extra: 1
                """.formatted(TOKEN));
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .satisfies(ex -> {
                    SecretChecks.absent(ex.getMessage(), TOKEN);
                    assertThat(ex.getMessage()).contains("extra").contains("unknown key");
                });
    }

    @Test
    void reportIntervalBelowSixtyIsRejected() throws Exception {
        Path file = new CrabPaths(temp).configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                report:
                  interval_seconds: 59
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("report.interval_seconds")
                .hasMessageContaining("out of range");

        Files.writeString(file, """
                report:
                  enabled: true
                """);
        CrabConfig omitted = CrabConfigLoader.load(file);
        assertThat(omitted.reportEnabled()).isTrue();
        assertThat(omitted.reportIntervalSeconds()).isEqualTo(1800);
        assertThat(omitted.syncIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void mcpConcurrencyDefaultsToFourAndRejectsOutOfRange() throws Exception {
        Path file = new CrabPaths(temp).configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                engine:
                  mcp_max_concurrency_per_account: 8
                """);
        assertThat(CrabConfigLoader.load(file).mcpMaxConcurrencyPerAccount()).isEqualTo(8);
        for (int value : new int[] {0, 33}) {
            Files.writeString(file, """
                    engine:
                      mcp_max_concurrency_per_account: %d
                    """.formatted(value));
            assertThatThrownBy(() -> CrabConfigLoader.load(file))
                    .isInstanceOf(ConfigFileException.class)
                    .hasMessageContaining("engine.mcp_max_concurrency_per_account");
        }
    }

    @Test
    void tlsDirectoryAndSystemTrustAreOptionalAndHaveNoInsecureSwitch() throws Exception {
        Path file = new CrabPaths(temp).configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                tls:
                  ca_dir: trust/extra
                  system_trust: false
                """);
        CrabConfig config = CrabConfigLoader.load(file);
        assertThat(config.tlsCaDir()).isEqualTo("trust/extra");
        assertThat(config.tlsSystemTrust()).isFalse();
        assertThat(config.tlsCaDirectory(temp)).isEqualTo(temp.resolve("trust/extra").toAbsolutePath().normalize());

        Files.writeString(file, """
                tls:
                  system_trust: "yes"
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("tls.system_trust")
                .hasMessageContaining("boolean");

        Files.writeString(file, """
                tls:
                  insecure_skip_verify: true
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("tls.insecure_skip_verify")
                .hasMessageContaining("unknown key");
    }

    @Test
    void engineConnectionSettingsUseDefaultsAndRejectOutOfRange() throws Exception {
        Path file = new CrabPaths(temp).configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                engine: {}
                """);
        CrabConfig defaults = CrabConfigLoader.load(file);
        assertThat(defaults.connectTimeoutMs()).isEqualTo(15_000);
        assertThat(defaults.bodyIdleTimeoutMs()).isEqualTo(5_000);
        assertThat(defaults.maxHeaderLineBytes()).isEqualTo(65_536);
        assertThat(defaults.maxHeaderCount()).isEqualTo(500);
        assertThat(defaults.engineSettings().connectTimeout()).isEqualTo(Duration.ofMillis(15_000));
        assertThat(defaults.engineSettings().bodyIdleTimeout()).isEqualTo(Duration.ofMillis(5_000));
        assertThat(defaults.engineSettings().headers().maxLineBytes()).isEqualTo(65_536);
        assertThat(defaults.engineSettings().headers().maxHeaderCount()).isEqualTo(500);

        Files.writeString(file, """
                engine:
                  connect_timeout_ms: 400
                  body_idle_timeout_ms: 250
                  max_header_line_bytes: 2048
                  max_header_count: 12
                """);
        CrabConfig custom = CrabConfigLoader.load(file);
        assertThat(custom.connectTimeoutMs()).isEqualTo(400);
        assertThat(custom.bodyIdleTimeoutMs()).isEqualTo(250);
        assertThat(custom.maxHeaderLineBytes()).isEqualTo(2048);
        assertThat(custom.maxHeaderCount()).isEqualTo(12);

        Files.writeString(file, """
                engine:
                  connect_timeout_ms: 0
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("engine.connect_timeout_ms")
                .hasMessageContaining("out of range");

        Files.writeString(file, """
                engine:
                  body_idle_timeout_ms: 600001
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("engine.body_idle_timeout_ms");

        Files.writeString(file, """
                engine:
                  max_header_line_bytes: 255
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("engine.max_header_line_bytes");

        Files.writeString(file, """
                engine:
                  max_header_count: 10001
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("engine.max_header_count");

        Files.writeString(file, """
                engine:
                  validate_after_inactivity_ms: 1000
                """);
        assertThatThrownBy(() -> CrabConfigLoader.load(file))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("engine.validate_after_inactivity_ms")
                .hasMessageContaining("unknown key");
    }
}
