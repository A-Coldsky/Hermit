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
public final class ConchLocks {
    private static final Map<DataSource, ConcurrentHashMap<String, Object>> LOCKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ConchLocks() {
    }

    /** 持有这个包的写锁，在一个事务里执行；锁先于事务取得，和单项写入的顺序一致。 */
    public static <T> T atomic(DataSource dataSource, Origin origin, String conch, java.util.function.Supplier<T> work) {
        synchronized (of(dataSource, origin, conch)) {
            return Db.atomic(dataSource, work);
        }
    }

    public static Object of(DataSource dataSource, Origin origin, String conch) {
        synchronized (LOCKS) {
            ConcurrentHashMap<String, Object> perDatabase = LOCKS.computeIfAbsent(
                    dataSource, key -> new ConcurrentHashMap<>());
            return perDatabase.computeIfAbsent(origin.name() + "\0" + conch, key -> new Object());
        }
    }
}
