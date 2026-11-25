package dev.hermit.reef;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Reef 已经投产：已经合入 main 的迁移一律不改，结构变化只新增更高版本。
 *
 * <p>Flyway 启动时校验已执行迁移的校验和，就地改过的迁移会让生产 Reef 起不来。这里钉住每个迁移文件的 SHA-256，
 * 并从生产所在的版本带数据升级一次。新增迁移合入时，把它的哈希追加到 {@link #MERGED}，同时在
 * {@link #productionDatabaseWithRowsUpgrades()} 里核对它对已有数据的效果；已列出的哈希不能改。
 */
class MigrationHistoryTest {

    /** 生产执行过 V1–V7（V1 含 #125 补上的登录失败表主键）。V8 已合入，随下一次发布上线。 */
    private static final Map<String, String> MERGED = new LinkedHashMap<>();
    private static final String PRODUCTION_VERSION = "7";

    static {
        MERGED.put("V1__init.sql", "d1cf26258a4fed1ec05d6487603bfb8d4e551e45eb62017f997eac22cc3f56d9");
        MERGED.put("V2__tide_reports.sql", "50e9ca804dba19b22506b368ec44e1204a6ad576e9e5fbeb9909e852a83407f0");
        MERGED.put("V3__rollback_event_id.sql", "6ca8de904816ccb0299b98dff107265166e6660ada9a5e597231b241bb2a10da");
        MERGED.put("V4__conch_availability.sql", "04d7c7d11fd00edabf572c2b9b01937e80afd0877ca25dabfb1337840bf938aa");
        MERGED.put("V5__conch_pull_count.sql", "136b34a559c914928bf57bc664d2a08f7a6c86ddd2b1a3d0edb4b06e093c7b68");
        MERGED.put("V6__users_and_agent_tokens.sql", "6bb1ec85735d95e6ab3a28c447bfa7aa437a08414dfca0bbe7a555d135cc3e1a");
        MERGED.put("V7__voided_config_tokens.sql", "00f2d0421cc871cce831d132700fabca943b5619d3b771089bfc34bfda396582");
        MERGED.put("V8__admin_token_version.sql", "a0f5d080b22820c4b95a96d70de9c8dad1973c7d6bb132333b420b1dc4239149");
        MERGED.put("V9__notices_and_submissions.sql", "0d7e72925465a5cf9f868fad9dab4d470bee3360fbdb287845b0cd220776c2b9");
        MERGED.put("V10__conch_auth_use.sql", "0a583ea0735c131912edc1f4e4287b196e6e3726873497f18b73e4bd82509818");
    }

    @Test
    void mergedMigrationsAreNeverEditedInPlace() throws Exception {
        for (Map.Entry<String, String> entry : MERGED.entrySet()) {
            assertThat(sha256("db/migration/" + entry.getKey()))
                    .as("%s 已经合入 main，不能就地修改；结构变化请新增一个更高版本的迁移", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    /**
     * 生产库停在 V7、已有管理员和登录失败记录。升级只跑新增的迁移：已有管理员的 token_version 是 0，
     * 升级前签出的 JWT（没有 tv，按 0 处理）仍然有效，不会因为升级被统一踢下线；
     * V9 新增的公告表与规则审批表从空开始，不影响存量数据；
     * V10 给规则包版本加的 auth_use 在已有的版本行上是 NULL（不引用授权器）。
     */
    @Test
    void productionDatabaseWithRowsUpgrades() throws Exception {
        String url = "jdbc:h2:mem:upgrade-" + UUID.randomUUID() + ";MODE=REGULAR;DB_CLOSE_DELAY=-1";
        flyway(url, PRODUCTION_VERSION).migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) "
                    + "VALUES ('admin', 'x', TIMESTAMP '2026-11-01 00:00:00', FALSE)");
            statement.executeUpdate("INSERT INTO hermit_admin_login_failures (username, failed_at) "
                    + "VALUES ('admin', TIMESTAMP '2026-11-01 00:00:00')");
            statement.executeUpdate("INSERT INTO hermit_conch_versions "
                    + "(conch, version, sha256, package_path, status, uploaded_by, uploaded_at, is_current) "
                    + "VALUES ('legacy_oa', '1.0.0', 'ab', 'p', 'PUBLISHED', 'admin', "
                    + "TIMESTAMP '2026-11-01 00:00:00', TRUE)");
        }

        assertThat(flyway(url, null).migrate().migrationsExecuted).isEqualTo(MERGED.size() - Integer.parseInt(PRODUCTION_VERSION));

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery(
                    "SELECT token_version FROM hermit_admin_users WHERE username = 'admin'")) {
                version.next();
                assertThat(version.getInt(1)).isZero();
            }
            try (ResultSet failures = statement.executeQuery(
                    "SELECT COUNT(*), COUNT(DISTINCT id) FROM hermit_admin_login_failures")) {
                failures.next();
                assertThat(failures.getInt(1)).isEqualTo(1);
                assertThat(failures.getInt(2)).isEqualTo(1);
            }
            try (ResultSet versions = statement.executeQuery(
                    "SELECT COUNT(*), COUNT(auth_use) FROM hermit_conch_versions")) {
                versions.next();
                assertThat(versions.getInt(1)).isEqualTo(1);
                assertThat(versions.getInt(2)).isZero();
            }
            try (ResultSet notices = statement.executeQuery("SELECT COUNT(*) FROM hermit_announcements")) {
                notices.next();
                assertThat(notices.getInt(1)).isZero();
            }
            try (ResultSet submissions = statement.executeQuery("SELECT COUNT(*) FROM hermit_conch_submissions")) {
                submissions.next();
                assertThat(submissions.getInt(1)).isZero();
            }
        }
        assertThat(flyway(url, null).validateWithResult().validationSuccessful).isTrue();
    }

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .table("hermit_flyway_schema_history")
                .placeholderReplacement(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static String sha256(String resource) throws IOException, NoSuchAlgorithmException {
        try (InputStream in = MigrationHistoryTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("缺少已合入的迁移 %s", resource).isNotNull();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }
}
