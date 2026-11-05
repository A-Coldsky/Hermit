package dev.hermit.crab.engine;

import java.util.Map;

/**
 * The draft for the capability call currently running on this thread.
 * {@link RuleEngine#testLogin} does not set it. {@code execute} clears it before listeners run.
 */
final class DevTraceScope {
    private static final ThreadLocal<DevTraceDraft> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> PENDING = new ThreadLocal<>();

    private DevTraceScope() {
    }

    static void begin(DevTraceDraft draft) {
        if (draft == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(draft);
        }
        PENDING.remove();
    }

    static void end() {
        CURRENT.remove();
        PENDING.remove();
    }

    static DevTraceDraft current() {
        return CURRENT.get();
    }

    /** A request that threw before it could return an {@link Outcome}. */
    static void stash(Map<String, Object> piece) {
        if (CURRENT.get() != null && piece != null) {
            PENDING.set(piece);
        }
    }

    static Map<String, Object> takePending() {
        Map<String, Object> piece = PENDING.get();
        PENDING.remove();
        return piece;
    }
}
