package dev.hermit.crab.engine;

import java.time.Duration;
import java.util.Objects;

import dev.hermit.crab.http.HttpMessageLimits;
import dev.hermit.crab.http.OutboundHttp;

public record EngineSettings(
        Duration capabilityTimeout,
        Duration sessionIdleLimit,
        int maxLoginFailures,
        Duration connectTimeout,
        Duration bodyIdleTimeout,
        HttpMessageLimits headers
) {

    public EngineSettings(Duration capabilityTimeout, Duration sessionIdleLimit, int maxLoginFailures) {
        this(
                capabilityTimeout,
                sessionIdleLimit,
                maxLoginFailures,
                OutboundHttp.DEFAULT_CONNECT,
                OutboundHttp.DEFAULT_BODY_IDLE,
                HttpMessageLimits.defaults());
    }

    public EngineSettings {
        Objects.requireNonNull(capabilityTimeout, "capabilityTimeout");
        Objects.requireNonNull(sessionIdleLimit, "sessionIdleLimit");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(bodyIdleTimeout, "bodyIdleTimeout");
        Objects.requireNonNull(headers, "headers");
        if (capabilityTimeout.isZero() || capabilityTimeout.isNegative()) {
            throw new IllegalArgumentException("capabilityTimeout 必须为正");
        }
        if (sessionIdleLimit.isZero() || sessionIdleLimit.isNegative()) {
            throw new IllegalArgumentException("sessionIdleLimit 必须为正");
        }
        if (maxLoginFailures < 1) {
            throw new IllegalArgumentException("maxLoginFailures 至少为 1");
        }
        long connectMs = connectTimeout.toMillis();
        if (connectMs < 1 || connectMs > 600_000) {
            throw new IllegalArgumentException("connectTimeout 必须在 1 毫秒到 600000 毫秒之间");
        }
        long idleMs = bodyIdleTimeout.toMillis();
        if (idleMs < 1 || idleMs > 600_000) {
            throw new IllegalArgumentException("bodyIdleTimeout 必须在 1 毫秒到 600000 毫秒之间");
        }
    }

    public static EngineSettings defaults() {
        return new EngineSettings(Duration.ofSeconds(60), Duration.ofMinutes(10), 3);
    }
}
