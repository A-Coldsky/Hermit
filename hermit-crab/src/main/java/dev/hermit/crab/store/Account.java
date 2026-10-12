package dev.hermit.crab.store;

import java.time.Instant;
import java.util.Objects;

public record Account(
        Origin origin,
        String shell,
        String account,
        boolean defaultAccount,
        Instant createdAt
) {
    public Account {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
