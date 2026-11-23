package dev.hermit.crab.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crab 已经投产：已发布的升级步骤一律不改，结构变化只新增版本。
 *
 * <p>从冻结的已发布结构（{@code schema/crab-v15.sql}）开库升级，结果必须和全新安装的表、列、约束、索引一致。
 * 就地改 applyV1–applyV15 只会改到全新安装，已有客户端拿不到，两边就对不上。新增版本时，在
 * {@link #aReleasedDatabaseKeepsItsRowsAndMatchesAFreshInstall()} 里补上它对已有数据的效果。
 */
class CrabSchemaHistoryTest {
    @TempDir
    Path temp;

    @Test
    void aReleasedDatabaseKeepsItsRowsAndMatchesAFreshInstall() throws Exception {
        CrabPaths released = new CrabPaths(temp.resolve("released"));
        Path script = temp.resolve("crab-v15.sql");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("schema/crab-v15.sql")) {
            assertThat(in).as("缺少已发布的 Crab 结构快照").isNotNull();
            Files.copy(in, script);
        }
        Files.createDirectories(released.databaseFile().getParent());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:file:" + released.databaseFile().toAbsolutePath(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("RUNSCRIPT FROM '" + script.toAbsolutePath() + "' CHARSET 'UTF-8'");
            statement.executeUpdate("INSERT INTO accounts (origin, conch, account, is_default, created_at) "
                    + "VALUES ('official', 'oa', 'alice', TRUE, CURRENT_TIMESTAMP)");
        }

        List<String> upgraded;
        try (CrabDatabase database = new CrabDatabase(released)) {
            upgraded = shape(database);
            try (Connection connection = database.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT account FROM accounts WHERE conch = 'oa'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("alice");
                }
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM session_login")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM tls_trusted_hosts")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
                // 21：身份的三张表都是空的，升级前的账号没有绑定，仍各用各的会话。
                for (String table : List.of("identities", "identity_members", "identity_sessions")) {
                    try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).as(table).isZero();
                    }
                }
            }
        }
        List<String> fresh;
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp.resolve("fresh")))) {
            fresh = shape(database);
        }

        assertThat(upgraded).contains("version 16", "version 17", "version 18", "version 19", "version 20", "version 21");
        assertThat(upgraded)
                .as("已发布的库升级后和全新安装的结构不一致：已发布的升级步骤不能就地改，结构变化请新增版本")
                .containsExactlyElementsOf(fresh);
    }

    /** 表、列（按顺序）、约束和普通索引。约束只看类型和列，H2 自动生成的约束名在两边不同。 */
    private static List<String> shape(CrabDatabase database) throws SQLException {
        List<String> shape = new ArrayList<>();
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            collect(statement, shape, """
                    SELECT 'column ' || TABLE_NAME || '.' || COLUMN_NAME || ' ' || DATA_TYPE
                        || ' len=' || COALESCE(CAST(CHARACTER_MAXIMUM_LENGTH AS VARCHAR), '-')
                        || ' null=' || IS_NULLABLE || ' identity=' || IS_IDENTITY
                        || ' default=' || COALESCE(COLUMN_DEFAULT, '-')
                    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'
                    ORDER BY TABLE_NAME, ORDINAL_POSITION
                    """);
            collect(statement, shape, """
                    SELECT 'constraint ' || c.TABLE_NAME || ' ' || c.CONSTRAINT_TYPE || ' ('
                        || COALESCE((SELECT LISTAGG(k.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY k.ORDINAL_POSITION)
                                     FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                                     WHERE k.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA
                                       AND k.CONSTRAINT_NAME = c.CONSTRAINT_NAME), '')
                        || ') ' || COALESCE((SELECT x.CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS x
                                             WHERE x.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA
                                               AND x.CONSTRAINT_NAME = c.CONSTRAINT_NAME), '')
                        || COALESCE((SELECT ' on delete ' || r.DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r
                                     WHERE r.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA
                                       AND r.CONSTRAINT_NAME = c.CONSTRAINT_NAME), '')
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c WHERE c.TABLE_SCHEMA = 'PUBLIC'
                    """);
            collect(statement, shape, """
                    SELECT 'index ' || i.TABLE_NAME || '.' || i.INDEX_NAME || ' ('
                        || LISTAGG(ic.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY ic.ORDINAL_POSITION) || ')'
                    FROM INFORMATION_SCHEMA.INDEXES i
                    JOIN INFORMATION_SCHEMA.INDEX_COLUMNS ic
                      ON ic.INDEX_SCHEMA = i.INDEX_SCHEMA AND ic.INDEX_NAME = i.INDEX_NAME
                    WHERE i.TABLE_SCHEMA = 'PUBLIC' AND i.INDEX_TYPE_NAME = 'INDEX'
                    GROUP BY i.TABLE_NAME, i.INDEX_NAME
                    """);
            collect(statement, shape, "SELECT 'version ' || version FROM schema_version");
        }
        shape.sort(null);
        return shape;
    }

    private static void collect(Statement statement, List<String> into, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                into.add(rows.getString(1));
            }
        }
    }
}
