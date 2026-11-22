package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.CrabPaths;

class CrabRuntimeLifecycleTest {
    @TempDir
    Path temp;

    @Test
    @Timeout(15)
    void startBackgroundAfterCloseDoesNotStartScheduler() {
        CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("after-close")),
                CrabConfig.defaults().withListen("127.0.0.1", 0));
        runtime.close();
        runtime.startBackground();
        assertThat(runtime.backgroundRunning()).isFalse();
        assertThatThrownBy(runtime::startServing).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Timeout(30)
    void concurrentStartBackgroundAndCloseNeverLeavesSchedulerRunning() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                CrabRuntime runtime = CrabRuntime.open(
                        new CrabPaths(temp.resolve("race-" + round)),
                        CrabConfig.defaults().withListen("127.0.0.1", 0));
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> starter = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    runtime.startBackground();
                    return null;
                });
                Future<?> closer = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    runtime.close();
                    return null;
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                starter.get(10, TimeUnit.SECONDS);
                closer.get(10, TimeUnit.SECONDS);
                assertThat(runtime.backgroundRunning()).as("round " + round).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
