package dev.hermit.crab.sync;

import java.util.Optional;

/**
 * A manual update. {@code fromVersion} and {@code toVersion} are set when the catalog version
 * replaced a different installed version. Reloading a verified zip leaves both null.
 */
public record UpdateResult(
        ConchOutcome outcome,
        Optional<CredentialDeclarationChange> credentialChange,
        String fromVersion,
        String toVersion
) {
    public UpdateResult {
        credentialChange = credentialChange == null ? Optional.empty() : credentialChange;
    }
}
