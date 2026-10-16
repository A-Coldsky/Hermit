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

import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ShellRegistry;
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
import dev.hermit.crab.store.ShellConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class IdempotencyStripeTest {
    private static final String SHELL = """
            shell: stripe_probe
            version: 1.0.0
            driver: http
            min_crab_version: 1.0.0
            base_url: http://127.0.0.1:9
            title: 条带探针
            description: 幂等条带锁回归夹具
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
    void sameKeyExecutesOnceAndReplaysUnderStripedLocks() throws Exception {
        CrabPaths paths = new CrabPaths(temp);
        try (CrabDatabase database = new CrabDatabase(paths);
             SessionManager sessions = new SessionManager()) {
            byte[] masterKey = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
            CredentialCipher cipher;
            try {
                cipher = new CredentialCipher(masterKey);
            } finally {
                Arrays.fill(masterKey, (byte) 0);
            }
            AccountStore accounts = new AccountStore(database.dataSource());
            ShellRegistry registry = new ShellRegistry();
            registry.put(ShellParser.parse(SHELL));
            accounts.create(Origin.OFFICIAL, "stripe_probe", "alice", Actors.CLI);
            Clock clock = Clock.systemUTC();
            Invoker invoker = new Invoker(
                    registry,
                    accounts,
                    new CredentialVault(database.dataSource(), cipher),
                    new ShellConfigStore(database.dataSource()),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(database.dataSource(), clock),
                    new IdempotencyStore(database.dataSource(), cipher, clock),
                    (shell, action) -> false);
            assertThat(invoker.idempotencyStripes()).isEqualTo(64);
            assertThat(Integer.bitCount(invoker.idempotencyStripes())).isEqualTo(1);
            assertThat(Invoker.IDEMPOTENCY_STRIPES).isEqualTo(invoker.idempotencyStripes());

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
            Thread first = new Thread(() -> call(invoker, principal, "trace-left", left, failure, holding), "idem-left");
            Thread second = new Thread(() -> call(invoker, principal, "trace-right", right, failure, null), "idem-right");
            first.setDaemon(true);
            second.setDaemon(true);
            try {
                first.start();
                assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isNull();
                assertThat(runs).hasValue(1);
                second.start();
                assertThat(awaitBlockedOn(second, first))
                        .as("second call should block on the stripe held by the first call: %s",
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
            assertThat(invoker.idempotencyStripes()).isEqualTo(64);
        }
    }

    private static void call(
            Invoker invoker,
            ApiKeyPrincipal principal,
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
                    Optional.of("same-key"),
                    traceId));
        } catch (Throwable ex) {
            failure.set(ex);
            if (holding != null) {
                holding.countDown();
            }
        }
    }

    private static boolean awaitBlockedOn(Thread waiter, Thread owner) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int streak = 0;
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.getId());
            if (info != null
                    && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == owner.getId()) {
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
