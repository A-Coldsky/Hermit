package dev.hermit.crab.sync;

import java.util.List;
import java.util.Optional;

public record SyncReport(
        boolean notModified,
        List<ConchOutcome> conches,
        List<String> removed,
        List<CredentialDeclarationChange> credentialChanges,
        Optional<CrabRelease> latestCrab,
        Optional<SyncError> error
) {
    public SyncReport(
            boolean notModified,
            List<ConchOutcome> conches,
            List<String> removed,
            List<CredentialDeclarationChange> credentialChanges,
            Optional<CrabRelease> latestCrab
    ) {
        this(notModified, conches, removed, credentialChanges, latestCrab, Optional.empty());
    }

    public SyncReport {
        conches = conches == null ? List.of() : List.copyOf(conches);
        removed = removed == null ? List.of() : List.copyOf(removed);
        credentialChanges = credentialChanges == null ? List.of() : List.copyOf(credentialChanges);
        latestCrab = latestCrab == null ? Optional.empty() : latestCrab;
        error = error == null ? Optional.empty() : error;
    }

    /** A completed attempt that did not return a catalog. Conch rows are empty so the previous success is not shown. */
    public static SyncReport failed(SyncError error) {
        return new SyncReport(false, List.of(), List.of(), List.of(), Optional.empty(), Optional.of(error));
    }
}
