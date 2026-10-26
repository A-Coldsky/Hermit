package dev.hermit.crab.sync;

import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.ConchDoc;

final class CredentialChanges {

    private CredentialChanges() {
    }

    static Optional<CredentialDeclarationChange> between(ConchDoc previous, ConchDoc next) {
        if (previous == null || next == null) {
            return Optional.empty();
        }
        Map<String, CredentialField> oldFields = previous.credentials() == null ? Map.of() : previous.credentials();
        Map<String, CredentialField> newFields = next.credentials() == null ? Map.of() : next.credentials();
        TreeSet<String> addedRequired = new TreeSet<>();
        TreeSet<String> removed = new TreeSet<>();
        for (Map.Entry<String, CredentialField> entry : newFields.entrySet()) {
            if (!entry.getValue().required()) {
                continue;
            }
            CredentialField old = oldFields.get(entry.getKey());
            if (old == null || !old.required()) {
                addedRequired.add(entry.getKey());
            }
        }
        for (String name : oldFields.keySet()) {
            if (!newFields.containsKey(name)) {
                removed.add(name);
            }
        }
        if (addedRequired.isEmpty() && removed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CredentialDeclarationChange(next.conch(), addedRequired, removed));
    }
}
