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

    /**
     * 每个包名有本地和正式两个槽，同一时刻只有一份生效。
     * 只有一个槽有文档时它生效；两个槽都有时按 {@code selected}，没有记录时正式那份生效。
     */
    private record State(
            Map<String, ConchDoc> local,
            Map<String, ConchDoc> official,
            Map<String, Origin> selected,
            Map<String, ConchDoc> active
    ) {
        static final State EMPTY = new State(Map.of(), Map.of(), Map.of(), Map.of());

        static State of(Map<String, ConchDoc> local, Map<String, ConchDoc> official, Map<String, Origin> selected) {
            LinkedHashMap<String, ConchDoc> active = new LinkedHashMap<>();
            LinkedHashSet<String> names = new LinkedHashSet<>(official.keySet());
            names.addAll(local.keySet());
            for (String name : names) {
                ConchDoc mine = local.get(name);
                ConchDoc theirs = official.get(name);
                if (mine != null && (theirs == null || selected.get(name) == Origin.LOCAL)) {
                    active.put(name, mine);
                } else {
                    active.put(name, theirs);
                }
            }
            return new State(
                    Collections.unmodifiableMap(new LinkedHashMap<>(local)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(official)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(selected)),
                    Collections.unmodifiableMap(active));
        }

        Map<String, ConchDoc> slot(Origin origin) {
            return origin == Origin.LOCAL ? local : official;
        }

        Origin activeOrigin(String conch) {
            boolean mine = local.containsKey(conch);
            boolean theirs = official.containsKey(conch);
            if (!mine && !theirs) {
                return null;
            }
            return mine && (!theirs || selected.get(conch) == Origin.LOCAL) ? Origin.LOCAL : Origin.OFFICIAL;
        }
    }

    /** 记住同名的两份里哪一份生效。实现要自己持久化。 */
    public interface Selections {
        Map<String, Origin> load();

        /** {@code origin} 为 null 表示这个名字不再有两份。 */
        void save(String conch, Origin origin);
    }

    /** 生效的那份文档和它的来源，一次读出。 */
    public record Active(ConchDoc doc, Origin origin) {
    }

    private static final class Shared {
        final AtomicReference<State> state = new AtomicReference<>(State.EMPTY);
        final List<Listener> listeners = new CopyOnWriteArrayList<>();
        final List<Consumer<String>> removals = new CopyOnWriteArrayList<>();
        final Object writeLock = new Object();
        volatile Selections selections;
    }

    private final Shared shared;
    /** 非 null 时这个对象只看、只写这一个来源的槽。 */
    private final Origin scope;

    public ConchRegistry() {
        this(new Shared(), null);
    }

    private ConchRegistry(Shared shared, Origin scope) {
        this.shared = shared;
        this.scope = scope;
    }

    /**
     * 同一份数据的单来源视图：{@code find}、{@code snapshot}、{@code origin}、{@code remove}
     * 只涉及这个来源的槽，不管它当前是否生效。本地导入和 Reef 同步各拿自己的视图。
     */
    public ConchRegistry scoped(Origin origin) {
        if (origin == null) {
            throw new IllegalArgumentException("origin");
        }
        return new ConchRegistry(shared, origin);
    }

    /** 启动时在载入任何规则包之前调用。 */
    public void bindSelections(Selections selections) {
        synchronized (shared.writeLock) {
            shared.selections = selections;
            if (selections != null) {
                State current = shared.state.get();
                shared.state.set(State.of(current.local(), current.official(), selections.load()));
            }
        }
    }

    /** Fired after a conch is added, replaced, or removed. */
    public void addListener(Listener listener) {
        if (listener != null) {
            shared.listeners.add(listener);
        }
    }

    /** 生效的文档被移出，或同名的生效来源被切换时调用。替换版本不走这里。 */
    public void onRemove(Consumer<String> listener) {
        if (listener != null) {
            shared.removals.add(listener);
        }
    }

    public interface Listener {
        void changed();
    }

    public void put(ConchDoc doc) {
        put(doc, scope == null ? Origin.OFFICIAL : scope);
    }

    public void put(ConchDoc doc, Origin origin) {
        put(doc, origin, false);
    }

    /**
     * {@code allowEmptyMcpActions} 只对 {@link Origin#LOCAL} 生效。正式包始终拒绝空 actions。
     * 同名的另一份已经在时，新放进来的这份不会自动生效。
     */
    public void put(ConchDoc doc, Origin origin, boolean allowEmptyMcpActions) {
        if (doc == null) {
            throw new IllegalArgumentException("doc");
        }
        if (origin == null || (scope != null && origin != scope)) {
            throw new IllegalArgumentException("origin");
        }
        List<ValidationIssue> issues = inspect(doc, origin == Origin.LOCAL && allowEmptyMcpActions);
        if (!issues.isEmpty()) {
            throw new ConchRejectedException(issues);
        }
        String name = doc.conch();
        mutate(name, current -> {
            LinkedHashMap<String, ConchDoc> local = new LinkedHashMap<>(current.local());
            LinkedHashMap<String, ConchDoc> official = new LinkedHashMap<>(current.official());
            LinkedHashMap<String, Origin> selected = new LinkedHashMap<>(current.selected());
            Origin before = current.activeOrigin(name);
            (origin == Origin.LOCAL ? local : official).put(name, doc);
            if (local.containsKey(name) && official.containsKey(name) && !selected.containsKey(name)) {
                selected.put(name, before == null ? origin : before);
            }
            return State.of(local, official, selected);
        });
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

    /** 单来源视图只移出自己的槽；完整视图把两份都移出。 */
    public void remove(String conch) {
        if (conch == null) {
            return;
        }
        mutate(conch, current -> {
            boolean inLocal = scope != Origin.OFFICIAL && current.local().containsKey(conch);
            boolean inOfficial = scope != Origin.LOCAL && current.official().containsKey(conch);
            if (!inLocal && !inOfficial) {
                return current;
            }
            LinkedHashMap<String, ConchDoc> local = new LinkedHashMap<>(current.local());
            LinkedHashMap<String, ConchDoc> official = new LinkedHashMap<>(current.official());
            LinkedHashMap<String, Origin> selected = new LinkedHashMap<>(current.selected());
            if (inLocal) {
                local.remove(conch);
            }
            if (inOfficial) {
                official.remove(conch);
            }
            selected.remove(conch);
            return State.of(local, official, selected);
        });
    }

    /**
     * 让同名两份里的 {@code origin} 那份生效。那个来源没有载入时返回 false，什么都不改。
     */
    public boolean activate(String conch, Origin origin) {
        if (conch == null || origin == null) {
            return false;
        }
        boolean[] found = {false};
        mutate(conch, current -> {
            if (!current.slot(origin).containsKey(conch)) {
                return current;
            }
            found[0] = true;
            if (current.activeOrigin(conch) == origin) {
                return current;
            }
            LinkedHashMap<String, Origin> selected = new LinkedHashMap<>(current.selected());
            selected.put(conch, origin);
            return State.of(current.local(), current.official(), selected);
        });
        return found[0];
    }

    private void mutate(String conch, java.util.function.UnaryOperator<State> change) {
        boolean dropped;
        synchronized (shared.writeLock) {
            State before = shared.state.get();
            State after = change.apply(before);
            if (after == before) {
                return;
            }
            Selections store = shared.selections;
            Origin was = before.selected().get(conch);
            Origin now = after.selected().get(conch);
            if (store != null && was != now) {
                store.save(conch, now);
            }
            shared.state.set(after);
            Origin activeBefore = before.activeOrigin(conch);
            dropped = activeBefore != null && activeBefore != after.activeOrigin(conch);
        }
        if (dropped) {
            removed(conch);
        }
        changed();
    }

    /**
     * 来源。完整视图返回生效那份的来源；单来源视图在自己的槽里有文档时返回自己的来源。
     * 没有载入时为 null。
     */
    public Origin origin(String conch) {
        if (conch == null) {
            return null;
        }
        State current = shared.state.get();
        if (scope != null) {
            return current.slot(scope).containsKey(conch) ? scope : null;
        }
        return current.activeOrigin(conch);
    }

    /** 这个来源的槽里有没有文档，不管它是否生效。 */
    public boolean has(String conch, Origin origin) {
        return conch != null && origin != null && shared.state.get().slot(origin).containsKey(conch);
    }

    /** 这个来源槽里的文档，不管它是否生效。 */
    public Optional<ConchDoc> find(String conch, Origin origin) {
        if (conch == null || origin == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(shared.state.get().slot(origin).get(conch));
    }

    /** 生效的文档和来源取自同一个快照，切换来源时不会拿到一边新一边旧。 */
    public Optional<Active> active(String conch) {
        if (conch == null) {
            return Optional.empty();
        }
        State current = shared.state.get();
        ConchDoc doc = current.active().get(conch);
        return doc == null ? Optional.empty() : Optional.of(new Active(doc, current.activeOrigin(conch)));
    }

    private void removed(String conch) {
        for (Consumer<String> listener : shared.removals) {
            try {
                listener.accept(conch);
            } catch (RuntimeException ex) {
                LOG.warning("conch registry remove listener failed: " + ex.getClass().getName());
            }
        }
    }

    private void changed() {
        for (Listener listener : shared.listeners) {
            try {
                listener.changed();
            } catch (RuntimeException ex) {
                LOG.warning("conch registry listener failed: " + ex.getClass().getName());
            }
        }
    }

    public Map<String, ConchDoc> snapshot() {
        State current = shared.state.get();
        return scope == null ? current.active() : current.slot(scope);
    }

    public Optional<ConchDoc> find(String conch) {
        if (conch == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot().get(conch));
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
