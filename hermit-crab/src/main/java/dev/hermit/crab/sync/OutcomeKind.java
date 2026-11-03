package dev.hermit.crab.sync;

public enum OutcomeKind {
    INSTALLED,
    SKIPPED_SAME,
    SKIPPED_MIN_VERSION,
    /**
     * A subscribed installed conch has a higher manifest version. Sync recorded it and did not
     * download. {@code version} on the outcome is the installed version.
     */
    UPDATE_AVAILABLE,
    /**
     * The manifest version is lower than the installed version. Sync did not download it.
     * {@code version} on the outcome is the installed version.
     */
    SKIPPED_OLDER,
    REJECTED,
    /**
     * Reef dropped a subscribed official conch from the distribution manifest.
     * The installed copy stays loaded and callable. Sync does not download it again
     * until Reef lists the name.
     */
    DISABLED,
    /**
     * The manifest named a conch already loaded from {@code local-conches}.
     * Nothing is downloaded and the local entry is left in place.
     */
    SKIPPED_LOCAL,
    /** Reef listed the conch, and this Crab has not subscribed, so it was not downloaded. */
    NOT_SUBSCRIBED
}
