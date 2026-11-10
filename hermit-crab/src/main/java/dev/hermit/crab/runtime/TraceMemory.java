package dev.hermit.crab.runtime;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers trial ids that never became a trace, and trace ids deleted when developer mode closed.
 * The sets are bounded so a long-running process does not keep every id forever.
 */
final class TraceMemory {
    private static final int LIMIT = 256;

    private final Set<String> rejected = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<String> rejectedOrder = new ArrayDeque<>();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<String> deletedOrder = new ArrayDeque<>();

    void reject(String id) {
        add(rejected, rejectedOrder, id);
    }

    void rememberDeleted(List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            add(deleted, deletedOrder, id);
        }
    }

    boolean rejected(String id) {
        return id != null && rejected.contains(id);
    }

    boolean deleted(String id) {
        return id != null && deleted.contains(id);
    }

    private static void add(Set<String> set, ArrayDeque<String> order, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        synchronized (order) {
            if (!set.add(id)) {
                return;
            }
            order.addLast(id);
            while (order.size() > LIMIT) {
                set.remove(order.removeFirst());
            }
        }
    }
}
