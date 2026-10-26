package dev.hermit.crab.runtime;

import java.util.Objects;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.store.ApiKeyScope;

/**
 * API key action entries are {@code shell.action} names of capabilities currently loaded.
 * An empty actions set still means every action. Bare names and unknown capabilities are rejected
 * at creation so a key is not stored only to fail every call with {@code FORBIDDEN}.
 */
public final class ApiKeyScopes {
    private ApiKeyScopes() {
    }

    public static void requireInstalled(ShellRegistry registry, ApiKeyScope scope) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(scope, "scope");
        for (String action : scope.actions()) {
            int dot = action == null ? -1 : action.indexOf('.');
            if (dot <= 0 || dot != action.lastIndexOf('.') || dot == action.length() - 1) {
                throw new IllegalArgumentException("能力范围必须写成 shell.action，不能填写裸能力名: " + shown(action));
            }
            String shell = action.substring(0, dot);
            String name = action.substring(dot + 1);
            ShellDoc doc = registry.find(shell).orElse(null);
            if (doc == null || doc.actions().get(name) == null) {
                throw new IllegalArgumentException("能力不存在: " + shown(action));
            }
        }
    }

    private static String shown(String value) {
        if (value == null || value.isBlank()) {
            return "（空）";
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ');
        return cleaned.length() <= 60 ? cleaned : cleaned.substring(0, 60);
    }
}
