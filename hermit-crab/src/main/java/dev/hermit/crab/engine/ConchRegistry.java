package dev.hermit.crab.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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

    static final Set<String> RESERVED = Set.of("input", "credential", "config", "response", "now");
    private static final Set<String> STEP_TYPES = Set.of("request", "extract", "assert", "set", "script");

    private final AtomicReference<Map<String, Entry>> conches = new AtomicReference<>(Map.of());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** A loaded conch and where it came from. Official and local names do not share a slot. */
    public record Entry(ConchDoc doc, Origin origin) {
    }

    /** Fired after a conch is added, replaced, or removed. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public interface Listener {
        void changed();
    }

    /** Same as {@link #put(ConchDoc, Origin)} with {@link Origin#OFFICIAL}. */
    public void put(ConchDoc doc) {
        put(doc, Origin.OFFICIAL);
    }

    public void put(ConchDoc doc, Origin origin) {
        if (doc == null) {
            throw new IllegalArgumentException("doc");
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin");
        }
        List<ValidationIssue> issues = inspect(doc);
        if (!issues.isEmpty()) {
            throw new ConchRejectedException(issues);
        }
        Map<String, Entry> before = conches.get();
        Map<String, Entry> after = conches.updateAndGet(current -> {
            LinkedHashMap<String, Entry> copy = new LinkedHashMap<>(current);
            copy.put(doc.conch(), new Entry(doc, origin));
            return Collections.unmodifiableMap(copy);
        });
        if (after != before) {
            changed();
        }
    }

    /** Errors that {@link #put(ConchDoc, Origin)} would reject, including reserved-variable writes. */
    public List<ValidationIssue> inspect(ConchDoc doc) {
        if (doc == null) {
            throw new IllegalArgumentException("doc");
        }
        ArrayList<ValidationIssue> issues = new ArrayList<>();
        ValidationResult result = ConchValidator.validate(
                doc, ConchValidator.Context.of(CrabVersion.CURRENT, STEP_TYPES));
        issues.addAll(result.errors());
        reserved(doc, issues);
        return List.copyOf(issues);
    }

    public void remove(String conch) {
        if (conch == null) {
            return;
        }
        Map<String, Entry> before = conches.get();
        Map<String, Entry> after = conches.updateAndGet(current -> {
            if (!current.containsKey(conch)) {
                return current;
            }
            LinkedHashMap<String, Entry> copy = new LinkedHashMap<>(current);
            copy.remove(conch);
            return Collections.unmodifiableMap(copy);
        });
        if (after != before) {
            changed();
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
        LinkedHashMap<String, ConchDoc> docs = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> entry : conches.get().entrySet()) {
            docs.put(entry.getKey(), entry.getValue().doc());
        }
        return Collections.unmodifiableMap(docs);
    }

    public Optional<ConchDoc> find(String conch) {
        return entry(conch).map(Entry::doc);
    }

    public Optional<Origin> origin(String conch) {
        return entry(conch).map(Entry::origin);
    }

    public Optional<Entry> entry(String conch) {
        if (conch == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(conches.get().get(conch));
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
