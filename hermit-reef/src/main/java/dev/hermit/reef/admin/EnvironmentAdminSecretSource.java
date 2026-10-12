package dev.hermit.reef.admin;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentAdminSecretSource implements AdminSecretSource {

    public static final String USER_ENV = "HERMIT_REEF_ADMIN_USER";
    public static final String PASSWORD_ENV = "HERMIT_REEF_ADMIN_PASSWORD";

    @Override
    public Optional<String> username() {
        return nonBlank(System.getenv(USER_ENV));
    }

    @Override
    public Optional<String> password() {
        return nonBlank(System.getenv(PASSWORD_ENV));
    }

    static Optional<String> nonBlank(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
