package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.store.Origin;

/** auth.concurrent_reads：同一账号的只读能力并行，登录和其它能力仍然独占。上游是本机的 HttpServer。 */
class ConcurrentReadsEngineTest {
    private HttpServer server;
    private ExecutorService serverPool;
    private ExecutorService callers;
    private String base;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private volatile String valid = "none";
    private volatile Runnable onRejected;
    private final Object gate = new Object();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        callers = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stop() {
        onRejected = null;
        server.stop(0);
        serverPool.shutdownNow();
        callers.shutdownNow();
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/login")) {
            valid = "s" + logins.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "sid=" + valid + "; Path=/");
            reply(exchange, 200, "{\"token\":\"tok-" + valid + "\"}");
            return;
        }
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || !cookie.contains("sid=" + valid)) {
            rejected.incrementAndGet();
            Runnable hook = onRejected;
            if (hook != null) {
                hook.run();
            }
            reply(exchange, 401, "{}");
            return;
        }
        int now = inFlight.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
        // 等第二个请求进来，等不到就放行：串行时每个请求各等满这段时间。
        synchronized (gate) {
            gate.notifyAll();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
            while (inFlight.get() < 2 && peak.get() < 2 && System.nanoTime() < until) {
                try {
                    gate.wait(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        inFlight.decrementAndGet();
        reply(exchange, 200, "{\"value\":\"ok\"}");
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void readOnlyCallsOfOneAccountOverlapOnceLoggedIn() throws Exception {
        try (Fixture fixture = new Fixture(conch(true, true, ""))) {
            assertThat(fixture.call().success()).isTrue();
            peak.set(0);
            List<ExecutionResult> results = fixture.both();
            assertThat(results).allMatch(ExecutionResult::success);
            assertThat(peak.get()).as("两个只读调用同时在上游").isEqualTo(2);
            assertThat(logins.get()).isEqualTo(1);
        }
    }

    @Test
    void staysSerialWithoutTheSwitchOrForOtherActions() throws Exception {
        try (Fixture off = new Fixture(conch(false, true, ""))) {
            assertThat(off.call().success()).isTrue();
            peak.set(0);
            assertThat(off.both()).allMatch(ExecutionResult::success);
            assertThat(peak.get()).as("没打开 concurrent_reads").isEqualTo(1);
        }
        try (Fixture writes = new Fixture(conch(true, false, ""))) {
            assertThat(writes.call().success()).isTrue();
            peak.set(0);
            assertThat(writes.both()).allMatch(ExecutionResult::success);
            assertThat(peak.get()).as("不是只读能力").isEqualTo(1);
        }
    }

    @Test
    void firstCallsLogInOnceEvenWhenTheyArriveTogether() throws Exception {
        try (Fixture fixture = new Fixture(conch(true, true, ""))) {
            assertThat(fixture.both()).allMatch(ExecutionResult::success);
            assertThat(logins.get()).as("只登录一次").isEqualTo(1);
        }
    }

    @Test
    void lostSessionSeenByParallelCallsLogsInOnce() throws Exception {
        try (Fixture fixture = new Fixture(conch(true, true, ""))) {
            assertThat(fixture.call().success()).isTrue();
            valid = "revoked";
            List<ExecutionResult> results = fixture.both();
            assertThat(results).allMatch(ExecutionResult::success);
            assertThat(rejected.get()).isGreaterThanOrEqualTo(1);
            assertThat(logins.get()).as("两个调用都发现失效，只重新登录一次").isEqualTo(2);
        }
    }

    @Test
    void loginValueMissingUnderTheSharedLockFallsBackToTheExclusivePath() throws Exception {
        // 会话在、登录变量不在（比如重启后快照没了）：共享锁下不登录，改拿独占锁重新登录再跑。
        try (Fixture fixture = new Fixture(conch(true, true, "{{login.token}}"))) {
            ExecutionResult first = fixture.call();
            assertThat(first.success()).as(String.valueOf(first.error())).isTrue();
            fixture.sessions.replaceSnapshotForTest(fixture.key());
            ExecutionResult again = fixture.call();
            assertThat(again.success()).as(String.valueOf(again.error())).isTrue();
            assertThat(logins.get()).isEqualTo(2);
        }
    }

    @Test
    void loginValueReadUnderTheSharedLockStaysMaskedAfterTheSessionIsDropped() throws Exception {
        String yaml = conch(true, true, "")
                .replace("idempotent: true", "idempotent: false")
                .replace("path: /data", "path: \"/data/{{login.token}}\"");
        try (Fixture fixture = new Fixture(yaml)) {
            assertThat(fixture.call().success()).isTrue();
            valid = "revoked";
            ExecutionResult result = fixture.call();
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(dev.hermit.common.error.ErrorCode.SESSION_EXPIRED);
            assertThat(result.toString()).contains("/data").doesNotContain("tok-s1");
            assertThat(fixture.trace).contains("/data").doesNotContain("tok-s1");
        }
    }

    @Test
    void exclusiveLockTimeoutAfterSharedAttemptKeepsLoginSecretMasked() throws Exception {
        peak.set(2);
        String yaml = conch(true, true, "")
                .replace("path: /data", "path: \"/data/{{login.token}}\"");
        try (Fixture fixture = new Fixture(yaml, Duration.ofMillis(500))) {
            assertThat(fixture.call().success()).isTrue();
            valid = "revoked";
            CountDownLatch lockerStarted = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            AtomicReference<Thread> lockerRef = new AtomicReference<>();
            AtomicReference<Throwable> lockerError = new AtomicReference<>();
            onRejected = () -> {
                onRejected = null;
                Thread locker = new Thread(() -> {
                    lockerStarted.countDown();
                    try {
                        if (fixture.sessions.tryLock(fixture.key(), System.nanoTime() + TimeUnit.SECONDS.toNanos(10))) {
                            try {
                                fixture.sessions.markLoggedOut(fixture.key());
                                releaseLock.await(10, TimeUnit.SECONDS);
                            } finally {
                                fixture.sessions.unlock(fixture.key());
                            }
                        }
                    } catch (Throwable ex) {
                        lockerError.set(ex);
                    }
                });
                lockerRef.set(locker);
                locker.start();
                waitUntilWaiting(locker, lockerStarted);
            };
            ExecutionResult result;
            try {
                result = fixture.call();
            } finally {
                releaseLock.countDown();
                Thread locker = lockerRef.get();
                if (locker != null) {
                    locker.join(5_000);
                }
            }
            assertThat(lockerError.get()).isNull();
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(fixture.trace).contains("/data").doesNotContain("tok-s1");
        }
    }

    @Test
    void exclusiveLockInterruptAfterSharedAttemptKeepsLoginSecretMasked() throws Exception {
        peak.set(2);
        String yaml = conch(true, true, "")
                .replace("path: /data", "path: \"/data/{{login.token}}\"");
        try (Fixture fixture = new Fixture(yaml)) {
            assertThat(fixture.call().success()).isTrue();
            valid = "revoked";
            CountDownLatch lockerStarted = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            AtomicReference<Thread> callerRef = new AtomicReference<>();
            AtomicReference<Thread> lockerRef = new AtomicReference<>();
            AtomicReference<Throwable> lockerError = new AtomicReference<>();
            AtomicReference<ExecutionResult> resultRef = new AtomicReference<>();
            onRejected = () -> {
                onRejected = null;
                Thread locker = new Thread(() -> {
                    lockerStarted.countDown();
                    try {
                        if (fixture.sessions.tryLock(fixture.key(), System.nanoTime() + TimeUnit.SECONDS.toNanos(10))) {
                            try {
                                fixture.sessions.markLoggedOut(fixture.key());
                                Thread caller = callerRef.get();
                                if (caller != null) {
                                    caller.interrupt();
                                }
                                releaseLock.await(10, TimeUnit.SECONDS);
                            } finally {
                                fixture.sessions.unlock(fixture.key());
                            }
                        }
                    } catch (Throwable ex) {
                        lockerError.set(ex);
                    }
                });
                lockerRef.set(locker);
                locker.start();
                waitUntilWaiting(locker, lockerStarted);
            };
            Thread caller = new Thread(() -> {
                callerRef.set(Thread.currentThread());
                resultRef.set(fixture.call());
                Thread.interrupted();
            });
            try {
                caller.start();
                caller.join(10_000);
            } finally {
                releaseLock.countDown();
                Thread locker = lockerRef.get();
                if (locker != null) {
                    locker.join(5_000);
                }
            }
            assertThat(lockerError.get()).isNull();
            ExecutionResult result = resultRef.get();
            assertThat(result).isNotNull();
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(fixture.trace).contains("/data").doesNotContain("tok-s1");
        }
    }

    private static void waitUntilWaiting(Thread thread, CountDownLatch started) {
        try {
            started.await(2, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < until) {
                Thread.State state = thread.getState();
                if (state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING) {
                    return;
                }
                Thread.sleep(5);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void exclusiveWorkWaitsForSharedHoldersAndTheOtherWayRound() throws Exception {
        try (Fixture fixture = new Fixture(conch(true, true, ""))) {
            SessionKey key = fixture.key();
            SessionManager sessions = fixture.sessions;
            long soon = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            assertThat(sessions.tryLockShared(key, soon)).isTrue();
            try {
                assertThat(callers.submit(() -> {
                    boolean got = sessions.tryLockShared(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
                    if (got) {
                        sessions.unlockShared(key);
                    }
                    return got;
                }).get()).as("另一个只读调用进得来").isTrue();
                assertThat(callers.submit(() -> {
                    boolean got = sessions.tryLock(key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
                    if (got) {
                        sessions.unlock(key);
                    }
                    return got;
                }).get()).as("独占的要等").isFalse();
            } finally {
                sessions.unlockShared(key);
            }
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))).isTrue();
            try {
                assertThat(callers.submit(() -> sessions.tryLockShared(
                        key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200))).get())
                        .as("独占期间只读调用也等").isFalse();
            } finally {
                sessions.unlock(key);
            }
        }
    }

    private static String conch(boolean concurrent, boolean readOnly, String header) {
        return """
                conch: reads_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                auth:
                  %s
                  steps:
                    - request: {method: POST, path: /login, form: {u: "{{credential.username}}", p: "{{credential.password}}"}}
                    - extract:
                        token: {jsonpath: "$.token"}
                actions:
                  run:
                    title: 能力
                    description: 说明
                    idempotent: true
                    mcp: {expose: true, read_only: %s}
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, path: /data%s}
                      - extract:
                          value: {jsonpath: "$.value"}
                """.formatted(
                concurrent ? "concurrent_reads: true" : "cookies: {}",
                readOnly,
                header.isEmpty() ? "" : ", headers: {X-Token: \"" + header + "\"}");
    }

    private final class Fixture implements AutoCloseable {
        private final SessionManager sessions;
        private final RuleEngine engine;
        private volatile String trace;

        private Fixture(String yaml) {
            this(yaml, Duration.ofSeconds(20));
        }

        private Fixture(String yaml, Duration capabilityTimeout) {
            ConchDoc doc = ConchParser.parse(yaml);
            ValidationResult checked = ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT));
            assertThat(checked.errors()).isEmpty();
            EngineSettings settings = new EngineSettings(capabilityTimeout, Duration.ofMinutes(10), 3);
            sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC());
            ConchRegistry registry = new ConchRegistry();
            registry.put(doc, Origin.LOCAL);
            engine = new RuleEngine(registry, sessions, settings);
            engine.bindDevTraceSink(new DevTraceSink() {
                @Override
                public DevTraceDraft open(ExecutionRequest request) {
                    return new DevTraceDraft(java.time.Instant.parse("2026-11-18T00:00:00Z"));
                }

                @Override
                public void save(ExecutionRequest request, java.time.Instant expiresAt, String body) {
                    trace = body;
                }
            });
        }

        private SessionKey key() {
            return new SessionKey(Origin.LOCAL, "reads_conch", "alice");
        }

        private ExecutionResult call() {
            return engine.execute(EngineTestSupport.request(
                    Origin.LOCAL, "reads_conch", "run", "alice", Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base)));
        }

        private List<ExecutionResult> both() throws Exception {
            List<Future<ExecutionResult>> futures = new ArrayList<>();
            futures.add(callers.submit(this::call));
            futures.add(callers.submit(this::call));
            List<ExecutionResult> results = new ArrayList<>();
            for (Future<ExecutionResult> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        }

        @Override
        public void close() {
            sessions.close();
        }
    }
}
