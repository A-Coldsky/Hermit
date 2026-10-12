package dev.hermit.crab.store;

import java.time.Instant;
import java.util.Objects;

/** A stored API key without its hash or plaintext. */
public record ApiKeySummary(
        String id,
        String name,
        String prefix,
        ApiKeyScope scope,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt
) {
    public ApiKeySummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
