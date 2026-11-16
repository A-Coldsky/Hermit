package dev.hermit.crab.sync;

/**
 * A round may record the manifest ETag only when every conch outcome is safe to skip next time.
 * Reasons that are not an explicit permanent rejection stay retryable, including ones added later.
 * {@link OutcomeKind#REPAIR_REQUIRED}, {@link OutcomeKind#RULE_DISABLED}, and
 * {@link OutcomeKind#SKIPPED_MIN_VERSION} stay retryable so a 304 cannot hide a package that is
 * not loaded, or that this Crab is not yet new enough to install.
 */
final class SyncFailures {

    private SyncFailures() {
    }

    static boolean retryable(ConchOutcome outcome) {
        if (outcome.kind() == null) {
            return true;
        }
        return switch (outcome.kind()) {
            case INSTALLED, SKIPPED_SAME, UPDATE_AVAILABLE, SKIPPED_OLDER, DISABLED,
                    SKIPPED_LOCAL, NOT_SUBSCRIBED, REPAIRED -> false;
            case SKIPPED_MIN_VERSION, REPAIR_REQUIRED, RULE_DISABLED -> true;
            case REJECTED -> !permanent(outcome.reason());
        };
    }

    private static boolean permanent(String reason) {
        return SyncReasons.CONCH_NAME.equals(reason)
                || SyncReasons.VERSION_INVALID.equals(reason)
                || SyncReasons.PATH_UNSAFE.equals(reason)
                || SyncReasons.ZIP_TOO_LARGE.equals(reason)
                || SyncReasons.MANIFEST_ENTRY_MISMATCH.equals(reason);
    }
}
