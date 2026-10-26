package dev.hermit.crab.store;

import java.time.Instant;
import java.util.Objects;

public record Account(
        Origin origin,
        String conch,
        String account,
        boolean defaultAccount,
        Instant createdAt
) {
    public Account {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(conch, "conch");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
