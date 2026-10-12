package dev.hermit.crab.report;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ReportSettings(
        boolean enabled,
        URI reefUrl,
        Optional<String> token,
        UUID instanceId,
        String crabVersion
) {
    public static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    public static final Duration MAX_BACKOFF = Duration.ofHours(1);

    public ReportSettings {
        Objects.requireNonNull(reefUrl, "reefUrl");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(crabVersion, "crabVersion");
        String scheme = reefUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("reefUrl must be http or https");
        }
        if (reefUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException("reefUrl must not contain user info");
        }
        if (crabVersion.isBlank() || crabVersion.length() > 64) {
            throw new IllegalArgumentException("crabVersion length must be 1..64");
        }
        token = token == null || token.filter(value -> !value.isBlank()).isEmpty()
                ? Optional.empty()
                : Optional.of(token.get());
    }

    @Override
    public String toString() {
        return "ReportSettings[enabled=" + enabled
                + ", reefUrl=" + reefUrl
                + ", token=" + (token.isPresent() ? "[present]" : "[absent]")
                + ", instanceId=" + instanceId
                + ", crabVersion=" + crabVersion
                + "]";
    }
}
