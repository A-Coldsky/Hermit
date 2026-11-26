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
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.crab.CrabMain;
import dev.hermit.crab.store.ApiKeyScope;
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
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("凭证不能写在命令行参数里。");
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
            // 这两个变量会让 JVM 自己往 stderr 打一行 "Picked up ..."，混进要比对的输出。
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("JAVA_TOOL_OPTIONS");
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

    private static Path explicitEmptyRelatedYaml() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("hermit-common/src/test/resources/related/explicit-empty.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("explicit-empty.yaml not found");
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
        assertThat(bareErr.toString(StandardCharsets.UTF_8)).contains("conch.action");

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
    void apiKeyCreateRejectsUnknownConchesAndAllowsDisabledOnes() throws Exception {
        Path root = temp.resolve("conch-scope");
        installLegacy(root);
        Function<String, String> env = env(root);

        ByteArrayOutputStream unknownErr = new ByteArrayOutputStream();
        int unknown = new CrabCommand().run(
                new String[] {"apikey", "create", "ghost", "--conches", "no_such_pack"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(unknownErr));
        assertThat(unknown).isEqualTo(1);
        assertThat(unknownErr.toString(StandardCharsets.UTF_8)).contains("规则包不存在").contains("no_such_pack");

        ByteArrayOutputStream created = new ByteArrayOutputStream();
        int ok = new CrabCommand().run(
                new String[] {"apikey", "create", "scoped", "--conches", "legacy_oa"},
                env,
                InputStream.nullInputStream(),
                stdout(created),
                stderr(new ByteArrayOutputStream()));
        assertThat(ok).isZero();
        assertThat(created.toString(StandardCharsets.UTF_8)).contains("id ");

        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO disabled_conches (conch, version, disabled_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            statement.setString(1, "legacy_oa");
            statement.setString(2, "1.0.0");
            statement.executeUpdate();
        }
        ByteArrayOutputStream disabledOut = new ByteArrayOutputStream();
        ByteArrayOutputStream disabledErr = new ByteArrayOutputStream();
        int disabled = new CrabCommand().run(
                new String[] {"apikey", "create", "paused", "--conches", "legacy_oa"},
                env,
                InputStream.nullInputStream(),
                stdout(disabledOut),
                stderr(disabledErr));
        assertThat(disabled).as(disabledErr.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(disabledOut.toString(StandardCharsets.UTF_8)).contains("id ");
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
        assertThat(run(env, InputStream.nullInputStream(), listed, "audit", "list", "--conch", "legacy_oa")).isZero();
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
                "audit", "list", "--conch", "legacy_oa", "--limit", "1", "--page", "2")).isZero();
        String second = older.toString(StandardCharsets.UTF_8).trim();
        assertThat(second.lines()).hasSize(1);
        assertThat(second).contains("CREDENTIALS_WRITTEN").contains("password").contains("alice");
        SecretChecks.absent(second, PASSWORD);

        ByteArrayOutputStream account = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), account,
                "audit", "list", "--conch", "legacy_oa", "--account", "alice")).isZero();
        String alice = account.toString(StandardCharsets.UTF_8);
        assertThat(alice).contains("CREDENTIALS_WRITTEN").contains("ACCOUNT_CREATED");
        assertThat(alice).doesNotContain("CONFIG_PUT");
        SecretChecks.absent(alice, PASSWORD);

        ByteArrayOutputStream other = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), other, "audit", "list", "--conch", "other_conch")).isZero();
        assertThat(other.toString(StandardCharsets.UTF_8)).isEmpty();

        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"audit", "list", "--limit", "0"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(bad));
        assertThat(code).isEqualTo(1);
        assertThat(bad.toString(StandardCharsets.UTF_8)).contains("每页条数超出范围。");

        for (byte[] blob : ciphertexts(root)) {
            SecretChecks.absent(text, Base64.getEncoder().encodeToString(blob));
            SecretChecks.absent(text, HexFormat.of().formatHex(blob));
        }
    }

    @Test
    void auditListRejectsBadPagesAndLimitsInChinese() {
        Function<String, String> env = env(temp.resolve("audit-range"));
        assertThat(auditErr(env, "audit", "list", "--page", "0")).isEqualTo("页码超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--page", "-1")).isEqualTo("页码超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--page", "1.5")).isEqualTo("页码超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--page", "abc")).isEqualTo("页码超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--limit", "0")).isEqualTo("每页条数超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--limit", "-5")).isEqualTo("每页条数超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--limit", "abc")).isEqualTo("每页条数超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--page")).isEqualTo("缺少 --page 的值。\n");
        assertThat(auditErr(env, "audit", "list", "--limit")).isEqualTo("缺少 --limit 的值。\n");
        assertThat(auditErr(env, "audit", "list", "--conch")).isEqualTo("缺少 --conch 的值。\n");
        assertThat(auditErr(env, "audit", "list", "--account")).isEqualTo("缺少 --account 的值。\n");
        assertThat(auditErr(env, "audit", "list", "--page", "--limit", "1")).isEqualTo("缺少 --page 的值。\n");
        assertThat(auditErr(env, "audit", "list", "--page", "1", "--page", "2")).isEqualTo("--page 重复了。\n");
        assertThat(auditErr(env, "audit", "list", "--limit", "1", "--limit", "2")).isEqualTo("--limit 重复了。\n");
        assertThat(auditErr(env, "audit", "list", "--conch", "a", "--conch", "b")).isEqualTo("--conch 重复了。\n");
        assertThat(auditErr(env, "audit", "list", "--account", "a", "--account", "b")).isEqualTo("--account 重复了。\n");
        assertThat(auditErr(env, "audit", "list", "--limit", "201")).isEqualTo("每页条数超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--page", "2147483647")).isEqualTo("页码超出范围。\n");
        assertThat(auditErr(env, "audit", "list", "--conch", "oa/extra")).isEqualTo("规则包名称无效。\n");
        assertThat(auditErr(env, "audit", "list", "--account", "bad name"))
                .isEqualTo("账号名只能包含字母、数字、下划线和连字符，长度 1 到 64。\n");
        assertThat(auditErr(env, "audit", "nosuch")).startsWith("用法：\n").contains("crab audit list").doesNotContain("usage:");
        assertThat(auditErr(env, "audit")).startsWith("用法：\n").doesNotContain("usage:");
        assertThat(auditErr(env, "apikey", "create", "wide", "--conches")).isEqualTo("缺少 --conches 的值。\n");
    }

    private static String auditErr(Function<String, String> env, String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(args, env, InputStream.nullInputStream(), stdout(), stderr(err));
        assertThat(code).isEqualTo(1);
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void localImportReloadExportAndDelete() throws Exception {
        Path home = temp.resolve("local-home");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.0"));
        ByteArrayOutputStream imported = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"local", "import", file.toString()},
                env(home),
                InputStream.nullInputStream(),
                stdout(imported),
                stderr(err));
        assertThat(code).isZero();
        assertThat(imported.toString(StandardCharsets.UTF_8)).contains("local_ping 1.0.0 已导入");
        assertThat(run(env(home), InputStream.nullInputStream(), "account", "add", "local_ping", "alice")).isZero();
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(home));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT origin FROM accounts WHERE conch = ? AND account = ?")) {
            query.setString(1, "local_ping");
            query.setString(2, "alice");
            try (ResultSet resultSet = query.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("LOCAL");
            }
        }

        Path dir = home.resolve("local-conches");
        Files.writeString(dir.resolve("local_two.yaml"), dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_two", "1.0.0"));
        ByteArrayOutputStream reloaded = new ByteArrayOutputStream();
        assertThat(run(env(home), InputStream.nullInputStream(), reloaded, "local", "reload")).isZero();
        assertThat(reloaded.toString(StandardCharsets.UTF_8)).contains("local_two.yaml local_two 1.0.0 ok");

        Path out = home.resolve("out.yaml");
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        assertThat(run(env(home), InputStream.nullInputStream(), exported, "local", "export", "local_ping", "--out", out.toString()))
                .isZero();
        assertThat(Files.readAllBytes(out)).isEqualTo(Files.readAllBytes(dir.resolve("local_ping.yaml")));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        assertThat(run(env(home), InputStream.nullInputStream(), raw, "local", "export", "local_two")).isZero();
        assertThat(raw.toByteArray()).isEqualTo(Files.readAllBytes(dir.resolve("local_two.yaml")));

        ByteArrayOutputStream deleted = new ByteArrayOutputStream();
        assertThat(run(env(home), InputStream.nullInputStream(), deleted, "local", "delete", "local_two")).isZero();
        assertThat(deleted.toString(StandardCharsets.UTF_8)).contains("local_two deleted");
        assertThat(dir.resolve("local_two.yaml")).doesNotExist();
    }

    @Test
    void localImportAcceptsExplicitEmptyRelatedWithoutMissingRelated() throws Exception {
        Path home = temp.resolve("empty-related");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        byte[] yaml = Files.readAllBytes(explicitEmptyRelatedYaml());
        Files.write(file, yaml);
        ByteArrayOutputStream imported = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"local", "import", file.toString()},
                env(home),
                InputStream.nullInputStream(),
                stdout(imported),
                stderr(err));
        assertThat(code).isZero();
        assertThat(imported.toString(StandardCharsets.UTF_8)).contains("empty_related 1.0.0 已导入");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readAllBytes(home.resolve("local-conches").resolve("empty_related.yaml"))).isEqualTo(yaml);
    }

    @Test
    void localReplaceFlagAndBrokenPackageStayVisible() throws Exception {
        Path home = temp.resolve("local-replace");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.0"));
        Function<String, String> env = env(home);
        assertThat(run(env, InputStream.nullInputStream(), "local", "import", file.toString())).isZero();

        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.2.0"));
        ByteArrayOutputStream deniedErr = new ByteArrayOutputStream();
        ByteArrayOutputStream deniedOut = new ByteArrayOutputStream();
        int denied = new CrabCommand().run(
                new String[] {"local", "import", file.toString()},
                env,
                InputStream.nullInputStream(),
                stdout(deniedOut),
                stderr(deniedErr));
        assertThat(denied).isEqualTo(1);
        assertThat(deniedErr.toString(StandardCharsets.UTF_8)).isEqualTo("将覆盖已有的本地规则包 local_ping。确认后再导入。\n");
        assertThat(deniedOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(home.resolve("local-conches").resolve("local_ping.yaml"))).contains("version: 1.0.0");

        ByteArrayOutputStream replaced = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), replaced, "local", "import", "--replace", file.toString())).isZero();
        assertThat(replaced.toString(StandardCharsets.UTF_8)).contains("local_ping 1.2.0 已覆盖");
        assertThat(replaced.toString(StandardCharsets.UTF_8)).doesNotContain("replaced");

        Files.writeString(home.resolve("local-conches").resolve("local_ping.yaml"), ":\n");
        ByteArrayOutputStream reloaded = new ByteArrayOutputStream();
        ByteArrayOutputStream reloadErr = new ByteArrayOutputStream();
        int reload = new CrabCommand().run(
                new String[] {"local", "reload"},
                env,
                InputStream.nullInputStream(),
                stdout(reloaded),
                stderr(reloadErr));
        assertThat(reload).isZero();
        assertThat(reloaded.toString(StandardCharsets.UTF_8)).contains("local_ping.yaml local_ping - 未载入");
        String reason = reloadErr.toString(StandardCharsets.UTF_8);
        assertThat(reason).contains("文件 local_ping.yaml").contains("规则包 local_ping").contains("无法解析");
        assertThat(reason).doesNotContain("file name does not match conch").doesNotContain("conch validation failed");

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "local", "list")).isZero();
        String list = listed.toString(StandardCharsets.UTF_8);
        assertThat(list).contains("local_ping - local_ping.yaml 未载入");
        assertThat(list).contains("文件 local_ping.yaml").contains("规则包 local_ping").contains("无法解析");

        ByteArrayOutputStream removed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), removed, "local", "delete", "local_ping")).isZero();
        assertThat(removed.toString(StandardCharsets.UTF_8)).contains("local_ping deleted");
        assertThat(home.resolve("local-conches").resolve("local_ping.yaml")).doesNotExist();

        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.2"));
        ByteArrayOutputStream imported = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), imported, "local", "import", file.toString())).isZero();
        assertThat(imported.toString(StandardCharsets.UTF_8)).contains("local_ping 1.0.2 已导入");
        ByteArrayOutputStream after = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), after, "local", "list")).isZero();
        assertThat(after.toString(StandardCharsets.UTF_8)).contains("local_ping 1.0.2 local_ping.yaml");
        assertThat(after.toString(StandardCharsets.UTF_8)).doesNotContain("未载入");
    }

    @Test
    void localHistoryAndRestore() throws Exception {
        Path home = temp.resolve("local-history");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.0"));
        Function<String, String> env = env(home);
        assertThat(run(env, InputStream.nullInputStream(), "local", "import", file.toString())).isZero();

        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), empty, "local", "history", "local_ping")).isZero();
        assertThat(empty.toString(StandardCharsets.UTF_8)).isEmpty();

        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.2.0"));
        assertThat(run(env, InputStream.nullInputStream(), "local", "import", "--replace", file.toString())).isZero();

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "local", "history", "local_ping")).isZero();
        String line = listed.toString(StandardCharsets.UTF_8).trim();
        assertThat(line).endsWith(" 1.0.0");
        String id = line.substring(0, line.lastIndexOf(' '));
        assertThat(id).doesNotContain(".yaml");

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), restored, "local", "restore", "local_ping", id)).isZero();
        assertThat(restored.toString(StandardCharsets.UTF_8)).isEqualTo("local_ping 1.0.0 已回退\n");
        assertThat(Files.readString(home.resolve("local-conches").resolve("local_ping.yaml"))).contains("version: 1.0.0");

        ByteArrayOutputStream missingErr = new ByteArrayOutputStream();
        int missing = new CrabCommand().run(
                new String[] {"local", "restore", "local_ping", "missing"},
                env,
                InputStream.nullInputStream(),
                stdout(new ByteArrayOutputStream()),
                stderr(missingErr));
        assertThat(missing).isEqualTo(1);
        assertThat(missingErr.toString(StandardCharsets.UTF_8)).isEqualTo("没有这份历史。\n");
    }

    @Test
    void localImportPrintsChineseReasonsAndRejectsBadFiles() throws Exception {
        Path home = temp.resolve("local-bad");
        Files.createDirectories(home);
        Function<String, String> env = env(home);

        Path driver = home.resolve("bad-driver.yaml");
        Files.writeString(driver, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("bad_driver", "1.0.0")
                .replace("driver: http", "driver: shell"));
        ByteArrayOutputStream driverErr = new ByteArrayOutputStream();
        ByteArrayOutputStream driverOut = new ByteArrayOutputStream();
        int driverCode = new CrabCommand().run(
                new String[] {"local", "import", driver.toString()},
                env,
                InputStream.nullInputStream(),
                stdout(driverOut),
                stderr(driverErr));
        String driverText = driverErr.toString(StandardCharsets.UTF_8);
        assertThat(driverCode).isEqualTo(1);
        assertThat(driverOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(driverText).contains("规则包校验失败").contains("DRIVER").contains("driver 只允许 http 或 mcp").contains("第 3 行");
        assertThat(driverText).doesNotContain("shell").doesNotContain("com.fasterxml");
        assertThat(home.resolve("local-conches").resolve("bad_driver.yaml")).doesNotExist();

        Path high = home.resolve("high-version.yaml");
        Files.writeString(high, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("high_crab", "1.0.0")
                .replace("min_crab_version: 1.0.0", "min_crab_version: 9.0.0"));
        ByteArrayOutputStream highErr = new ByteArrayOutputStream();
        int highCode = new CrabCommand().run(
                new String[] {"local", "import", high.toString()},
                env,
                InputStream.nullInputStream(),
                stdout(new ByteArrayOutputStream()),
                stderr(highErr));
        String highText = highErr.toString(StandardCharsets.UTF_8);
        assertThat(highCode).isEqualTo(1);
        assertThat(highText).contains("MIN_CRAB_VERSION").contains("高于当前 Crab").contains("第 4 行");
        assertThat(home.resolve("local-conches").resolve("high_crab.yaml")).doesNotExist();

        Path broken = home.resolve("broken.yaml");
        Files.writeString(broken, "conch: \"unterminated\nversion: 1.0.0\n");
        ByteArrayOutputStream parseErr = new ByteArrayOutputStream();
        ByteArrayOutputStream parseOut = new ByteArrayOutputStream();
        int parseCode = new CrabCommand().run(
                new String[] {"local", "import", broken.toString()},
                env,
                InputStream.nullInputStream(),
                stdout(parseOut),
                stderr(parseErr));
        String parseText = parseErr.toString(StandardCharsets.UTF_8);
        assertThat(parseCode).isEqualTo(1);
        assertThat(parseOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(parseText).contains("规则包校验失败").contains("PARSE").contains("YAML 无法解析").contains("行");
        assertThat(parseText).doesNotContain("unterminated").doesNotContain("com.fasterxml");
        try (var files = Files.list(home.resolve("local-conches"))) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList()).isEmpty();
        }
    }

    @Test
    void localImportPrintsChineseWarningsAndKeepsFailureChinese() throws Exception {
        Path home = temp.resolve("warn-import");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        String yaml = dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.0");
        Files.writeString(file, yaml);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"local", "import", file.toString()},
                env(home),
                InputStream.nullInputStream(),
                stdout(out),
                stderr(err));
        String warnings = err.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("local_ping 1.0.0 已导入\n");
        assertThat(warnings).isEqualTo(
                "VAGUE_DESCRIPTION description description 不足 6 个字；其它 AI 靠它判断该不该用，请写清能做什么、用户平时怎么称呼\n"
                        + "VAGUE_DESCRIPTION actions.ping.description description 不足 6 个字；其它 AI 靠它判断该不该用，请写清能做什么、用户平时怎么称呼\n"
                        + "MISSING_RELATED actions.ping.related 缺少 related\n");
        assertThat(warnings).doesNotContain("本地探测").doesNotContain("base_url").doesNotContain("com.fasterxml");
        assertThat(Files.readString(home.resolve("local-conches").resolve("local_ping.yaml"))).isEqualTo(yaml);

        Path missing = home.resolve("missing.yaml");
        Files.writeString(missing, yaml.replace("min_crab_version: 1.0.0\n", "").replace("conch: local_ping", "conch: local_miss"));
        ByteArrayOutputStream failOut = new ByteArrayOutputStream();
        ByteArrayOutputStream failErr = new ByteArrayOutputStream();
        int failed = new CrabCommand().run(
                new String[] {"local", "import", missing.toString()},
                env(home),
                InputStream.nullInputStream(),
                stdout(failOut),
                stderr(failErr));
        String failure = failErr.toString(StandardCharsets.UTF_8);
        assertThat(failed).isEqualTo(1);
        assertThat(failOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(failure).isEqualTo("规则包校验失败\nPARSE min_crab_version min_crab_version: 缺少 min_crab_version\n");
        assertThat(failure).doesNotContain("本地探测").doesNotContain("com.fasterxml").doesNotContain("unterminated");
        assertThat(home.resolve("local-conches").resolve("local_miss.yaml")).doesNotExist();
    }

    @Test
    void localImportPrintsRequestEncodingWarningsAndRejectsQueryStyle() throws Exception {
        Path home = temp.resolve("encode-warn");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        Files.writeString(file, encodingPackage("encode_warn", "payload: nope"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"local", "import", file.toString()},
                env(home),
                InputStream.nullInputStream(),
                stdout(out),
                stderr(err));
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("encode_warn 1.0.0 已导入");
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("IGNORED_REQUEST_FIELD actions.ping.steps[0].request.payload 这个字段不会被发送");

        Path bad = home.resolve("bad.yaml");
        Files.writeString(bad, encodingPackage("encode_bad", "query_style: nope"));
        ByteArrayOutputStream badOut = new ByteArrayOutputStream();
        ByteArrayOutputStream badErr = new ByteArrayOutputStream();
        int failed = new CrabCommand().run(
                new String[] {"local", "import", bad.toString()},
                env(home),
                InputStream.nullInputStream(),
                stdout(badOut),
                stderr(badErr));
        assertThat(failed).isEqualTo(1);
        assertThat(badOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(badErr.toString(StandardCharsets.UTF_8))
                .contains("规则包校验失败")
                .contains("QUERY_STYLE actions.ping.steps[0].request.query_style query_style 只允许 url 或 form");
        assertThat(home.resolve("local-conches").resolve("encode_bad.yaml")).doesNotExist();
    }

    private static String encodingPackage(String name, String extra) {
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  ping:
                    title: 查询
                    description: 说明
                    steps:
                      - request:
                          method: GET
                          path: /p
                          %s
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: yes}
                    notes: 注意
                    related: []
                """.formatted(name, extra);
    }

    @Test
    void localImportRejectsRemember() throws Exception {
        Path home = temp.resolve("remember-cli");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.0"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"local", "import", file.toString(), "--remember"},
                env(home),
                InputStream.nullInputStream(),
                stdout(out),
                stderr(err));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("不认识的选项 --remember。\n");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(new CrabPaths(home).localConches().resolve("local_ping.yaml")).doesNotExist();
    }

    @Test
    void apiKeyCreateRejectsUnknownOptions() {
        Path root = temp.resolve("unknown-opt");
        Function<String, String> env = env(root);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"apikey", "create", "wide", "--scope", "legacy_oa"},
                env,
                InputStream.nullInputStream(),
                stdout(out),
                stderr(err));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("不认识的选项 --scope。\n");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("id ");

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "apikey", "list")).isZero();
        assertThat(listed.toString(StandardCharsets.UTF_8)).doesNotContain("wide");
    }

    @Test
    void apiKeyCreateRequiresAllOrANamedScope() throws Exception {
        Path root = temp.resolve("explicit-scope");
        installLegacy(root);
        Function<String, String> env = env(root);

        ByteArrayOutputStream emptyErr = new ByteArrayOutputStream();
        ByteArrayOutputStream emptyOut = new ByteArrayOutputStream();
        int empty = new CrabCommand().run(
                new String[] {"apikey", "create", "open"},
                env,
                InputStream.nullInputStream(),
                stdout(emptyOut),
                stderr(emptyErr));
        assertThat(empty).isEqualTo(1);
        assertThat(emptyErr.toString(StandardCharsets.UTF_8)).contains(ApiKeyScope.MISSING_SCOPE);
        assertThat(emptyOut.toString(StandardCharsets.UTF_8)).doesNotContain("id ");

        ByteArrayOutputStream mixedErr = new ByteArrayOutputStream();
        int mixed = new CrabCommand().run(
                new String[] {"apikey", "create", "mixed", "--all", "--conches", "legacy_oa"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(mixedErr));
        assertThat(mixed).isEqualTo(1);
        assertThat(mixedErr.toString(StandardCharsets.UTF_8)).contains(ApiKeyScope.ALL_EXCLUDES_NAMES);

        ByteArrayOutputStream created = new ByteArrayOutputStream();
        int ok = new CrabCommand().run(
                new String[] {"apikey", "create", "wide", "--all", "--read-only"},
                env,
                InputStream.nullInputStream(),
                stdout(created),
                stderr(new ByteArrayOutputStream()));
        assertThat(ok).isZero();
        assertThat(created.toString(StandardCharsets.UTF_8)).contains("id ");
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT scope_json FROM api_keys WHERE name = ?")) {
            statement.setString(1, "wide");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).contains("\"all\":true").contains("\"readOnlyOnly\":true");
            }
        }

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "apikey", "list")).isZero();
        String keys = listed.toString(StandardCharsets.UTF_8);
        assertThat(keys).contains("wide").contains("all=true").contains("read-only=true")
                .doesNotContain("conches=").doesNotContain("actions=")
                .doesNotContain("open").doesNotContain("mixed");
    }

    @Test
    void fromEnvSetsACredentialAndDoesNotCreateAnApiKey() throws Exception {
        Path root = temp.resolve("from-env");
        installLegacy(root);
        assertThat(run(env(root), InputStream.nullInputStream(), "account", "add", "legacy_oa", "alice")).isZero();
        Function<String, String> env = name -> switch (name) {
            case "HERMIT_HOME" -> root.toString();
            case "OA_PASSWORD" -> PASSWORD;
            default -> null;
        };
        ByteArrayOutputStream credentialErr = new ByteArrayOutputStream();
        int wrote = new CrabCommand().run(
                new String[] {"credential", "set", "legacy_oa", "alice", "password", "--from-env", "OA_PASSWORD"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(credentialErr));
        assertThat(wrote).as(credentialErr.toString(StandardCharsets.UTF_8)).isZero();
        SecretChecks.absent(credentialErr.toString(StandardCharsets.UTF_8), PASSWORD);

        ByteArrayOutputStream keyErr = new ByteArrayOutputStream();
        ByteArrayOutputStream keyOut = new ByteArrayOutputStream();
        int created = new CrabCommand().run(
                new String[] {"apikey", "create", "from-env", "--from-env", "OA_PASSWORD"},
                env,
                InputStream.nullInputStream(),
                stdout(keyOut),
                stderr(keyErr));
        assertThat(created).isEqualTo(1);
        assertThat(keyOut.toString(StandardCharsets.UTF_8)).doesNotContain("id ");
        assertThat(keyErr.toString(StandardCharsets.UTF_8)).contains("用法：");
        SecretChecks.absent(keyErr.toString(StandardCharsets.UTF_8), PASSWORD);
        SecretChecks.absent(keyOut.toString(StandardCharsets.UTF_8), PASSWORD);

        int withAll = new CrabCommand().run(
                new String[] {"apikey", "create", "from-env-all", "--all", "--from-env", "OA_PASSWORD"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(new ByteArrayOutputStream()));
        assertThat(withAll).isEqualTo(1);

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "apikey", "list")).isZero();
        String keys = listed.toString(StandardCharsets.UTF_8);
        assertThat(keys).doesNotContain("from-env");
        SecretChecks.absent(keys, PASSWORD);
    }

    @Test
    void helpGoesToStdoutAndUnknownCommandExits() {
        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
        int help = new CrabCommand().run(
                new String[] {"--help"},
                env(temp),
                InputStream.nullInputStream(),
                stdout(helpOut),
                stderr(helpErr));
        assertThat(help).isZero();
        assertThat(helpOut.toString(StandardCharsets.UTF_8)).startsWith("用法：\n").contains("crab apikey list").contains("crab --help").doesNotContain("usage:");
        assertThat(helpErr.toString(StandardCharsets.UTF_8)).isEmpty();

        ByteArrayOutputStream shortOut = new ByteArrayOutputStream();
        ByteArrayOutputStream shortErr = new ByteArrayOutputStream();
        int shorthand = new CrabCommand().run(
                new String[] {"-h"},
                env(temp),
                InputStream.nullInputStream(),
                stdout(shortOut),
                stderr(shortErr));
        assertThat(shorthand).isZero();
        assertThat(shortOut.toString(StandardCharsets.UTF_8)).startsWith("用法：\n");
        assertThat(shortErr.toString(StandardCharsets.UTF_8)).isEmpty();

        ByteArrayOutputStream bareOut = new ByteArrayOutputStream();
        ByteArrayOutputStream bareErr = new ByteArrayOutputStream();
        int bare = new CrabCommand().run(
                new String[0],
                env(temp),
                InputStream.nullInputStream(),
                stdout(bareOut),
                stderr(bareErr));
        assertThat(bare).isZero();
        assertThat(bareOut.toString(StandardCharsets.UTF_8)).isEqualTo(helpOut.toString(StandardCharsets.UTF_8));
        assertThat(bareErr.toString(StandardCharsets.UTF_8)).isEmpty();

        ByteArrayOutputStream unknownOut = new ByteArrayOutputStream();
        ByteArrayOutputStream unknownErr = new ByteArrayOutputStream();
        int unknown = new CrabCommand().run(
                new String[] {"nosuch"},
                env(temp),
                InputStream.nullInputStream(),
                stdout(unknownOut),
                stderr(unknownErr));
        assertThat(unknown).isEqualTo(1);
        assertThat(unknownOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(unknownErr.toString(StandardCharsets.UTF_8)).isEqualTo(helpOut.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream extraOut = new ByteArrayOutputStream();
        ByteArrayOutputStream extraErr = new ByteArrayOutputStream();
        int extra = new CrabCommand().run(
                new String[] {"--help", "serve"},
                env(temp),
                InputStream.nullInputStream(),
                stdout(extraOut),
                stderr(extraErr));
        assertThat(extra).isEqualTo(1);
        assertThat(extraOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(extraErr.toString(StandardCharsets.UTF_8)).startsWith("用法：\n");
        assertThat(temp.resolve("data")).doesNotExist();
    }

    @Test
    void configGetRejectsUnknownConchAndPrintsStoredValues() throws Exception {
        Path root = temp.resolve("config");
        installLegacy(root);
        Function<String, String> env = env(root);

        ByteArrayOutputStream missingOut = new ByteArrayOutputStream();
        ByteArrayOutputStream missingErr = new ByteArrayOutputStream();
        int missing = new CrabCommand().run(
                new String[] {"config", "get", "no_such_pack"},
                env,
                InputStream.nullInputStream(),
                stdout(missingOut),
                stderr(missingErr));
        assertThat(missing).isEqualTo(1);
        assertThat(missingOut.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(missingErr.toString(StandardCharsets.UTF_8)).isEqualTo("本机没有这个规则包: no_such_pack" + System.lineSeparator());

        ByteArrayOutputStream missingKeyErr = new ByteArrayOutputStream();
        int missingKey = new CrabCommand().run(
                new String[] {"config", "get", "no_such_pack", "base_url"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(missingKeyErr));
        assertThat(missingKey).isEqualTo(1);
        assertThat(missingKeyErr.toString(StandardCharsets.UTF_8)).contains("本机没有这个规则包").doesNotContain("config key is not set");

        ByteArrayOutputStream oddErr = new ByteArrayOutputStream();
        int odd = new CrabCommand().run(
                new String[] {"config", "get", "Not A Name\nsecret"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(oddErr));
        assertThat(odd).isEqualTo(1);
        assertThat(oddErr.toString(StandardCharsets.UTF_8)).isEqualTo("本机没有这个规则包" + System.lineSeparator());

        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), empty, "config", "get", "legacy_oa")).isZero();
        assertThat(empty.toString(StandardCharsets.UTF_8)).isEmpty();

        String configValue = "http://oa.example/cli-config-value";
        assertThat(run(env, InputStream.nullInputStream(), "config", "set", "legacy_oa", "base_url", configValue)).isZero();
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), listed, "config", "get", "legacy_oa")).isZero();
        assertThat(listed.toString(StandardCharsets.UTF_8)).isEqualTo("base_url=" + configValue + System.lineSeparator());
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), one, "config", "get", "legacy_oa", "base_url")).isZero();
        assertThat(one.toString(StandardCharsets.UTF_8)).isEqualTo(configValue + System.lineSeparator());

        ByteArrayOutputStream unsetErr = new ByteArrayOutputStream();
        int unset = new CrabCommand().run(
                new String[] {"config", "get", "legacy_oa", "missing_key"},
                env,
                InputStream.nullInputStream(),
                stdout(),
                stderr(unsetErr));
        assertThat(unset).isEqualTo(1);
        assertThat(unsetErr.toString(StandardCharsets.UTF_8)).contains("config key is not set");

        Path home = temp.resolve("local-config");
        Files.createDirectories(home);
        Path file = home.resolve("in.yaml");
        Files.writeString(file, dev.hermit.crab.sync.LocalConchBehaviorTest.ping("local_ping", "1.0.0"));
        assertThat(run(env(home), InputStream.nullInputStream(), "local", "import", file.toString())).isZero();
        ByteArrayOutputStream localOut = new ByteArrayOutputStream();
        ByteArrayOutputStream localErr = new ByteArrayOutputStream();
        int local = new CrabCommand().run(
                new String[] {"config", "get", "local_ping"},
                env(home),
                InputStream.nullInputStream(),
                stdout(localOut),
                stderr(localErr));
        assertThat(local).as(localErr.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(localOut.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void apiKeyListPrintsScopeAndReadOnlyWithoutTheSecret() throws Exception {
        Path root = temp.resolve("key-list");
        installLegacy(root);
        Function<String, String> env = env(root);
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO disabled_conches (conch, version, disabled_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            statement.setString(1, "other_oa");
            statement.setString(2, "1.0.0");
            statement.executeUpdate();
        }

        ByteArrayOutputStream created = new ByteArrayOutputStream();
        ByteArrayOutputStream createErr = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {
                    "apikey", "create", "scoped",
                    "--read-only",
                    "--conches", "other_oa,legacy_oa",
                    "--actions", "legacy_oa.list_todo"
                },
                env,
                InputStream.nullInputStream(),
                stdout(created),
                stderr(createErr));
        assertThat(code).as(createErr.toString(StandardCharsets.UTF_8)).isZero();
        String id = null;
        String prefix = null;
        String secret = null;
        for (String line : created.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("id ")) {
                id = line.substring(3);
            } else if (line.startsWith("prefix ")) {
                prefix = line.substring("prefix ".length());
            } else if (!line.isEmpty()) {
                secret = line;
            }
        }
        assertThat(id).isNotBlank();
        assertThat(prefix).hasSize(8);
        assertThat(secret).startsWith(prefix).hasSizeGreaterThan(prefix.length());

        ByteArrayOutputStream openOut = new ByteArrayOutputStream();
        int open = new CrabCommand().run(
                new String[] {"apikey", "create", "open", "--all"},
                env,
                InputStream.nullInputStream(),
                stdout(openOut),
                stderr(new ByteArrayOutputStream()));
        assertThat(open).isZero();
        String openId = null;
        for (String line : openOut.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("id ")) {
                openId = line.substring(3);
            }
        }
        assertThat(run(env, InputStream.nullInputStream(), "apikey", "revoke", openId)).isZero();

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        ByteArrayOutputStream listErr = new ByteArrayOutputStream();
        int listedCode = new CrabCommand().run(
                new String[] {"apikey", "list"},
                env,
                InputStream.nullInputStream(),
                stdout(listed),
                stderr(listErr));
        assertThat(listedCode).isZero();
        assertThat(listErr.toString(StandardCharsets.UTF_8)).isEmpty();
        String text = listed.toString(StandardCharsets.UTF_8);
        String scoped = lineContaining(text, " scoped ");
        String revoked = lineContaining(text, " open ");
        assertThat(scoped).contains("all=false")
                .contains("conches=legacy_oa,other_oa")
                .contains("actions=legacy_oa.list_todo")
                .contains("read-only=true")
                .doesNotContain("revoked");
        assertThat(revoked).contains("all=true")
                .contains("read-only=false")
                .doesNotContain("conches=")
                .doesNotContain("actions=")
                .endsWith("revoked");
        assertThat(text).contains(prefix);
        SecretChecks.absent(text, secret);
        SecretChecks.absent(listErr.toString(StandardCharsets.UTF_8), secret);

        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE api_keys SET scope_json = ? WHERE id = ?")) {
            statement.setString(1, "{\"conches\":[],\"actions\":[],\"readOnlyOnly\":false}");
            statement.setString(2, openId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        ByteArrayOutputStream legacyListed = new ByteArrayOutputStream();
        assertThat(run(env, InputStream.nullInputStream(), legacyListed, "apikey", "list")).isZero();
        String legacy = lineContaining(legacyListed.toString(StandardCharsets.UTF_8), " open ");
        assertThat(legacy).contains("all=true")
                .contains("read-only=false")
                .doesNotContain("conches=")
                .doesNotContain("actions=")
                .endsWith("revoked");
        SecretChecks.absent(legacyListed.toString(StandardCharsets.UTF_8), secret);
    }

    @Test
    void consolePasswordGatesDebugAndNotExistingCommands() throws Exception {
        Path root = temp.resolve("console-password");
        Function<String, String> home = env(root);
        String password = "cli-console-secret";
        ByteArrayOutputStream argumentErr = new ByteArrayOutputStream();
        int rejected = new CrabCommand().run(
                new String[] {"console", "password", password},
                home,
                InputStream.nullInputStream(),
                stdout(),
                stderr(argumentErr));
        assertThat(rejected).isEqualTo(1);
        assertThat(argumentErr.toString(StandardCharsets.UTF_8)).contains("口令不能写在命令行参数里。");
        SecretChecks.absent(argumentErr.toString(StandardCharsets.UTF_8), password);

        ByteArrayInputStream in = new ByteArrayInputStream((password + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream setErr = new ByteArrayOutputStream();
        int set = new CrabCommand().run(
                new String[] {"console", "password"},
                home,
                in,
                stdout(out),
                stderr(setErr));
        assertThat(set).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("updated");
        SecretChecks.absent(out.toString(StandardCharsets.UTF_8), password);
        SecretChecks.absent(setErr.toString(StandardCharsets.UTF_8), password);

        assertThat(run(home, InputStream.nullInputStream(), "apikey", "list")).isZero();

        ByteArrayOutputStream missingErr = new ByteArrayOutputStream();
        int missing = new CrabCommand().run(
                new String[] {"debug", "on"},
                home,
                InputStream.nullInputStream(),
                stdout(),
                stderr(missingErr));
        assertThat(missing).isEqualTo(1);
        assertThat(missingErr.toString(StandardCharsets.UTF_8)).contains("请提供口令。");

        ByteArrayOutputStream wrongErr = new ByteArrayOutputStream();
        int wrong = new CrabCommand().run(
                new String[] {"debug", "on", "--from-env", "DEBUG_PW"},
                name -> switch (name) {
                    case "HERMIT_HOME" -> root.toString();
                    case "DEBUG_PW" -> "wrong-console-pw";
                    default -> null;
                },
                InputStream.nullInputStream(),
                stdout(),
                stderr(wrongErr));
        assertThat(wrong).isEqualTo(1);
        assertThat(wrongErr.toString(StandardCharsets.UTF_8)).contains("口令错误");
        SecretChecks.absent(wrongErr.toString(StandardCharsets.UTF_8), password);

        ByteArrayOutputStream enabled = new ByteArrayOutputStream();
        int on = new CrabCommand().run(
                new String[] {"debug", "on", "--from-env", "DEBUG_PW"},
                name -> switch (name) {
                    case "HERMIT_HOME" -> root.toString();
                    case "DEBUG_PW" -> password;
                    default -> null;
                },
                InputStream.nullInputStream(),
                stdout(enabled),
                stderr(new ByteArrayOutputStream()));
        assertThat(on).isZero();
        assertThat(enabled.toString(StandardCharsets.UTF_8)).contains("enabled=true");
        SecretChecks.absent(enabled.toString(StandardCharsets.UTF_8), password);

        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                SecretChecks.absent(
                        new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1), password);
            }
        }

        Path open = temp.resolve("debug-open");
        ByteArrayOutputStream status = new ByteArrayOutputStream();
        assertThat(run(env(open), InputStream.nullInputStream(), status, "debug", "status")).isZero();
        assertThat(status.toString(StandardCharsets.UTF_8)).contains("enabled=false").contains("expiresAt=-");

        ByteArrayOutputStream help = new ByteArrayOutputStream();
        assertThat(run(env(temp.resolve("help-console")), InputStream.nullInputStream(), help, "--help")).isZero();
        assertThat(help.toString(StandardCharsets.UTF_8)).contains("crab console password").contains("crab debug status|on|off");
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

    @Test
    void clientMessageKeepsTheFirstLineAndDropsStacks() {
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException("字".repeat(201))))
                .isEqualTo("字".repeat(200) + "…");
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException("字".repeat(200))))
                .isEqualTo("字".repeat(200));
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException("第一行\n第二行：字段 page")))
                .isEqualTo("第一行");
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException("第一行\r\n第二行：字段 page")))
                .isEqualTo("第一行");
        String stack = """
                java.lang.IllegalArgumentException: bad
                \tat dev.hermit.crab.cli.CrabCommand.clientMessage(CrabCommand.java:1)
                \tat dev.hermit.crab.cli.CliTest.clientMessageKeepsTheFirstLineAndDropsStacks(CliTest.java:1)""";
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException(stack))).isEqualTo("输入无效。");
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException(
                "\tat dev.hermit.Foo.bar(Foo.java:1)\n\tat dev.hermit.Foo.main(Foo.java:2)")))
                .isEqualTo("输入无效。");
        assertThat(CrabCommand.clientMessage(new IllegalArgumentException((String) null))).isEqualTo("输入无效。");
    }

    private static String lineContaining(String text, String token) {
        return text.lines().filter(line -> line.contains(token)).findFirst().orElse("");
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
        byte[] zip = ConchPackage.write(yaml, keys.getPrivate());
        Path version = root.resolve("conches").resolve("legacy_oa").resolve("1.0.0");
        Files.createDirectories(version);
        Files.write(version.resolve("package.zip"), zip);
        Files.write(version.resolve("conch.yaml"), yaml);
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
