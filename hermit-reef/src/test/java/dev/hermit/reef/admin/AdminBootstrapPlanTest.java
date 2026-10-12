package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminBootstrapPlanTest {

    @Test
    void skipsWhenAnAdministratorAlreadyExists() {
        assertThat(AdminBootstrap.plan(1, Optional.of("admin"), Optional.of("secret")))
                .isEqualTo(AdminBootstrap.BootstrapPlan.SKIP_EXISTING);
    }

    @Test
    void warnsWhenBothEnvironmentValuesAreAbsent() {
        assertThat(AdminBootstrap.plan(0, Optional.empty(), Optional.empty()))
                .isEqualTo(AdminBootstrap.BootstrapPlan.WARN_NONE);
    }

    @Test
    void warnsWhenOnlyOneEnvironmentValueIsPresent() {
        assertThat(AdminBootstrap.plan(0, Optional.of("admin"), Optional.empty()))
                .isEqualTo(AdminBootstrap.BootstrapPlan.WARN_PARTIAL);
        assertThat(AdminBootstrap.plan(0, Optional.empty(), Optional.of("secret")))
                .isEqualTo(AdminBootstrap.BootstrapPlan.WARN_PARTIAL);
    }

    @Test
    void createsWhenBothValuesArePresentOnAnEmptyTable() {
        assertThat(AdminBootstrap.plan(0, Optional.of("admin"), Optional.of("secret")))
                .isEqualTo(AdminBootstrap.BootstrapPlan.CREATE);
    }
}
