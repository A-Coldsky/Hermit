package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminUsersTest {

    @Test
    void resetPasswordBlocksCaseInsensitiveSelfResetAndAuditsCanonicalUsername() {
        AdminAccount admin = new AdminAccount(
                1L, "reef-admin", "hash-admin", false, AdminRole.ADMIN, ReefTestConfig.START, 0);
        AdminAccount member = new AdminAccount(
                2L, "reef-user", "hash-user", false, AdminRole.USER, ReefTestConfig.START, 0);
        List<Long> updatedIds = new ArrayList<>();
        List<String> clearedFailures = new ArrayList<>();
        List<String> audits = new ArrayList<>();

        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM hermit_admin_login_failures")) {
                    for (Object arg : args) {
                        clearedFailures.add(String.valueOf(arg));
                    }
                    return 1;
                }
                updatedIds.add((Long) args[1]);
                return 1;
            }

            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return requiredType.cast(0);
            }
        };
        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "encoded:" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
        AuditService auditService = new AuditService(null, null) {
            @Override
            public void record(String actor, AuditAction action, String conch, String version, String detail) {
                audits.add(actor + "|" + action.name() + "|" + detail);
            }
        };
        AdminUsers users = new AdminUsers(
                jdbc,
                encoder,
                auditService,
                Clock.fixed(ReefTestConfig.START, ZoneOffset.UTC),
                new ObjectMapper(),
                new ReefProperties(),
                null,
                null) {
            @Override
            public AdminAccount find(String username) {
                if (admin.username().equalsIgnoreCase(username)) {
                    return admin;
                }
                if (member.username().equalsIgnoreCase(username)) {
                    return member;
                }
                return null;
            }

            @Override
            public AdminAccount initialAdmin() {
                return admin;
            }
        };

        assertThatThrownBy(() -> users.resetPassword("reef-admin", "REEF-ADMIN", "NewPassword-1234"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo(ReefErrorCode.INVALID_INPUT);
                    assertThat(ex.getMessage()).isEqualTo(AdminUsers.CANNOT_RESET_OWN_PASSWORD);
                });
        assertThat(updatedIds).isEmpty();
        assertThat(clearedFailures).isEmpty();
        assertThat(audits).isEmpty();

        assertThatThrownBy(() -> users.resetPassword("reef-admin", "nobody", "NewPassword-1234"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo(ReefErrorCode.NOT_FOUND);
                });

        UserSummary summary = users.resetPassword("reef-admin", "REEF-USER", "NewPassword-1234");
        assertThat(summary.username()).isEqualTo("reef-user");
        assertThat(summary.locked()).isFalse();
        assertThat(updatedIds).containsExactly(2L);
        assertThat(clearedFailures).containsExactly("REEF-USER", "reef-user");
        assertThat(audits).containsExactly("reef-admin|USER_PASSWORD_RESET|{\"username\":\"reef-user\"}");
    }
}
