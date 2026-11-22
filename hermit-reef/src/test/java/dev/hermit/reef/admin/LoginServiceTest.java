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

    @Autowired
    dev.hermit.reef.security.JwtSecretStore jwtSecretStore;

    @Autowired
    dev.hermit.reef.config.ReefProperties reefProperties;

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

    @Test
    void canonicalUsernameIsUsedForJwtAndAuditWhenDbMatchesCaseInsensitively() {
        List<String> jwtSubjects = new java.util.ArrayList<>();
        List<String> auditEvents = new java.util.ArrayList<>();
        AdminAccount canonical = new AdminAccount(
                1L,
                "admin",
                passwordEncoder.encode(TestSecrets.PASSWORD),
                false,
                AdminRole.ADMIN,
                ReefTestConfig.START,
                3);
        AdminUsers caseInsensitiveUsers = new AdminUsers(null, null, null, null, null, null, null, null) {
            @Override
            public AdminAccount find(String username) {
                return "admin".equalsIgnoreCase(username) ? canonical : null;
            }
        };
        dev.hermit.reef.audit.AuditService audit = new dev.hermit.reef.audit.AuditService(null, null) {
            @Override
            public void record(String actor, dev.hermit.reef.audit.AuditAction action, String conch, String version,
                    String detail) {
                auditEvents.add(action.name() + ":" + actor);
            }
        };
        dev.hermit.reef.security.JwtService jwt = new dev.hermit.reef.security.JwtService(
                jwtSecretStore,
                reefProperties,
                java.time.Clock.fixed(ReefTestConfig.START, java.time.ZoneOffset.UTC)) {
            @Override
            public dev.hermit.reef.security.IssuedJwt issue(String username, AdminRole role, int tokenVersion) {
                jwtSubjects.add(username);
                return super.issue(username, role, tokenVersion);
            }
        };
        org.springframework.transaction.PlatformTransactionManager tx =
                new org.springframework.transaction.PlatformTransactionManager() {
                    @Override
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return new org.springframework.transaction.support.SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(org.springframework.transaction.TransactionStatus status) {
                    }

                    @Override
                    public void rollback(org.springframework.transaction.TransactionStatus status) {
                    }
                };
        LoginService service = new LoginService(
                jdbc,
                passwordEncoder,
                jwt,
                audit,
                caseInsensitiveUsers,
                java.time.Clock.fixed(ReefTestConfig.START, java.time.ZoneOffset.UTC),
                tx);

        assertThat(service.login("ADMIN", "wrong-password")).isEmpty();
        assertThat(service.login("Unknown", "wrong-password")).isEmpty();
        java.util.Optional<LoginResponse> response = service.login("ADMIN", TestSecrets.PASSWORD);
        assertThat(response).isPresent();
        assertThat(jwtSubjects).containsExactly("admin");
        String payload = new String(
                java.util.Base64.getUrlDecoder().decode(response.get().token().split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("\"sub\":\"admin\"");
        assertThat(auditEvents).containsExactly(
                "LOGIN_FAILURE:admin",
                "LOGIN_FAILURE:Unknown",
                "LOGIN_SUCCESS:admin");
    }
}
