package dev.hermit.crab.engine;

import java.util.Objects;

import dev.hermit.crab.store.Origin;

public record SessionKey(Origin origin, String conch, String account) {

    public SessionKey {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(conch, "conch");
        Objects.requireNonNull(account, "account");
    }
}
