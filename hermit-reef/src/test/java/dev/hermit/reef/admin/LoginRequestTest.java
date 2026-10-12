package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginRequestTest {

    @Test
    void toStringOmitsPassword() {
        LoginRequest request = new LoginRequest("reef-admin", "ReefTestPassword-9f3a7c");
        assertThat(request.toString()).doesNotContain("ReefTestPassword-9f3a7c").contains("reef-admin");
    }
}
