package dev.hermit.crab.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strings returned by a {@code credential: true} script during the current call.
 * Included in the result redactor and the dev-trace redactor. Not written to
 * config audit and not added to {@link dev.hermit.crab.secure.KnownSecrets}.
 */
final class CallSecrets {
    private static final ThreadLocal<LinkedHashSet<String>> CURRENT = new ThreadLocal<>();

    private CallSecrets() {
    }

    static void begin() {
        CURRENT.set(new LinkedHashSet<>());
    }

    static void end() {
        CURRENT.remove();
    }

    static void remember(Object value) {
        LinkedHashSet<String> secrets = CURRENT.get();
        if (secrets != null) {
            walk(secrets, value, 0);
        }
    }

    static Set<String> current() {
        LinkedHashSet<String> secrets = CURRENT.get();
        return secrets == null ? Set.of() : Set.copyOf(secrets);
    }

    private static void walk(Set<String> secrets, Object value, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() >= 4 && !"***".equals(text)) {
                secrets.add(text);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                walk(secrets, item, depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                walk(secrets, item, depth + 1);
            }
        }
    }
}
