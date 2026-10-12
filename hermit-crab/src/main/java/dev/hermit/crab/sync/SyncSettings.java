package dev.hermit.crab.sync;

import java.net.URI;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.hermit.common.version.SemVer;

public record SyncSettings(
        URI reefUrl,
        Optional<String> token,
        PublicKey reefKey,
        Duration interval,
        UUID instanceId,
        String crabVersion
) {
    public SyncSettings {
        Objects.requireNonNull(reefUrl, "reefUrl");
        Objects.requireNonNull(reefKey, "reefKey");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(crabVersion, "crabVersion");
        String scheme = reefUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("reefUrl must be http or https");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (reefUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException("reefUrl must not contain user info");
        }
        token = token == null || token.filter(value -> !value.isBlank()).isEmpty()
                ? Optional.empty()
                : Optional.of(token.get());
        SemVer.parse(crabVersion);
    }

    @Override
    public String toString() {
        return "SyncSettings[reefUrl=" + reefUrl
                + ", token=" + (token.isPresent() ? "[present]" : "[absent]")
                + ", interval=" + interval
                + ", instanceId=" + instanceId
                + ", crabVersion=" + crabVersion
                + "]";
    }
}
