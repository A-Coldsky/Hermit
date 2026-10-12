package dev.hermit.reef.admin;

import java.util.Optional;

public interface AdminSecretSource {

    Optional<String> username();

    Optional<String> password();
}
