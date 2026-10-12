package dev.hermit.crab.store;

import java.time.Instant;
import java.util.Objects;

/**
 * The only object that holds an API key plaintext. {@link #plaintext()} is the single read,
 * and {@link #toString()} leaves it out so a log of the create result cannot persist the key.
 */
public final class CreatedApiKey {
    private final String id;
    private final String name;
    private final String prefix;
    private final String plaintext;
    private final ApiKeyScope scope;
    private final Instant createdAt;

    public CreatedApiKey(String id, String name, String prefix, String plaintext, ApiKeyScope scope, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.plaintext = Objects.requireNonNull(plaintext, "plaintext");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String prefix() {
        return prefix;
    }

    public String plaintext() {
        return plaintext;
    }

    public ApiKeyScope scope() {
        return scope;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "CreatedApiKey[id=" + id + ", name=" + name + ", prefix=" + prefix + "]";
    }
}
