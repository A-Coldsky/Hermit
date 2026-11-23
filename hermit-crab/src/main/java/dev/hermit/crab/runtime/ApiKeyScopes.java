package dev.hermit.crab.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

import dev.hermit.common.model.CapabilityGroups;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.sync.DisabledConches;

/**
 * API key action entries are {@code conch.action} names of capabilities currently loaded.
 * Group entries are {@code conch:group} or {@code conch:parent/child} declared on a loaded conch.
 * An empty actions and groups set still means every action. Bare names and unknown capabilities
 * or groups are rejected at creation so a key is not stored only to fail every call with {@code FORBIDDEN}.
 * Names in {@code conches} must be loaded, or tombstoned in {@code disabled_conches}.
 * A loaded local conch counts as known. {@link ApiKeyScope#grantsAllOfficial()} includes local
 * conches. A key that names any conch, group, or action still needs the local package name in
 * {@code conches}; {@code Invoker} and the MCP catalog both use {@link ApiKeyScope#allowsLocal}.
 * Console and CLI creation use {@link ApiKeyScope#explicit} so a new key cannot store all
 * sets empty unless that full-access flag was requested. This check does not.
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
        for (String group : scope.groups()) {
            int colon = group == null ? -1 : group.indexOf(':');
            if (colon <= 0 || colon != group.lastIndexOf(':') || colon == group.length() - 1) {
                throw new IllegalArgumentException("分组范围必须写成 conch:group 或 conch:parent/child: " + shown(group));
            }
            String conch = group.substring(0, colon);
            String path = group.substring(colon + 1);
            ConchDoc doc = registry.find(conch).orElse(null);
            if (doc == null || !CapabilityGroups.hasGroup(doc, path)) {
                throw new IllegalArgumentException("分组不存在: " + shown(group));
            }
        }
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
