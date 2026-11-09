package dev.hermit.crab.store;

import java.util.Set;

/**
 * What an API key is allowed to call.
 * An empty {@code conches} set means every conch for {@link ApiKeyPrincipal#permits}.
 * Local conches are included when {@link #grantsAllOfficial()} is true. A key that names
 * any conch or action still needs the local name in {@code conches}; {@link #allowsLocal}
 * is that check for {@code Invoker} and {@code tools/list}.
 * An empty {@code actions} set means every action of the selected conches. Action elements
 * are {@code conch.action}. {@code readOnlyOnly} rejects actions whose read-only flag is false.
 *
 * <p>{@code all} is stored only for a key created with both sets empty.
 * Rows written before the flag omit it, so Jackson reads {@code all} as false; those empty
 * sets still grant every loaded conch, including local ones. {@link #grantsAllOfficial()}
 * is that runtime meaning. Console and CLI creation reject both sets empty unless {@code all}
 * is requested. {@link ApiKeyPrincipal} does not read {@code all}.
 */
public record ApiKeyScope(Set<String> conches, Set<String> actions, boolean readOnlyOnly, boolean all) {
    public static final String MISSING_SCOPE =
            "必须指定范围：设置 all 为 true 表示全部规则包（包括本地包），或填写 conches、actions";
    public static final String ALL_EXCLUDES_NAMES =
            "all 为 true 时不能同时填写 conches 或 actions";

    public ApiKeyScope {
        conches = conches == null ? Set.of() : Set.copyOf(conches);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        if (all && (!conches.isEmpty() || !actions.isEmpty())) {
            throw new IllegalArgumentException(ALL_EXCLUDES_NAMES);
        }
    }

    /** Same as {@code all == false}. Existing callers and stored rows use this shape. */
    public ApiKeyScope(Set<String> conches, Set<String> actions, boolean readOnlyOnly) {
        this(conches, actions, readOnlyOnly, false);
    }

    /**
     * Scope from a console or CLI create. {@code all} requests every loaded conch and
     * every action, including local conches, still subject to {@code readOnlyOnly}.
     * Both sets empty without {@code all} is rejected. {@code all} together with any name is rejected.
     */
    public static ApiKeyScope explicit(boolean all, Set<String> conches, Set<String> actions, boolean readOnlyOnly) {
        Set<String> namedConches = conches == null ? Set.of() : Set.copyOf(conches);
        Set<String> namedActions = actions == null ? Set.of() : Set.copyOf(actions);
        boolean named = !namedConches.isEmpty() || !namedActions.isEmpty();
        if (all && named) {
            throw new IllegalArgumentException(ALL_EXCLUDES_NAMES);
        }
        if (!all && !named) {
            throw new IllegalArgumentException(MISSING_SCOPE);
        }
        if (all) {
            return new ApiKeyScope(Set.of(), Set.of(), readOnlyOnly, true);
        }
        return new ApiKeyScope(namedConches, namedActions, readOnlyOnly, false);
    }

    /**
     * Every loaded conch and every action, before the read-only filter, including local conches.
     * True for an explicit {@code all} key and for a stored row whose sets are both empty.
     * A key that names any conch or action is false here and must list a local conch to use it.
     */
    public boolean grantsAllOfficial() {
        return conches.isEmpty() && actions.isEmpty();
    }

    /**
     * Whether this key may call and list {@code conch} when that package is local.
     * Full access ({@link #grantsAllOfficial()}) may. Any other key may only when
     * {@link #conches()} names it. Read-only is enforced separately.
     */
    public boolean allowsLocal(String conch) {
        if (conch == null || conch.isBlank()) {
            return false;
        }
        return grantsAllOfficial() || conches.contains(conch);
    }
}
