package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(ReefTestConfig.class)
class AdminBootstrapMissingEnvTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("bootstrap-missing").toString());
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void leavesTheTableEmptyAndWarnsWhenEnvironmentVariablesAreUnset() {
        assertThat(System.getenv(EnvironmentAdminSecretSource.USER_ENV)).isNull();
        assertThat(System.getenv(EnvironmentAdminSecretSource.PASSWORD_ENV)).isNull();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Integer.class);
        assertThat(count).isZero();
        assertThat(MemoryLogAppender.LINES).anyMatch(line -> line.contains(AdminBootstrap.WARN_NONE));
    }
}
