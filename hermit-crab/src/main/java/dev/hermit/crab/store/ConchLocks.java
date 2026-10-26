package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serializes account and credential mutations for one conch in this process.
 * Readers still see the default-account change as a single committed transaction.
 */
final class ConchLocks {
    private static final Map<DataSource, ConcurrentHashMap<String, Object>> LOCKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ConchLocks() {
    }

    static Object of(DataSource dataSource, Origin origin, String conch) {
        synchronized (LOCKS) {
            ConcurrentHashMap<String, Object> perDatabase = LOCKS.computeIfAbsent(
                    dataSource, key -> new ConcurrentHashMap<>());
            return perDatabase.computeIfAbsent(origin.name() + "\0" + conch, key -> new Object());
        }
    }
}
