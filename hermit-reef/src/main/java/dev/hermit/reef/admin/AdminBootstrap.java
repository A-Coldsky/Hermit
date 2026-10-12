package dev.hermit.reef.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {

    static final String WARN_NONE = "No administrator configured. Set HERMIT_REEF_ADMIN_USER and "
            + "HERMIT_REEF_ADMIN_PASSWORD to create the initial administrator.";
    static final String WARN_PARTIAL = "Initial administrator was not created. HERMIT_REEF_ADMIN_USER and "
            + "HERMIT_REEF_ADMIN_PASSWORD must both be set.";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AdminSecretSource secretSource;
    private final Clock clock;

    public AdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AdminSecretSource secretSource,
            Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.secretSource = secretSource;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Integer.class);
        int users = count == null ? 0 : count;
        Optional<String> username = secretSource.username();
        Optional<String> password = secretSource.password();
        switch (plan(users, username, password)) {
            case SKIP_EXISTING -> {
            }
            case WARN_NONE -> log.warn(WARN_NONE);
            case WARN_PARTIAL -> log.warn(WARN_PARTIAL);
            case CREATE -> create(username.orElseThrow(), password.orElseThrow());
        }
    }

    static BootstrapPlan plan(int existingUsers, Optional<String> username, Optional<String> password) {
        if (existingUsers > 0) {
            return BootstrapPlan.SKIP_EXISTING;
        }
        if (username.isEmpty() && password.isEmpty()) {
            return BootstrapPlan.WARN_NONE;
        }
        if (username.isEmpty() || password.isEmpty()) {
            return BootstrapPlan.WARN_PARTIAL;
        }
        return BootstrapPlan.CREATE;
    }

    private void create(String username, String password) {
        jdbc.update(
                "INSERT INTO admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                username,
                passwordEncoder.encode(password),
                Timestamp.from(clock.instant()));
        log.info("Created initial administrator username={}", username);
    }

    enum BootstrapPlan {
        SKIP_EXISTING,
        WARN_NONE,
        WARN_PARTIAL,
        CREATE
    }
}
