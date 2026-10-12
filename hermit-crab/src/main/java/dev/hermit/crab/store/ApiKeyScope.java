package dev.hermit.crab.store;

import java.util.Set;

/**
 * What an API key is allowed to call. An empty {@code shells} set means every shell.
 * An empty {@code actions} set means every action of the selected shells. Action elements
 * are {@code shell.action}. {@code readOnlyOnly} rejects actions whose read-only flag is false.
 */
public record ApiKeyScope(Set<String> shells, Set<String> actions, boolean readOnlyOnly) {
    public ApiKeyScope {
        shells = shells == null ? Set.of() : Set.copyOf(shells);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
    }
}
