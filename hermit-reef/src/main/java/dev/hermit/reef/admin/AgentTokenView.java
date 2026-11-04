package dev.hermit.reef.admin;

import java.time.Instant;

public record AgentTokenView(
        Long id,
        String owner,
        String name,
        String prefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt,
        TokenSource source) {
}
