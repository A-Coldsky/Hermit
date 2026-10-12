package dev.hermit.crab.sync;

import java.util.concurrent.ConcurrentHashMap;

final class ShellFileLocks {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ShellFileLocks() {
    }

    static Object of(String shell) {
        return LOCKS.computeIfAbsent(shell, key -> new Object());
    }
}
