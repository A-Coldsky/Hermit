package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrabEntrypointTest {
    @TempDir
    Path temp;

    @Test
    void startsWithoutReefEnvOrBootstrap() throws Exception {
        Result result = run(Map.of());
        assertThat(result.status).as(result.err).isZero();
        String config = Files.readString(temp.resolve("home/config.yml"));
        assertThat(config).doesNotContain("url:").contains(generatedReport());
    }

    @Test
    void bootstrapFileSkipsReefEnvAndPubkeyLogin() throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("reef-bootstrap.json"), "{\"kind\":\"hermit-crab-bootstrap\"}\n");
        Result result = run(Map.of());
        assertThat(result.status).as(result.err).isZero();
        assertThat(result.err).doesNotContain("reef.pub");
        String config = Files.readString(home.resolve("config.yml"));
        assertThat(config).doesNotContain("url:").contains(generatedReport());
        assertThat(Files.getPosixFilePermissions(home.resolve("reef-bootstrap.json"))).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void urlWithoutTokenStillExits() throws Exception {
        Result result = run(Map.of("HERMIT_REEF_URL", "http://127.0.0.1:9"));
        assertThat(result.status).isEqualTo(1);
        assertThat(result.err).contains("HERMIT_REEF_AGENT_TOKEN");
    }

    @Test
    void envWithoutPubkeyStillRequiresOne() throws Exception {
        Result result = run(Map.of(
                "HERMIT_REEF_URL", "http://127.0.0.1:9",
                "HERMIT_REEF_AGENT_TOKEN", "token-value"));
        assertThat(result.status).isEqualTo(1);
        assertThat(result.err).contains("reef.pub");
    }

    @Test
    void existingConfigWithoutEnvKeepsReefLinesAndChecksThePort() throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), """
                reef:
                  url: "http://reef.internal"
                  token: "kept-token"
                server:
                  host: "127.0.0.1"
                  port: 7777
                """);
        Result ok = run(Map.of());
        assertThat(ok.status).as(ok.err).isZero();
        assertThat(Files.readString(home.resolve("config.yml"))).contains("http://reef.internal");

        Files.writeString(home.resolve("config.yml"), """
                server:
                  host: "127.0.0.1"
                  port: 1
                """);
        Result bad = run(Map.of());
        assertThat(bad.status).isEqualTo(1);
        assertThat(bad.err).contains("server.port");
    }

    @Test
    void consolePasswordStaysInTheEnvironmentAndOutOfConfig() throws Exception {
        String password = "console-entry-secret";
        String runuser = """
                #!/bin/sh
                printf '%s' "$HERMIT_CONSOLE_PASSWORD" > "$HERMIT_HOME/passed-password"
                exit 0
                """;
        Result result = run(Map.of("HERMIT_CONSOLE_PASSWORD", password), runuser);
        assertThat(result.status).as(result.err).isZero();
        SecretChecks.absent(result.out, password);
        SecretChecks.absent(result.err, password);
        SecretChecks.absent(Files.readString(temp.resolve("home/config.yml")), password);
        String passed = Files.readString(temp.resolve("home/passed-password"));
        if (!password.equals(passed)) {
            throw new AssertionError("entrypoint did not leave the console password in the process environment");
        }
    }

    @Test
    void blankConsolePasswordIsUnset() throws Exception {
        String runuser = """
                #!/bin/sh
                if [ "${HERMIT_CONSOLE_PASSWORD+x}" = x ]; then
                  printf set > "$HERMIT_HOME/passed-password"
                else
                  printf unset > "$HERMIT_HOME/passed-password"
                fi
                exit 0
                """;
        Result result = run(Map.of("HERMIT_CONSOLE_PASSWORD", "   "), runuser);
        assertThat(result.status).as(result.err).isZero();
        assertThat(Files.readString(temp.resolve("home/passed-password"))).isEqualTo("unset");
        assertThat(Files.readString(temp.resolve("home/config.yml"))).doesNotContain("HERMIT_CONSOLE_PASSWORD");
    }

    private Result run(Map<String, String> extra) throws Exception {
        return run(extra, "#!/bin/sh\nexit 0\n");
    }

    private Result run(Map<String, String> extra, String runuser) throws Exception {
        Path bin = temp.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("chown"), "#!/bin/sh\nexit 0\n");
        Files.writeString(bin.resolve("runuser"), runuser);
        Files.setPosixFilePermissions(bin.resolve("chown"), SetExec.EXEC);
        Files.setPosixFilePermissions(bin.resolve("runuser"), SetExec.EXEC);
        Path script = Path.of("../deploy/crab-entrypoint.sh");
        if (!Files.exists(script)) {
            script = Path.of("deploy/crab-entrypoint.sh");
        }
        ProcessBuilder builder = new ProcessBuilder("sh", script.toAbsolutePath().toString());
        builder.environment().clear();
        builder.environment().put("PATH", bin.toAbsolutePath() + ":/usr/bin:/bin");
        builder.environment().put("HERMIT_HOME", temp.resolve("home").toAbsolutePath().toString());
        builder.environment().put("HERMIT_CRAB_PORT", "7777");
        builder.environment().putAll(extra);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        int status = process.waitFor();
        return new Result(status, new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    private record Result(int status, String out, String err) {
    }

    private static String generatedReport() {
        return """
                sync:
                  interval_seconds: 300
                report:
                  enabled: true
                  interval_seconds: 1800
                """;
    }

    private static final class SetExec {
        static final java.util.Set<PosixFilePermission> EXEC = java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
    }
}
