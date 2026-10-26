package dev.hermit.crab.sync;

public enum OutcomeKind {
    INSTALLED,
    SKIPPED_SAME,
    SKIPPED_MIN_VERSION,
    SKIPPED_PINNED,
    REJECTED,
    /** Local status after {@link RollbackGuard} restored an older installed version. */
    ROLLED_BACK,
    /** Reef dropped the shell from the distribution manifest. Calls return SHELL_DISABLED. */
    DISABLED
}
