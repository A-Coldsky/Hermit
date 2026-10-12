package dev.hermit.reef.testsupport;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration
public class ReefTestConfig {

    public static final Instant START = Instant.parse("2026-06-01T00:00:00Z");

    @Bean
    @Primary
    AdjustableClock testClock() {
        return new AdjustableClock(START);
    }

    @Bean
    @Primary
    PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder(4);
    }
}
