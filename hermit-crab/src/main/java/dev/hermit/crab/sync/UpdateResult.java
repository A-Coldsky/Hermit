package dev.hermit.crab.sync;

import java.util.Optional;

/**
 * A manual update that installed the catalog version. {@code fromVersion} is the version that
 * was current before the switch. {@code toVersion} is the version now installed.
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
