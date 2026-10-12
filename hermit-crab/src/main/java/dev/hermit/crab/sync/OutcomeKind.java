package dev.hermit.crab.sync;

public enum OutcomeKind {
    INSTALLED,
    SKIPPED_SAME,
    SKIPPED_MIN_VERSION,
    SKIPPED_PINNED,
    REJECTED
}
