package dev.hermit.crab.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.ShellPackage;
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

    @Test
    void invalidMasterKeyPrintsTheReason() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"account", "list", "legacy_oa"},
                name -> switch (name) {
                    case "HERMIT_HOME" -> temp.resolve("bad-key").toString();
                    case "HERMIT_MASTER_KEY" -> "not-a-key";
                    default -> null;
                },
                InputStream.nullInputStream(),
                stdout(),
                stderr(err));
        String text = err.toString(StandardCharsets.UTF_8);
        assertThat(code).isEqualTo(1);
        assertThat(text).contains("HERMIT_MASTER_KEY");
        assertThat(text).doesNotContain("MasterKeyException");
        assertThat(text).doesNotContain("not-a-key");
    }

    @Test
    void apiKeyCreateRejectsBareAndUnknownActions() throws Exception {
        Path root = temp.resolve("keys");
        installLegacy(root);
        Function<String, String> env = env(root);
        ByteArrayOutputStream bareErr = new ByteArrayOutputStream();
        int bare = new CrabCommand().run(
                new String[] {"apikey", "create", "bare", "--actions", "list_todo"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(bareErr));
        assertThat(bare).isEqualTo(1);
        assertThat(bareErr.toString(StandardCharsets.UTF_8)).contains("shell.action");

        ByteArrayOutputStream missingErr = new ByteArrayOutputStream();
        int missing = new CrabCommand().run(
                new String[] {"apikey", "create", "ghost", "--actions", "legacy_oa.missing"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(missingErr));
        assertThat(missing).isEqualTo(1);
        assertThat(missingErr.toString(StandardCharsets.UTF_8)).contains("能力不存在");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int created = new CrabCommand().run(
                new String[] {"apikey", "create", "reader", "--actions", "legacy_oa.list_todo"},
                env,
                InputStream.nullInputStream(),
                stdout(out),
                stderr(new ByteArrayOutputStream()));
        assertThat(created).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("id ");

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "apikey", "list")).isZero();
        String keys = listed.toString(StandardCharsets.UTF_8);
        assertThat(keys).contains("reader");
        assertThat(keys).doesNotContain("bare").doesNotContain("ghost");
    }

    @Test
    void auditListPrintsNamesWithoutSecretsAndPages() throws Exception {
        Path root = temp.resolve("audit");
        installLegacy(root);
        Function<String, String> env = env(root);
        String configValue = "http://oa.example/only-this-config-value";
        assertThat(run(env, InputStream.nullInputStream(), "account", "add", "legacy_oa", "alice")).isZero();
        ByteArrayInputStream in = new ByteArrayInputStream((PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int wrote = new CrabCommand().run(
                new String[] {"credential", "set", "legacy_oa", "alice", "password"},
                env,
                in,
                stdout(),
                stderr(err));
        assertThat(wrote).isZero();
        assertThat(run(env, InputStream.nullInputStream(), "config", "set", "legacy_oa", "base_url", configValue)).isZero();

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "audit", "list", "--shell", "legacy_oa")).isZero();
        String text = listed.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("CREDENTIALS_WRITTEN").contains("password").contains("CONFIG_PUT").contains("base_url");
        assertThat(text).contains("alice").contains("OFFICIAL").contains("cli");
        assertThat(text).doesNotContain("{").doesNotContain("detail");
        SecretChecks.absent(text, PASSWORD);
        SecretChecks.absent(text, configValue);
        assertThat(text.indexOf("CONFIG_PUT")).isLessThan(text.indexOf("CREDENTIALS_WRITTEN"));

        ByteArrayOutputStream newest = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), newest, "audit", "list", "--limit", "1")).isZero();
        String first = newest.toString(StandardCharsets.UTF_8).trim();
        assertThat(first.lines()).hasSize(1);
        assertThat(first).contains("CONFIG_PUT").contains("base_url").doesNotContain("CREDENTIALS_WRITTEN");
        SecretChecks.absent(first, configValue);

        ByteArrayOutputStream older = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), older,
                "audit", "list", "--shell", "legacy_oa", "--limit", "1", "--page", "2")).isZero();
        String second = older.toString(StandardCharsets.UTF_8).trim();
        assertThat(second.lines()).hasSize(1);
        assertThat(second).contains("CREDENTIALS_WRITTEN").contains("password").contains("alice");
        SecretChecks.absent(second, PASSWORD);

        ByteArrayOutputStream account = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), account,
                "audit", "list", "--shell", "legacy_oa", "--account", "alice")).isZero();
        String alice = account.toString(StandardCharsets.UTF_8);
        assertThat(alice).contains("CREDENTIALS_WRITTEN").contains("ACCOUNT_CREATED");
        assertThat(alice).doesNotContain("CONFIG_PUT");
        SecretChecks.absent(alice, PASSWORD);

        ByteArrayOutputStream other = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), other, "audit", "list", "--shell", "other_shell")).isZero();
        assertThat(other.toString(StandardCharsets.UTF_8)).isEmpty();

        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"audit", "list", "--limit", "0"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(bad));
        assertThat(code).isEqualTo(1);
        assertThat(bad.toString(StandardCharsets.UTF_8)).contains("limit is out of range");

        for (byte[] blob : ciphertexts(root)) {
            SecretChecks.absent(text, Base64.getEncoder().encodeToString(blob));
            SecretChecks.absent(text, HexFormat.of().formatHex(blob));
        }
    }

    @Test
    void syncWithoutReefUrlExits() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"sync"},
                env(temp.resolve("nosync")),
                InputStream.nullInputStream(),
                stdout(),
                stderr(err));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("reef.url is not configured");
    }

    private static List<byte[]> ciphertexts(Path root) throws Exception {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT ciphertext FROM credentials");
             ResultSet resultSet = statement.executeQuery()) {
            List<byte[]> blobs = new ArrayList<>();
            while (resultSet.next()) {
                blobs.add(resultSet.getBytes(1));
            }
            assertThat(blobs).isNotEmpty();
            return blobs;
        }
    }

    private static void installLegacy(Path root) throws Exception {
        byte[] yaml;
        try (InputStream in = CliTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            yaml = in.readAllBytes();
        }
        KeyPair keys = Ed25519Keys.generate();
        byte[] zip = ShellPackage.write(yaml, keys.getPrivate());
        Path version = root.resolve("shells").resolve("legacy_oa").resolve("1.0.0");
        Files.createDirectories(version);
        Files.write(version.resolve("package.zip"), zip);
        Files.write(version.resolve("shell.yaml"), yaml);
        Files.writeString(version.getParent().resolve("current"), "1.0.0\n");
        Path pub = root.resolve("reef.pub");
        Files.writeString(pub, Ed25519Keys.writePublicPem(keys.getPublic()));
        Files.writeString(root.resolve("config.yml"), "reef:\n  public_key: \"" + pub.toAbsolutePath() + "\"\n");
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
