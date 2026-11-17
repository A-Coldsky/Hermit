package dev.hermit.crab.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrabDatabaseUrlTest {
    private static final String OPTIONS =
            ";LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0;FILE_LOCK=FILE";

    @TempDir
    Path temp;

    @Test
    void windowsDriveKeepsTheLetterAndDropsBackslashes() {
        String url = CrabDatabase.jdbcUrl("C:\\Users\\Public\\.hermit\\data\\crab");
        assertThat(url).isEqualTo("jdbc:h2:file:C:/Users/Public/.hermit/data/crab" + OPTIONS);
        assertThat(url).doesNotContain("file:/C:");
    }

    @Test
    void windowsDriveWrittenWithSlashesIsUnchanged() {
        assertThat(CrabDatabase.jdbcUrl("C:/Users/Public/.hermit/data/crab"))
                .isEqualTo("jdbc:h2:file:C:/Users/Public/.hermit/data/crab" + OPTIONS);
    }

    @Test
    void unixAbsolutePathStaysAbsolute() {
        assertThat(CrabDatabase.jdbcUrl("/var/lib/hermit/data/crab"))
                .isEqualTo("jdbc:h2:file:/var/lib/hermit/data/crab" + OPTIONS);
    }

    @Test
    void uncPathKeepsTheDoubleSlash() {
        assertThat(CrabDatabase.jdbcUrl("\\\\fileserver\\profiles\\public\\.hermit\\data\\crab"))
                .isEqualTo("jdbc:h2:file://fileserver/profiles/public/.hermit/data/crab" + OPTIONS);
    }

    @Test
    void semicolonInThePathIsRejectedBeforeH2() {
        assertThatThrownBy(() -> CrabDatabase.jdbcUrl("C:\\Users\\foo;bar\\.hermit\\data\\crab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("数据目录路径含有分号，无法打开本地数据库。")
                .hasNoCause();
        assertThatThrownBy(() -> CrabDatabase.jdbcUrl("/var/lib/hermit;x/data/crab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("数据目录路径含有分号，无法打开本地数据库。");
    }

    @Test
    void openingADirectoryWhosePathContainsASemicolonDoesNotCreateTheDatabase() {
        Path root = temp.resolve("foo;bar");
        assertThatThrownBy(() -> new CrabDatabase(new CrabPaths(root)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("数据目录路径含有分号，无法打开本地数据库。")
                .hasNoCause();
        assertThat(root.resolve("data").resolve("crab.mv.db")).doesNotExist();
    }

    @Test
    void openFailureDetailIsThePathAndNotAnIntermediateSecret() {
        String password = "credential-value-not-for-stderr";
        InvalidPathException path = new InvalidPathException(
                "/C:/Users/Public/.hermit/data/crab.mv.db", "Illegal char <:>", 2);
        SQLException sql = new SQLException("insert into credentials values ('" + password + "')", path);
        IllegalStateException wrapped = new IllegalStateException("database operation failed", sql);

        String detail = DatabaseOpenException.detail(wrapped);

        assertThat(detail).contains("/C:/Users/Public/.hermit/data/crab.mv.db");
        assertThat(detail).contains("Illegal char <:>");
        assertThat(detail).doesNotContain(password);
        assertThat(detail).doesNotContain("\n");
        assertThat(detail).doesNotContain("database operation failed");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void opensTheFileDatabaseOnAWindowsDrive() throws Exception {
        CrabPaths paths = new CrabPaths(temp);
        try (CrabDatabase database = new CrabDatabase(paths);
             Connection connection = database.dataSource().getConnection()) {
            String url = connection.getMetaData().getURL();
            assertThat(url).startsWith("jdbc:h2:file:");
            assertThat(url).doesNotContain("file:/C:");
        }
        assertThat(Files.isRegularFile(paths.databaseFile().resolveSibling("crab.mv.db"))).isTrue();
    }
}
