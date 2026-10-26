package dev.hermit.crab.sync;

import java.util.concurrent.ConcurrentHashMap;

final class ConchFileLocks {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ConchFileLocks() {
    }

    static Object of(String conch) {
        return LOCKS.computeIfAbsent(conch, key -> new Object());
    }
}
