package dev.hermit.crab.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.validate.ShellValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;

public final class ShellRegistry {

    static final Set<String> RESERVED = Set.of("input", "credential", "config", "response", "now");
    private static final Set<String> STEP_TYPES = Set.of("request", "extract", "assert", "set", "script");

    private final AtomicReference<Map<String, ShellDoc>> shells = new AtomicReference<>(Map.of());

    public void put(ShellDoc doc) {
        if (doc == null) {
            throw new IllegalArgumentException("doc");
        }
        ArrayList<ValidationIssue> issues = new ArrayList<>();
        ValidationResult result = ShellValidator.validate(
                doc, ShellValidator.Context.of(CrabVersion.CURRENT, STEP_TYPES));
        issues.addAll(result.errors());
        reserved(doc, issues);
        if (!issues.isEmpty()) {
            throw new ShellRejectedException(issues);
        }
        shells.updateAndGet(current -> {
            LinkedHashMap<String, ShellDoc> copy = new LinkedHashMap<>(current);
            copy.put(doc.shell(), doc);
            return Collections.unmodifiableMap(copy);
        });
    }

    public void remove(String shell) {
        if (shell == null) {
            return;
        }
        shells.updateAndGet(current -> {
            if (!current.containsKey(shell)) {
                return current;
            }
            LinkedHashMap<String, ShellDoc> copy = new LinkedHashMap<>(current);
            copy.remove(shell);
            return Collections.unmodifiableMap(copy);
        });
    }

    public Map<String, ShellDoc> snapshot() {
        return shells.get();
    }

    public Optional<ShellDoc> find(String shell) {
        if (shell == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(shells.get().get(shell));
    }

    private static void reserved(ShellDoc doc, List<ValidationIssue> issues) {
        Auth auth = doc.auth();
        if (auth != null) {
            reservedSteps(auth.steps(), "auth.steps", issues);
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            reservedSteps(entry.getValue().steps(), "actions." + entry.getKey() + ".steps", issues);
        }
    }

    private static void reservedSteps(List<Step> steps, String path, List<ValidationIssue> issues) {
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if ("script".equals(step.type())) {
                Object into = step.def().get("into");
                if (into instanceof String name && RESERVED.contains(name)) {
                    issues.add(new ValidationIssue(
                            "RESERVED_VARIABLE",
                            path + "[" + i + "].into",
                            "不能写入保留变量 " + name));
                }
                continue;
            }
            if (!"extract".equals(step.type()) && !"set".equals(step.type())) {
                continue;
            }
            for (String name : step.def().keySet()) {
                if (RESERVED.contains(name)) {
                    issues.add(new ValidationIssue(
                            "RESERVED_VARIABLE",
                            path + "[" + i + "]." + name,
                            "不能写入保留变量 " + name));
                }
            }
        }
    }
}
