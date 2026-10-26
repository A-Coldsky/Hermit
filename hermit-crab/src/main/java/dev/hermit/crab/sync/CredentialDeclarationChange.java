package dev.hermit.crab.sync;

import java.util.Set;

public record CredentialDeclarationChange(String conch, Set<String> addedRequired, Set<String> removed) {

    public CredentialDeclarationChange {
        addedRequired = addedRequired == null ? Set.of() : Set.copyOf(addedRequired);
        removed = removed == null ? Set.of() : Set.copyOf(removed);
    }
}
