package dev.hermit.crab.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.store.Origin;

public final class ConchRegistry {
    private static final Logger LOG = Logger.getLogger(ConchRegistry.class.getName());

    static final Set<String> RESERVED = Set.of("input", "credential", "config", "response", "now", "login");
    private static final Set<String> STEP_TYPES = Set.of("request", "extract", "assert", "set", "script", "ask");

    private final AtomicReference<Map<String, ConchDoc>> conches = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, Origin>> origins = new AtomicReference<>(Map.of());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> removals = new CopyOnWriteArrayList<>();

    /** Fired after a conch is added, replaced, or removed. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 文档确实被移出注册表时调用。替换版本不走这里。 */
    public void onRemove(Consumer<String> listener) {
        if (listener != null) {
            removals.add(listener);
        }
    }

    public interface Listener {
        void changed();
    }

    public void put(ConchDoc doc) {
        put(doc, Origin.OFFICIAL);
    }

    /**
     * Origin is written before the document so a reader that sees the document also sees
     * {@link Origin#LOCAL} instead of the official fallback.
     */
    public void put(ConchDoc doc, Origin origin) {
        put(doc, origin, false);
    }

    /**
     * {@code allowEmptyMcpActions} 只对 {@link Origin#LOCAL} 生效。正式包始终拒绝空 actions。
     */
    public void put(ConchDoc doc, Origin origin, boolean allowEmptyMcpActions) {
        if (doc == null) {
            throw new IllegalArgumentException("doc");
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin");
        }
        List<ValidationIssue> issues = inspect(doc, origin == Origin.LOCAL && allowEmptyMcpActions);
        if (!issues.isEmpty()) {
            throw new ConchRejectedException(issues);
        }
        Map<String, ConchDoc> docsBefore = conches.get();
        Map<String, Origin> originsBefore = origins.get();
        origins.updateAndGet(current -> {
            LinkedHashMap<String, Origin> copy = new LinkedHashMap<>(current);
            copy.put(doc.conch(), origin);
            return Collections.unmodifiableMap(copy);
        });
        conches.updateAndGet(current -> {
            LinkedHashMap<String, ConchDoc> copy = new LinkedHashMap<>(current);
            copy.put(doc.conch(), doc);
            return Collections.unmodifiableMap(copy);
        });
        if (conches.get() != docsBefore || origins.get() != originsBefore) {
            changed();
        }
    }

    /** Errors that {@link #put(ConchDoc, Origin)} would reject, including reserved-variable writes. */
    public List<ValidationIssue> inspect(ConchDoc doc) {
        return inspect(doc, false);
    }

    /**
     * {@code allowEmptyMcpActions} 把空的 mcp {@code actions} 从错误降为警告。
     * 调用方只在本地包且调试开关打开时传入 true。
     */
    public List<ValidationIssue> inspect(ConchDoc doc, boolean allowEmptyMcpActions) {
        if (doc == null) {
            throw new IllegalArgumentException("doc");
        }
        ArrayList<ValidationIssue> issues = new ArrayList<>();
        ConchValidator.Context context = allowEmptyMcpActions
                ? ConchValidator.Context.allowingEmptyMcpActions(CrabVersion.CURRENT, STEP_TYPES)
                : ConchValidator.Context.of(CrabVersion.CURRENT, STEP_TYPES);
        ValidationResult result = ConchValidator.validate(doc, context);
        issues.addAll(result.errors());
        reserved(doc, issues);
        dedupe(issues);
        return List.copyOf(issues);
    }

    public void remove(String conch) {
        if (conch == null) {
            return;
        }
        Map<String, ConchDoc> docsBefore = conches.get();
        Map<String, Origin> originsBefore = origins.get();
        conches.updateAndGet(current -> {
            if (!current.containsKey(conch)) {
                return current;
            }
            LinkedHashMap<String, ConchDoc> copy = new LinkedHashMap<>(current);
            copy.remove(conch);
            return Collections.unmodifiableMap(copy);
        });
        origins.updateAndGet(current -> {
            if (!current.containsKey(conch)) {
                return current;
            }
            LinkedHashMap<String, Origin> copy = new LinkedHashMap<>(current);
            copy.remove(conch);
            return Collections.unmodifiableMap(copy);
        });
        if (conches.get() != docsBefore || origins.get() != originsBefore) {
            removed(conch);
            changed();
        }
    }

    /**
     * Stored origin, or {@link Origin#OFFICIAL} when a document is present without one.
     * Null when the name is not loaded.
     */
    public Origin origin(String conch) {
        if (conch == null) {
            return null;
        }
        Origin stored = origins.get().get(conch);
        if (stored != null) {
            return stored;
        }
        if (conches.get().containsKey(conch)) {
            return Origin.OFFICIAL;
        }
        return null;
    }

    private void removed(String conch) {
        for (Consumer<String> listener : removals) {
            try {
                listener.accept(conch);
            } catch (RuntimeException ex) {
                LOG.warning("conch registry remove listener failed: " + ex.getClass().getName());
            }
        }
    }

    private void changed() {
        for (Listener listener : listeners) {
            try {
                listener.changed();
            } catch (RuntimeException ex) {
                LOG.warning("conch registry listener failed: " + ex.getClass().getName());
            }
        }
    }

    public Map<String, ConchDoc> snapshot() {
        return conches.get();
    }

    public Optional<ConchDoc> find(String conch) {
        if (conch == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(conches.get().get(conch));
    }

    /** 校验器和注册表都会报 {@code login}，本地导入只保留一条。 */
    private static void dedupe(List<ValidationIssue> issues) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        issues.removeIf(issue -> !seen.add(issue.code() + "\0" + issue.path() + "\0" + issue.message()));
    }

    private static void reserved(ConchDoc doc, List<ValidationIssue> issues) {
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
            if ("ask".equals(step.type())) {
                Object into = step.def().get("into");
                if (into instanceof String name && RESERVED.contains(name)) {
                    issues.add(new ValidationIssue(
                            "RESERVED_VARIABLE",
                            path + "[" + i + "].ask.into",
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
