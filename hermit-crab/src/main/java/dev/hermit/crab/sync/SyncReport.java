package dev.hermit.crab.sync;

import java.util.List;
import java.util.Optional;

public record SyncReport(
        boolean notModified,
        List<ShellOutcome> shells,
        List<String> removed,
        List<CredentialDeclarationChange> credentialChanges,
        Optional<CrabRelease> latestCrab
) {
    public SyncReport {
        shells = shells == null ? List.of() : List.copyOf(shells);
        removed = removed == null ? List.of() : List.copyOf(removed);
        credentialChanges = credentialChanges == null ? List.of() : List.copyOf(credentialChanges);
        latestCrab = latestCrab == null ? Optional.empty() : latestCrab;
    }
}
