package dev.hermit.reef.admin;

import java.time.Instant;

public record CrabInstanceView(
        String owner,
        String instanceId,
        String tokenName,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String crabVersion) {
}
