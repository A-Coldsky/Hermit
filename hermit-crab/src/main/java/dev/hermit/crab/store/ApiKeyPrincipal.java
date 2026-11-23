package dev.hermit.crab.store;

import java.util.Objects;
import java.util.Set;

import dev.hermit.common.model.CapabilityGroups;

public record ApiKeyPrincipal(String id, String name, ApiKeyScope scope) {
    public ApiKeyPrincipal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
    }

    public boolean permits(String conch, String action, boolean actionReadOnly) {
        return permits(conch, action, null, actionReadOnly);
    }

    /**
     * 校验 API Key 是否有权调用该能力。
     * {@code conches} 限定规则包；{@code groups} 与 {@code actions} 都非空时取并集（任一命中即允许），
     * 授予一级分组自动覆盖其下属二级子分组。{@code readOnlyOnly} 始终生效。
     */
    public boolean permits(String conch, String action, String actionGroup, boolean actionReadOnly) {
        if (conch == null || conch.isBlank() || action == null || action.isBlank()) {
            return false;
        }
        if (scope.readOnlyOnly() && !actionReadOnly) {
            return false;
        }
        if (!scope.conches().isEmpty() && !scope.conches().contains(conch)) {
            return false;
        }
        boolean hasActions = !scope.actions().isEmpty();
        boolean hasGroups = !scope.groups().isEmpty();
        if (hasActions || hasGroups) {
            if (hasActions && scope.actions().contains(conch + "." + action)) {
                return true;
            }
            if (hasGroups && matchesGroup(conch, actionGroup, scope.groups())) {
                return true;
            }
            return false;
        }
        return true;
    }

    private static boolean matchesGroup(String conch, String actionGroup, Set<String> allowedGroups) {
        if (actionGroup == null || actionGroup.isEmpty()) {
            return false;
        }
        String prefix = conch + ":";
        for (String entry : allowedGroups) {
            if (entry != null && entry.startsWith(prefix)) {
                String granted = entry.substring(prefix.length());
                if (CapabilityGroups.matches(actionGroup, granted)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ApiKeyPrincipal[id=" + id + ", name=" + name + "]";
    }
}
