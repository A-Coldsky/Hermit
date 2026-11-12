package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.engine.ConchRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 同步卡在 Reef 上时，控制台的只读查询限时等待，不陪着等到 Reef 超时。 */
@Timeout(60)
class ConchSyncDisplayTest {
    @TempDir
    Path temp;

    @Test
    void displayReadsDoNotWaitForAStuckSync() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer reef = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reef.createContext("/", exchange -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        reef.start();
        try (OpenCrab crab = OpenCrab.open(temp)) {
            SyncSettings settings = new SyncSettings(
                    URI.create("http://127.0.0.1:" + reef.getAddress().getPort()),
                    Optional.of("display-test-token"),
                    Ed25519Keys.generate().getPublic(),
                    Duration.ofMinutes(5),
                    UUID.randomUUID());
            ConchSync sync = new ConchSync(settings, crab.paths, new ConchRegistry(), crab.vault, crab.dataSource);
            Thread running = new Thread(() -> {
                try {
                    sync.syncOnce();
                } catch (RuntimeException expected) {
                    // 503 之后同步失败，这里只关心它拿着锁的那段时间。
                }
            });
            running.start();
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                sync.catalog();
                sync.disabledRules();
                sync.previousState("legacy_oa");
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                long budget = ConchSync.DISPLAY_WAIT.toMillis() * 3 + 2_000;
                assertThat(elapsed).isLessThan(budget);
                assertThat(running.isAlive()).isTrue();
            } finally {
                release.countDown();
                running.join(30_000);
            }
        } finally {
            reef.stop(0);
        }
    }
}
