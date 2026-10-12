package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import({ReefTestConfig.class, AdminBootstrapCreatesAdminTest.SecretConfig.class})
class AdminBootstrapCreatesAdminTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("bootstrap-create").toString());
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ApplicationRunner adminBootstrap;

    @Test
    void createsOneAdministratorAndDoesNotLogThePassword() throws Exception {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Integer.class);
        assertThat(count).isEqualTo(1);
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM admin_users WHERE username = ?",
                String.class,
                TestSecrets.USERNAME);
        assertThat(hash).isNotEqualTo(TestSecrets.PASSWORD);
        assertThat(passwordEncoder.matches(TestSecrets.PASSWORD, hash)).isTrue();
        assertThat(MemoryLogAppender.LINES).anyMatch(line -> line.contains("username=" + TestSecrets.USERNAME));
        assertThat(MemoryLogAppender.LINES).noneMatch(line -> line.contains(TestSecrets.PASSWORD));

        String before = hash;
        adminBootstrap.run(null);
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Integer.class);
        String hashAfter = jdbc.queryForObject("SELECT password_hash FROM admin_users WHERE username = ?",
                String.class, TestSecrets.USERNAME);
        assertThat(after).isEqualTo(1);
        assertThat(hashAfter).isEqualTo(before);
    }

    @TestConfiguration
    static class SecretConfig {

        @Bean
        @Primary
        AdminSecretSource adminSecretSource() {
            return new AdminSecretSource() {
                @Override
                public Optional<String> username() {
                    return Optional.of(TestSecrets.USERNAME);
                }

                @Override
                public Optional<String> password() {
                    return Optional.of(TestSecrets.PASSWORD);
                }
            };
        }
    }
}
