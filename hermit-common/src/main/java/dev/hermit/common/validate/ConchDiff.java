package dev.hermit.common.validate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.CapabilityGroups;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.GroupDef;
import dev.hermit.common.model.McpTool;
import dev.hermit.common.model.Step;

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
        List<String> newRequiredCredentials,
        List<ToolChange> toolChanges,
        List<String> addedScripts,
        List<String> removedScripts,
        List<String> changedScripts,
        List<String> addedGroups,
        List<String> removedGroups,
        List<String> changedGroups,
        List<ActionGroupChange> actionGroupChanges
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
        toolChanges = List.copyOf(toolChanges);
        addedScripts = List.copyOf(addedScripts);
        removedScripts = List.copyOf(removedScripts);
        changedScripts = List.copyOf(changedScripts);
        addedGroups = addedGroups == null ? List.of() : List.copyOf(addedGroups);
        removedGroups = removedGroups == null ? List.of() : List.copyOf(removedGroups);
        changedGroups = changedGroups == null ? List.of() : List.copyOf(changedGroups);
        actionGroupChanges = actionGroupChanges == null ? List.of() : List.copyOf(actionGroupChanges);
    }

    public static ConchDiff between(ConchDoc oldDoc, ConchDoc newDoc) {
        Objects.requireNonNull(oldDoc, "oldDoc");
        Objects.requireNonNull(newDoc, "newDoc");
        ScriptDiff scripts = scriptDiff(oldDoc, newDoc);
        GroupDiff groups = groupDiff(oldDoc, newDoc);
        List<String> addedActions = new ArrayList<>();
        List<String> removedActions = new ArrayList<>();
        List<ActionChange> changedActions = new ArrayList<>();
        List<ActionGroupChange> actionGroupChanges = new ArrayList<>();
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
            if (!Objects.equals(previous.group(), current.group())) {
                actionGroupChanges.add(new ActionGroupChange(entry.getKey(), previous.group(), current.group()));
            }
            boolean input = !Objects.equals(previous.input(), current.input());
            boolean output = !Objects.equals(previous.output(), current.output());
            boolean steps = !Objects.equals(previous.steps(), current.steps())
                    || referencesScript(previous.steps(), scripts.affectingSteps())
                    || referencesScript(current.steps(), scripts.affectingSteps());
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
                addedConfig, removedConfig, defaultChanges, newRequired,
                toolChanges(oldDoc, newDoc),
                scripts.added(), scripts.removed(), scripts.changed(),
                groups.added(), groups.removed(), groups.changed(), actionGroupChanges);
    }

    private static GroupDiff groupDiff(ConchDoc oldDoc, ConchDoc newDoc) {
        Map<String, GroupDef> oldGroups = CapabilityGroups.flatten(oldDoc);
        Map<String, GroupDef> newGroups = CapabilityGroups.flatten(newDoc);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, GroupDef> entry : oldGroups.entrySet()) {
            GroupDef next = newGroups.get(entry.getKey());
            if (next == null) {
                removed.add(entry.getKey());
            } else if (!Objects.equals(entry.getValue().title(), next.title())
                    || !Objects.equals(entry.getValue().description(), next.description())) {
                changed.add(entry.getKey());
            }
        }
        for (String path : newGroups.keySet()) {
            if (!oldGroups.containsKey(path)) {
                added.add(path);
            }
        }
        return new GroupDiff(added, removed, changed);
    }

    private record GroupDiff(List<String> added, List<String> removed, List<String> changed) {
    }

    /**
     * 名字按各自文档里的顺序。改过或删掉的库会影响仍引用它们的能力步骤；
     * 新增的库只有在步骤 {@code use} 本身变了时才算步骤变化。
     */
    private static ScriptDiff scriptDiff(ConchDoc oldDoc, ConchDoc newDoc) {
        Map<String, String> oldScripts = oldDoc.scripts() == null ? Map.of() : oldDoc.scripts();
        Map<String, String> newScripts = newDoc.scripts() == null ? Map.of() : newDoc.scripts();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : oldScripts.entrySet()) {
            if (!newScripts.containsKey(entry.getKey())) {
                removed.add(entry.getKey());
            } else if (!Objects.equals(entry.getValue(), newScripts.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (String name : newScripts.keySet()) {
            if (!oldScripts.containsKey(name)) {
                added.add(name);
            }
        }
        return new ScriptDiff(added, removed, changed);
    }

    private static boolean referencesScript(List<Step> steps, Set<String> names) {
        if (steps == null || names.isEmpty()) {
            return false;
        }
        for (Step step : FlowSteps.flat(steps)) {
            if (!"script".equals(step.type()) || step.def() == null) {
                continue;
            }
            Object use = step.def().get("use");
            if (!(use instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof String name && names.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record ScriptDiff(List<String> added, List<String> removed, List<String> changed) {
        private Set<String> affectingSteps() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            names.addAll(changed);
            names.addAll(removed);
            return names;
        }
    }

    private static List<ToolChange> toolChanges(ConchDoc oldDoc, ConchDoc newDoc) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(oldDoc.actions().keySet());
        names.addAll(newDoc.actions().keySet());
        ArrayList<ToolChange> changes = new ArrayList<>();
        for (String name : names) {
            ToolChange change = toolChange(name, oldDoc.actions().get(name), newDoc.actions().get(name));
            if (change != null) {
                changes.add(change);
            }
        }
        return changes;
    }

    private static ToolChange toolChange(String name, Action previous, Action current) {
        boolean wasTool = previous != null && previous.tool() != null;
        boolean isTool = current != null && current.tool() != null;
        if (!wasTool && !isTool) {
            return null;
        }
        if (!wasTool) {
            return toolView(name, ToolChange.ADDED, null, current);
        }
        if (!isTool) {
            return toolView(name, ToolChange.REMOVED, previous, null);
        }
        if (sameTool(previous, current)) {
            return null;
        }
        return toolView(name, ToolChange.CHANGED, previous, current);
    }

    private static boolean sameTool(Action previous, Action current) {
        return Objects.equals(upstreamName(previous), upstreamName(current))
                && Objects.equals(schemaSha(previous), schemaSha(current))
                && Objects.equals(previous.description(), current.description())
                && previous.mcp().readOnly() == current.mcp().readOnly()
                && previous.effectiveDestructive() == current.effectiveDestructive()
                && previous.effectiveConfirm() == current.effectiveConfirm();
    }

    private static ToolChange toolView(String name, String kind, Action previous, Action current) {
        return new ToolChange(
                name,
                kind,
                previous == null ? null : upstreamName(previous),
                current == null ? null : upstreamName(current),
                previous == null ? null : schemaSha(previous),
                current == null ? null : schemaSha(current),
                previous == null ? null : previous.description(),
                current == null ? null : current.description(),
                previous == null ? null : previous.mcp().readOnly(),
                current == null ? null : current.mcp().readOnly(),
                previous == null ? null : previous.effectiveDestructive(),
                current == null ? null : current.effectiveDestructive(),
                previous == null ? null : previous.effectiveConfirm(),
                current == null ? null : current.effectiveConfirm());
    }

    private static String upstreamName(Action action) {
        McpTool tool = action.tool();
        return tool == null ? null : tool.name();
    }

    private static String schemaSha(Action action) {
        McpTool tool = action.tool();
        if (tool == null) {
            return null;
        }
        return ToolSnapshots.schemaSha256(tool.inputSchema(), tool.outputSchema());
    }
}
