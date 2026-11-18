package dev.hermit.crab.tls;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** 每个 host:port 加种类，10 分钟最多一条 WARNING。只写异常类和根因原文，不带堆栈。 */
final class TransportLog {

    static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Logger LOG = Logger.getLogger(TransportLog.class.getName());
    private static final ConcurrentHashMap<String, Instant> LAST = new ConcurrentHashMap<>();
    private static final AtomicReference<Clock> CLOCK = new AtomicReference<>(Clock.systemUTC());

    private TransportLog() {
    }

    static void warn(TransportFailure.View view, Throwable failure) {
        if (view == null || failure == null) {
            return;
        }
        String host = view.host() == null || view.host().isBlank() ? "unknown" : view.host();
        int port = view.port();
        String key = host + ":" + port + " " + view.kind();
        Instant now = CLOCK.get().instant();
        if (!due(key, now)) {
            return;
        }
        Throwable root = TransportFailure.root(failure);
        String message = root.getMessage() == null ? "" : root.getMessage().strip();
        LOG.warning(view.kind() + " " + key + " " + root.getClass().getName() + ": " + TransportFailure.clip(message));
    }

    private static boolean due(String key, Instant now) {
        if (LAST.size() > 1024) {
            LAST.clear();
        }
        boolean[] emit = {false};
        LAST.compute(key, (ignored, previous) -> {
            if (previous == null || !now.isBefore(previous.plus(WINDOW))) {
                emit[0] = true;
                return now;
            }
            return previous;
        });
        return emit[0];
    }

    static void useClock(Clock clock) {
        CLOCK.set(clock == null ? Clock.systemUTC() : clock);
    }

    static void resetForTests() {
        LAST.clear();
        CLOCK.set(Clock.systemUTC());
    }

    static Set<String> keysForTests() {
        return Set.copyOf(LAST.keySet());
    }
}
