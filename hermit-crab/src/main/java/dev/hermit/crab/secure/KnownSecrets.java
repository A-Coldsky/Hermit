package dev.hermit.crab.secure;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide secret values that must be masked wherever credentials and cookies are.
 * The Reef token from config or an imported bootstrap file is added here. Callers still
 * pass credential and cookie values into {@link Redactor}; this set is merged with them.
 */
public final class KnownSecrets {
    private final Set<String> values = ConcurrentHashMap.newKeySet();

    public static KnownSecrets empty() {
        return new KnownSecrets();
    }

    public void add(String secret) {
        if (secret != null && !secret.isBlank()) {
            values.add(secret);
        }
    }

    public Set<String> copy() {
        return Set.copyOf(values);
    }
}
