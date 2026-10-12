package dev.hermit.crab.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import dev.hermit.crab.CrabMain;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.DatabaseInUseException;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CliTest {
    private static final String PASSWORD = "HermitCliSecret-77c1aa09e4";

    @TempDir
    Path temp;

    @Test
    void credentialSetRejectsArgumentValues() throws Exception {
        installLegacy(temp);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"credential", "set", "legacy_oa", "alice", "password", PASSWORD},
                env(temp),
                InputStream.nullInputStream(),
                stdout(),
                stderr(err));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("credential values must not be passed as arguments");
        SecretChecks.absent(err.toString(StandardCharsets.UTF_8), PASSWORD);
        assertThat(temp.resolve("data").resolve("crab.mv.db")).doesNotExist();
    }

    @Test
    void credentialSetReadsStdinAndPrintsNamesOnly() throws Exception {
        installLegacy(temp);
        Function<String, String> env = env(temp);
        assertThat(run(env, InputStream.nullInputStream(), "account", "add", "legacy_oa", "alice")).isZero();
        ByteArrayInputStream in = new ByteArrayInputStream((PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"credential", "set", "legacy_oa", "alice", "password"},
                env,
                in,
                stdout(out),
                stderr(err));
        assertThat(code).isZero();
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "credential", "list", "legacy_oa", "alice")).isZero();
        assertThat(listed.toString(StandardCharsets.UTF_8)).contains("password");
        SecretChecks.absent(listed.toString(StandardCharsets.UTF_8), PASSWORD);
        SecretChecks.absent(out.toString(StandardCharsets.UTF_8), PASSWORD);
        SecretChecks.absent(err.toString(StandardCharsets.UTF_8), PASSWORD);
    }

    @Test
    void lockedDatabaseExitsWithTheConsoleHint() throws Exception {
        CrabPaths pathsAgain = new CrabPaths(temp.resolve("locked"));
        try (CrabDatabase ignored = new CrabDatabase(pathsAgain)) {
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CrabMain.class.getName(),
                    "account",
                    "list",
                    "legacy_oa");
            builder.environment().put("HERMIT_HOME", pathsAgain.root().toString());
            builder.environment().remove("HERMIT_MASTER_KEY");
            Path outputFile = temp.resolve("locked-out.txt");
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            String output = Files.readString(outputFile).trim();
            if (!finished) {
                process.destroyForcibly();
            }
            assertThat(finished).isTrue();
            assertThat(process.exitValue()).isEqualTo(2);
            assertThat(output).isEqualTo(DatabaseInUseException.MESSAGE);
        }
    }

    private static int run(Function<String, String> env, InputStream in, String... args) {
        return run(env, in, new ByteArrayOutputStream(), args);
    }

    private static int run(Function<String, String> env, InputStream in, ByteArrayOutputStream out, String... args) {
        return new CrabCommand().run(args, env, in, stdout(out), stderr(new ByteArrayOutputStream()));
    }

    private static Function<String, String> env(Path root) {
        return name -> "HERMIT_HOME".equals(name) ? root.toString() : null;
    }

    private static void installLegacy(Path root) throws Exception {
        Path dir = root.resolve("shells").resolve("legacy_oa");
        Files.createDirectories(dir.resolve("1.0.0"));
        Files.writeString(dir.resolve("current"), "1.0.0\n");
        try (InputStream in = CliTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            Files.writeString(dir.resolve("1.0.0").resolve("shell.yaml"), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static PrintStream stdout() {
        return stdout(new ByteArrayOutputStream());
    }

    private static PrintStream stdout(ByteArrayOutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private static PrintStream stderr(ByteArrayOutputStream err) {
        return new PrintStream(err, true, StandardCharsets.UTF_8);
    }
}
