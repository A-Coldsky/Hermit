package dev.hermit.crab.engine;

import java.util.Map;

/**
 * The draft for the capability or login call currently running on this thread.
 * {@code execute}, {@link RuleEngine#testLogin}, {@link RuleEngine#continueLogin}, and
 * {@link RuleEngine#resendLogin} open it for local HTTP conches while developer mode is on
 * and clear it before listeners run.
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

    /** Suspends the outer package's dev trace while running a bound authenticator's login or probe. */
    static Suspended suspend() {
        DevTraceDraft draft = CURRENT.get();
        Map<String, Object> pending = PENDING.get();
        CURRENT.remove();
        PENDING.remove();
        return new Suspended(draft, pending);
    }

    record Suspended(DevTraceDraft draft, Map<String, Object> pending) implements AutoCloseable {
        @Override
        public void close() {
            if (draft == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(draft);
            }
            if (pending == null) {
                PENDING.remove();
            } else {
                PENDING.set(pending);
            }
        }
    }
}
