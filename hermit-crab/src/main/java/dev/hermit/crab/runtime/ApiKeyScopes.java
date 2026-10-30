package dev.hermit.crab.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.sync.DisabledConches;

/**
 * API key action entries are {@code conch.action} names of capabilities currently loaded.
 * An empty actions set still means every action. Bare names and unknown capabilities are rejected
 * at creation so a key is not stored only to fail every call with {@code FORBIDDEN}.
 * Names in {@code conches} must be loaded, or tombstoned in {@code disabled_conches}.
 * A loaded local conch counts as known. An empty {@code conches} set does not authorize calls
 * to local conches; {@code Invoker} requires the name to be listed.
 */
public final class ApiKeyScopes {
    private ApiKeyScopes() {
    }

    public static void requireInstalled(ConchRegistry registry, ApiKeyScope scope) {
        requireInstalled(registry, scope, DisabledConches.none());
    }

    public static void requireInstalled(ConchRegistry registry, ApiKeyScope scope, DisabledConches disabled) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(scope, "scope");
        DisabledConches tombstones = disabled == null ? DisabledConches.none() : disabled;
        rejectUnknownConches(registry, tombstones, scope);
        for (String action : scope.actions()) {
            int dot = action == null ? -1 : action.indexOf('.');
            if (dot <= 0 || dot != action.lastIndexOf('.') || dot == action.length() - 1) {
                throw new IllegalArgumentException("能力范围必须写成 conch.action，不能填写裸能力名: " + shown(action));
            }
            String conch = action.substring(0, dot);
            String name = action.substring(dot + 1);
            ConchDoc doc = registry.find(conch).orElse(null);
            if (doc == null || doc.actions().get(name) == null) {
                throw new IllegalArgumentException("能力不存在: " + shown(action));
            }
        }
    }

    private static void rejectUnknownConches(ConchRegistry registry, DisabledConches disabled, ApiKeyScope scope) {
        ArrayList<String> unknown = new ArrayList<>();
        for (String name : scope.conches()) {
            if (!knownConch(registry, disabled, name)) {
                unknown.add(shown(name));
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        unknown.sort(Comparator.naturalOrder());
        throw new IllegalArgumentException("规则包不存在: " + joinLimited(unknown));
    }

    private static boolean knownConch(ConchRegistry registry, DisabledConches disabled, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (registry.find(name).isPresent()) {
            return true;
        }
        return disabled.contains(name);
    }

    private static String joinLimited(ArrayList<String> names) {
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (builder.length() == 0) {
                builder.append(name.length() <= 160 ? name : name.substring(0, 160));
                continue;
            }
            if (builder.length() + 1 + name.length() > 160) {
                break;
            }
            builder.append('、').append(name);
        }
        return builder.toString();
    }

    private static String shown(String value) {
        if (value == null || value.isBlank()) {
            return "（空）";
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ');
        return cleaned.length() <= 60 ? cleaned : cleaned.substring(0, 60);
    }
}
