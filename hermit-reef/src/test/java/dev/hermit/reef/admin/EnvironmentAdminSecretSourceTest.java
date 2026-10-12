package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnvironmentAdminSecretSourceTest {

    @Test
    void blankValuesCountAsUnset() {
        assertThat(EnvironmentAdminSecretSource.nonBlank(null)).isEmpty();
        assertThat(EnvironmentAdminSecretSource.nonBlank("")).isEmpty();
        assertThat(EnvironmentAdminSecretSource.nonBlank("   ")).isEmpty();
        assertThat(EnvironmentAdminSecretSource.nonBlank("admin")).contains("admin");
    }

    @Test
    void readsTheDocumentedEnvironmentVariables() {
        EnvironmentAdminSecretSource source = new EnvironmentAdminSecretSource();
        assertThat(EnvironmentAdminSecretSource.USER_ENV).isEqualTo("HERMIT_REEF_ADMIN_USER");
        assertThat(EnvironmentAdminSecretSource.PASSWORD_ENV).isEqualTo("HERMIT_REEF_ADMIN_PASSWORD");
        if (System.getenv(EnvironmentAdminSecretSource.USER_ENV) == null) {
            assertThat(source.username()).isEmpty();
        }
        if (System.getenv(EnvironmentAdminSecretSource.PASSWORD_ENV) == null) {
            assertThat(source.password()).isEmpty();
        }
    }
}
