package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.MasterKeyProvider;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ConchConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
/** 同一幂等键只执行一次；不同的键互不等待；执行完不留锁（审计 A05）。 */
class IdempotencyInFlightTest {
    private static final String CONCH = """
            conch: stripe_probe
            version: 1.0.0
            driver: http
            min_crab_version: 1.0.0
            base_url: http://127.0.0.1:9
            title: 条带探针
            description: 幂等并发回归夹具
            credentials: {}
            actions:
              save_note:
                title: 保存
                description: 写入一条标记
                method: POST
                steps:
                  - set: { marker: ok }
                output:
                  marker: { type: string, description: 结果 }
                examples:
                  - input: {}
                    output: { marker: ok }
                notes: 仅用于回归测试
            """;

    @TempDir
    Path temp;

    @Test
    void sameKeyExecutesOnceAndReplays() throws Exception {
        CrabPaths paths = new CrabPaths(temp);
        try (CrabDatabase database = new CrabDatabase(paths);
             SessionManager sessions = new SessionManager()) {
            Invoker invoker = invoker(paths, database, sessions);

            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            invoker.addListener((invocation, result) -> {
                if (runs.incrementAndGet() == 1) {
                    holding.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out holding the first execution");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
            });

            ApiKeyPrincipal principal = new ApiKeyPrincipal(
                    "key-1", "stripe", new ApiKeyScope(Set.of(), Set.of(), false));
            AtomicReference<InvokeOutcome> left = new AtomicReference<>();
            AtomicReference<InvokeOutcome> right = new AtomicReference<>();
            Thread first = new Thread(() -> call(invoker, principal, "same-key", "trace-left", left, failure, holding), "idem-left");
            Thread second = new Thread(() -> call(invoker, principal, "same-key", "trace-right", right, failure, null), "idem-right");
            first.setDaemon(true);
            second.setDaemon(true);
            try {
                first.start();
                assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isNull();
                assertThat(runs).hasValue(1);
                second.start();
                assertThat(awaitWaiting(second))
                        .as("second call should wait for the first call with the same key: %s",
                                ManagementFactory.getThreadMXBean().getThreadInfo(second.getId(), 30))
                        .isTrue();
                assertThat(runs).hasValue(1);
            } finally {
                release.countDown();
                first.join(20_000);
                second.join(20_000);
            }

            assertThat(first.isAlive()).isFalse();
            assertThat(second.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            InvokeOutcome executed = left.get();
            InvokeOutcome replayed = right.get();
            assertThat(executed).isNotNull();
            assertThat(replayed).isNotNull();
            assertThat(executed.status()).isEqualTo(200);
            assertThat(executed.replayed()).isFalse();
            assertThat(executed.traceId()).isEqualTo("trace-left");
            assertThat(replayed.replayed()).isTrue();
            assertThat(replayed.status()).isEqualTo(executed.status());
            assertThat(replayed.traceId()).isEqualTo(executed.traceId());
            assertThat(new String(replayed.body(), StandardCharsets.UTF_8))
                    .isEqualTo(new String(executed.body(), StandardCharsets.UTF_8));
            assertThat(runs).hasValue(1);
            assertThat(invoker.idempotencyInFlight()).isZero();
        }
    }

    /** 以前 64 个条带共用监视器，不同的键可能排在一个慢调用后面。 */
    @Test
    void differentKeysDoNotWaitAndNothingIsLeftBehind() throws Exception {
        CrabPaths paths = new CrabPaths(temp);
        try (CrabDatabase database = new CrabDatabase(paths);
             SessionManager sessions = new SessionManager()) {
            Invoker invoker = invoker(paths, database, sessions);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            invoker.addListener((invocation, result) -> {
                if (runs.incrementAndGet() == 1) {
                    holding.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            ApiKeyPrincipal principal = new ApiKeyPrincipal(
                    "key-1", "stripe", new ApiKeyScope(Set.of(), Set.of(), false));
            AtomicReference<InvokeOutcome> slow = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread first = new Thread(() -> call(invoker, principal, "slow-key", "trace-slow", slow, failure, holding), "idem-slow");
            first.setDaemon(true);
            try {
                first.start();
                assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(invoker.idempotencyInFlight()).isEqualTo(1);
                long started = System.nanoTime();
                for (int i = 0; i < 200; i++) {
                    AtomicReference<InvokeOutcome> other = new AtomicReference<>();
                    call(invoker, principal, "other-" + i, "trace-" + i, other, failure, null);
                    assertThat(other.get().status()).isEqualTo(200);
                    assertThat(other.get().replayed()).isFalse();
                }
                assertThat(runs).hasValue(201);
                // 慢调用最多拖 15 秒；任何一个键排在它后面都会超过这个时间。
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);
            } finally {
                release.countDown();
                first.join(20_000);
            }
            assertThat(failure.get()).isNull();
            assertThat(slow.get().status()).isEqualTo(200);
            assertThat(invoker.idempotencyInFlight()).isZero();
        }
    }

    private static Invoker invoker(CrabPaths paths, CrabDatabase database, SessionManager sessions) {
        byte[] masterKey = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
        CredentialCipher cipher;
        try {
            cipher = new CredentialCipher(masterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }
        AccountStore accounts = new AccountStore(database.dataSource());
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse(CONCH));
        accounts.create(Origin.OFFICIAL, "stripe_probe", "alice", Actors.CLI);
        Clock clock = Clock.systemUTC();
        return new Invoker(
                registry,
                accounts,
                new CredentialVault(database.dataSource(), cipher),
                new ConchConfigStore(database.dataSource()),
                new RuleEngine(registry, sessions),
                new CallLogStore(database.dataSource(), clock),
                new IdempotencyStore(database.dataSource(), cipher, clock),
                (conch, action) -> false);
    }

    private static void call(
            Invoker invoker,
            ApiKeyPrincipal principal,
            String key,
            String traceId,
            AtomicReference<InvokeOutcome> target,
            AtomicReference<Throwable> failure,
            CountDownLatch holding
    ) {
        try {
            target.set(invoker.invoke(
                    principal,
                    "stripe_probe",
                    "save_note",
                    Map.of(),
                    Optional.of("alice"),
                    Optional.of(key),
                    traceId));
        } catch (Throwable ex) {
            failure.set(ex);
            if (holding != null) {
                holding.countDown();
            }
        }
    }

    private static boolean awaitWaiting(Thread waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int streak = 0;
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.getId());
            if (info != null && info.getThreadState() == Thread.State.WAITING) {
                streak++;
                if (streak >= 3) {
                    return true;
                }
            } else {
                streak = 0;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
