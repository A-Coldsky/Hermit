package dev.hermit.crab.store;

/**
 * Audit actors for this phase. Pass {@link #CLI} or {@link #CONSOLE}.
 * Do not copy an actor out of an HTTP request.
 */
public final class Actors {
    public static final String CLI = "cli";
    public static final String CONSOLE = "console";

    private Actors() {
    }
}
