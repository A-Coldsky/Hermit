package dev.hermit.crab.store;

import java.util.Set;

/**
 * What an API key is allowed to call. An empty {@code conches} set means every conch for
 * {@link ApiKeyPrincipal#permits}. Local conches are a further check in {@code Invoker}:
 * the name must be listed even when this set is empty.
 * An empty {@code actions} set means every action of the selected conches. Action elements
 * are {@code conch.action}. {@code readOnlyOnly} rejects actions whose read-only flag is false.
 */
public record ApiKeyScope(Set<String> conches, Set<String> actions, boolean readOnlyOnly) {
    public ApiKeyScope {
        conches = conches == null ? Set.of() : Set.copyOf(conches);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
    }
}
