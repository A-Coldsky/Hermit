package dev.hermit.crab.sync;

public enum OutcomeKind {
    INSTALLED,
    SKIPPED_SAME,
    SKIPPED_MIN_VERSION,
    SKIPPED_PINNED,
    REJECTED,
    /** Local status after {@link RollbackGuard} restored an older installed version. */
    ROLLED_BACK,
    /** Reef dropped the conch from the distribution manifest. Calls return CONCH_DISABLED. */
    DISABLED,
    /**
     * The manifest named a conch already loaded from {@code local-conches}.
     * Nothing is downloaded and the local entry is left in place.
     */
    SKIPPED_LOCAL
}
