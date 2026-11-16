package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(ReefTestConfig.class)
class LoginServiceTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("login-service").toString());
    }

    @Autowired
    LoginService logins;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM hermit_admin_login_failures");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                "admin",
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
    }

    @Test
    void lockStripeFoldsCaseAndTrailingSpacesOnly() {
        int admin = LoginService.lockStripe("admin");
        assertThat(LoginService.lockStripe("Admin")).isEqualTo(admin);
        assertThat(LoginService.lockStripe("ADMIN")).isEqualTo(admin);
        assertThat(LoginService.lockStripe("admin ")).isEqualTo(admin);
        assertThat(LoginService.lockStripe("admin  ")).isEqualTo(admin);
        assertThat(LoginService.lockStripe("Admin ")).isEqualTo(admin);
        assertThat(LoginService.lockKey("admin")).isEqualTo("admin");
        assertThat(LoginService.lockKey("Admin ")).isEqualTo("admin");

        assertThat(LoginService.lockKey(" admin")).isEqualTo(" admin");
        assertThat(LoginService.lockKey(" admin ")).isEqualTo(" admin");
        assertThat(LoginService.lockStripe(" admin")).isNotEqualTo(admin);
        assertThat(LoginService.lockKey("admin\t")).isEqualTo("admin\t");
        assertThat(LoginService.lockKey("admin \t")).isEqualTo("admin \t");
        assertThat(LoginService.lockKey("admin\u00A0")).isEqualTo("admin\u00A0");
    }

    @Test
    void loginLooksUpAndRecordsTheOriginalUsername() {
        assertThat(logins.login("Admin", TestSecrets.PASSWORD)).isEmpty();
        assertThat(logins.login("admin ", TestSecrets.PASSWORD)).isEmpty();
        assertThat(logins.login(" admin", TestSecrets.PASSWORD)).isEmpty();
        assertThat(logins.login("admin", TestSecrets.PASSWORD)).isPresent();

        List<String> recorded = jdbc.queryForList(
                "SELECT username FROM hermit_admin_login_failures ORDER BY id",
                String.class);
        assertThat(recorded).containsExactly("Admin", "admin ", " admin");
    }
}
