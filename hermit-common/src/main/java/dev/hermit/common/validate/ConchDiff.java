package dev.hermit.common.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.ConchDoc;

public record ConchDiff(
        List<String> addedActions,
        List<String> removedActions,
        List<ActionChange> changedActions,
        List<String> addedCredentials,
        List<String> removedCredentials,
        List<CredentialRequiredChange> credentialRequiredChanges,
        List<String> addedConfig,
        List<String> removedConfig,
        List<ConfigDefaultChange> configDefaultChanges,
        List<String> newRequiredCredentials
) {
    public ConchDiff {
        addedActions = List.copyOf(addedActions);
        removedActions = List.copyOf(removedActions);
        changedActions = List.copyOf(changedActions);
        addedCredentials = List.copyOf(addedCredentials);
        removedCredentials = List.copyOf(removedCredentials);
        credentialRequiredChanges = List.copyOf(credentialRequiredChanges);
        addedConfig = List.copyOf(addedConfig);
        removedConfig = List.copyOf(removedConfig);
        configDefaultChanges = List.copyOf(configDefaultChanges);
        newRequiredCredentials = List.copyOf(newRequiredCredentials);
    }

    public static ConchDiff between(ConchDoc oldDoc, ConchDoc newDoc) {
        Objects.requireNonNull(oldDoc, "oldDoc");
        Objects.requireNonNull(newDoc, "newDoc");
        List<String> addedActions = new ArrayList<>();
        List<String> removedActions = new ArrayList<>();
        List<ActionChange> changedActions = new ArrayList<>();
        for (String name : oldDoc.actions().keySet()) {
            if (!newDoc.actions().containsKey(name)) {
                removedActions.add(name);
            }
        }
        for (Map.Entry<String, Action> entry : newDoc.actions().entrySet()) {
            Action previous = oldDoc.actions().get(entry.getKey());
            if (previous == null) {
                addedActions.add(entry.getKey());
                continue;
            }
            Action current = entry.getValue();
            boolean input = !Objects.equals(previous.input(), current.input());
            boolean output = !Objects.equals(previous.output(), current.output());
            boolean steps = !Objects.equals(previous.steps(), current.steps());
            boolean method = previous.effectiveMethod() != current.effectiveMethod();
            boolean mcp = !Objects.equals(previous.mcp(), current.mcp());
            if (input || output || steps || method || mcp) {
                changedActions.add(new ActionChange(entry.getKey(), input, output, steps, method, mcp));
            }
        }

        Map<String, CredentialField> oldCreds = oldDoc.credentials() == null ? Map.of() : oldDoc.credentials();
        Map<String, CredentialField> newCreds = newDoc.credentials() == null ? Map.of() : newDoc.credentials();
        List<String> addedCredentials = new ArrayList<>();
        List<String> removedCredentials = new ArrayList<>();
        List<CredentialRequiredChange> requiredChanges = new ArrayList<>();
        List<String> newRequired = new ArrayList<>();
        for (String name : oldCreds.keySet()) {
            if (!newCreds.containsKey(name)) {
                removedCredentials.add(name);
            }
        }
        for (Map.Entry<String, CredentialField> entry : newCreds.entrySet()) {
            CredentialField previous = oldCreds.get(entry.getKey());
            CredentialField current = entry.getValue();
            if (previous == null) {
                addedCredentials.add(entry.getKey());
            } else if (previous.required() != current.required()) {
                requiredChanges.add(new CredentialRequiredChange(
                        entry.getKey(), previous.required(), current.required()));
            }
            if (current.required() && (previous == null || !previous.required())) {
                newRequired.add(entry.getKey());
            }
        }

        Map<String, ConfigField> oldConfig = oldDoc.config();
        Map<String, ConfigField> newConfig = newDoc.config();
        List<String> addedConfig = new ArrayList<>();
        List<String> removedConfig = new ArrayList<>();
        List<ConfigDefaultChange> defaultChanges = new ArrayList<>();
        for (String name : oldConfig.keySet()) {
            if (!newConfig.containsKey(name)) {
                removedConfig.add(name);
            }
        }
        for (Map.Entry<String, ConfigField> entry : newConfig.entrySet()) {
            ConfigField previous = oldConfig.get(entry.getKey());
            if (previous == null) {
                addedConfig.add(entry.getKey());
            } else if (!Objects.equals(previous.defaultValue(), entry.getValue().defaultValue())) {
                defaultChanges.add(new ConfigDefaultChange(
                        entry.getKey(), previous.defaultValue(), entry.getValue().defaultValue()));
            }
        }

        return new ConchDiff(
                addedActions, removedActions, changedActions,
                addedCredentials, removedCredentials, requiredChanges,
                addedConfig, removedConfig, defaultChanges, newRequired);
    }
}
