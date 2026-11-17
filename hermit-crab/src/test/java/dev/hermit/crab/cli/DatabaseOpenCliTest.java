package dev.hermit.crab.cli;

import dev.hermit.crab.store.DatabaseOpenException;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.function.Function;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseOpenCliTest {
    @TempDir
    Path temp;

    @Test
    void startupPrintsThePathCauseAndNotAnIntermediateSecret() {
        String password = "credential-value-not-for-stderr";
        InvalidPathException path = new InvalidPathException(
                "/C:/Users/Public/.hermit/data/crab.mv.db", "Illegal char <:>", 2);
        SQLException sql = new SQLException("insert into credentials values ('" + password + "')", path);
        DatabaseOpenException failure = new DatabaseOpenException(
                new IllegalStateException("database operation failed", sql));
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        CrabCommand.printDatabaseOpenFailure(failure, stderr(err));

        String text = err.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("database operation failed\n");
        assertThat(text).contains("/C:/Users/Public/.hermit/data/crab.mv.db");
        assertThat(text).contains("Illegal char <:>");
        assertThat(text).doesNotContain("Exception");
        SecretChecks.absent(text, password);
    }

    @Test
    void semicolonInTheHomePrintsTheChineseReason() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"account", "list", "oa"},
                env(temp.resolve("foo;bar")),
                InputStream.nullInputStream(),
                stdout(),
                stderr(err));
        String text = err.toString(StandardCharsets.UTF_8);
        assertThat(code).isEqualTo(1);
        assertThat(text).contains("数据目录路径含有分号，无法打开本地数据库。");
        assertThat(text).doesNotContain("database operation failed");
        assertThat(text).doesNotContain("输入无效");
        assertThat(temp.resolve("foo;bar").resolve("data").resolve("crab.mv.db")).doesNotExist();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void unwritableDataDirectoryPrintsThePathOnStderr() throws Exception {
        Path root = temp.resolve("unwritable");
        Path data = root.resolve("data");
        Files.createDirectories(data);
        Files.setPosixFilePermissions(data, EnumSet.of(OWNER_READ, OWNER_EXECUTE));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            int code = new CrabCommand().run(
                    new String[] {"account", "list", "oa"},
                    env(root),
                    InputStream.nullInputStream(),
                    stdout(),
                    stderr(err));
            String text = err.toString(StandardCharsets.UTF_8);
            assertThat(code).isEqualTo(1);
            assertThat(text).contains("database operation failed");
            assertThat(text).contains(data.toAbsolutePath().toString());
            assertThat(text.lines().count()).isGreaterThan(1);
        } finally {
            Files.setPosixFilePermissions(data, EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        }
    }

    private static Function<String, String> env(Path root) {
        return name -> "HERMIT_HOME".equals(name) ? root.toString() : null;
    }

    private static PrintStream stdout() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static PrintStream stderr(ByteArrayOutputStream err) {
        return new PrintStream(err, true, StandardCharsets.UTF_8);
    }
}
