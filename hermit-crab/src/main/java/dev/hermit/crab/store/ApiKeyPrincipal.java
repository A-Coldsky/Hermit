package dev.hermit.crab.store;

import java.util.Objects;

public record ApiKeyPrincipal(String id, String name, ApiKeyScope scope) {
    public ApiKeyPrincipal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
    }

    public boolean permits(String shell, String action, boolean actionReadOnly) {
        if (shell == null || shell.isBlank() || action == null || action.isBlank()) {
            return false;
        }
        if (scope.readOnlyOnly() && !actionReadOnly) {
            return false;
        }
        if (!scope.shells().isEmpty() && !scope.shells().contains(shell)) {
            return false;
        }
        if (!scope.actions().isEmpty() && !scope.actions().contains(shell + "." + action)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ApiKeyPrincipal[id=" + id + ", name=" + name + "]";
    }
}
