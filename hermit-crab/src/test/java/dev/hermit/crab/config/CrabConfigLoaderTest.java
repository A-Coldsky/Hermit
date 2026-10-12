package dev.hermit.crab.config;

import java.nio.file.Files;
import java.nio.file.Path;

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
        assertThat(config.reportIntervalSeconds()).isEqualTo(60);
        assertThat(config.capabilityTimeoutSeconds()).isEqualTo(60);
        assertThat(config.sessionIdleMinutes()).isEqualTo(10);
        assertThat(config.maxLoginFailures()).isEqualTo(3);
        assertThat(config.reefUrl()).isNull();
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
}
