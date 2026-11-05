package dev.hermit.crab.store;

/**
 * Audit actors for this phase. Pass {@link #CLI} or {@link #CONSOLE}.
 * Do not copy an actor out of an HTTP request.
 */
public final class Actors {
    public static final String CLI = "cli";
    public static final String CONSOLE = "console";
    /** Background sync. Not taken from a request. */
    public static final String SYNC = "sync";
    /**
     * Debug window ended with nobody at the console. Written by {@code DebugStateStore} directly.
     * {@code Checks.actor} does not accept this value, so callers cannot supply it.
     */
    public static final String RETENTION = "retention";

    private Actors() {
    }
}
