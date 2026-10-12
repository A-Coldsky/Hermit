package dev.hermit.crab.engine;

import java.time.Duration;
import java.util.Objects;

public record EngineSettings(Duration capabilityTimeout, Duration sessionIdleLimit, int maxLoginFailures) {

    public EngineSettings {
        Objects.requireNonNull(capabilityTimeout, "capabilityTimeout");
        Objects.requireNonNull(sessionIdleLimit, "sessionIdleLimit");
        if (capabilityTimeout.isZero() || capabilityTimeout.isNegative()) {
            throw new IllegalArgumentException("capabilityTimeout 必须为正");
        }
        if (sessionIdleLimit.isZero() || sessionIdleLimit.isNegative()) {
            throw new IllegalArgumentException("sessionIdleLimit 必须为正");
        }
        if (maxLoginFailures < 1) {
            throw new IllegalArgumentException("maxLoginFailures 至少为 1");
        }
    }

    public static EngineSettings defaults() {
        return new EngineSettings(Duration.ofSeconds(60), Duration.ofMinutes(10), 3);
    }
}
