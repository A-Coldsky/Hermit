package dev.hermit.crab.store;

import java.util.Objects;

public record ApiKeyPrincipal(String id, String name, ApiKeyScope scope) {
    public ApiKeyPrincipal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
    }

    public boolean permits(String conch, String action, boolean actionReadOnly) {
        if (conch == null || conch.isBlank() || action == null || action.isBlank()) {
            return false;
        }
        if (scope.readOnlyOnly() && !actionReadOnly) {
            return false;
        }
        if (!scope.conches().isEmpty() && !scope.conches().contains(conch)) {
            return false;
        }
        if (!scope.actions().isEmpty() && !scope.actions().contains(conch + "." + action)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ApiKeyPrincipal[id=" + id + ", name=" + name + "]";
    }
}
