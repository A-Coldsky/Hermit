package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionSuccessTest {

    @Test
    void concurrentFirstSuccessesLeaveOneRowAndNoError(@TempDir Path temp) throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp)) {
            SyncStore store = new SyncStore(crab.dataSource);
            int callers = 16;
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                for (int round = 0; round < 20; round++) {
                    String version = "1.0." + round;
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < callers; i++) {
                        futures.add(pool.submit(() -> {
                            start.await();
                            store.recordSuccess("legacy_oa", version, Instant.parse("2026-06-01T00:00:00Z"));
                            return null;
                        }));
                    }
                    start.countDown();
                    for (Future<?> future : futures) {
                        future.get(30, TimeUnit.SECONDS);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(rows(crab.dataSource)).hasSize(20);
        }
    }

    @Test
    void repeatedSuccessOfOneVersionWritesAtMostOncePerInterval(@TempDir Path temp) throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp)) {
            AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));
            Clock clock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneId.of("UTC");
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            VersionSuccess recorder = new VersionSuccess(crab.dataSource, clock);
            SyncStore store = new SyncStore(crab.dataSource);

            recorder.record("legacy_oa", "1.0.0");
            now.set(now.get().plusSeconds(30));
            recorder.record("legacy_oa", "1.0.0");
            assertThat(rows(crab.dataSource)).containsExactly("legacy_oa 1.0.0 2026-06-01T00:00:00Z");

            // 换版本不等间隔，回到旧版本也一样：最近成功的版本必须排在最前。
            now.set(now.get().plusSeconds(5));
            recorder.record("legacy_oa", "1.0.1");
            assertThat(store.latestSuccessExcept("legacy_oa", "9.9.9")).contains("1.0.1");
            now.set(now.get().plusSeconds(5));
            recorder.record("legacy_oa", "1.0.0");
            assertThat(store.latestSuccessExcept("legacy_oa", "9.9.9")).contains("1.0.0");

            now.set(now.get().plus(VersionSuccess.MIN_INTERVAL));
            recorder.record("legacy_oa", "1.0.0");
            assertThat(rows(crab.dataSource)).containsExactly(
                    "legacy_oa 1.0.0 2026-06-01T00:01:40Z", "legacy_oa 1.0.1 2026-06-01T00:00:35Z");
        }
    }

    private static List<String> rows(DataSource dataSource) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT conch, version, last_success_at FROM version_success ORDER BY conch, version")) {
            while (result.next()) {
                rows.add(result.getString(1) + " " + result.getString(2) + " "
                        + result.getObject(3, java.time.OffsetDateTime.class).toInstant());
            }
        }
        return rows;
    }
}
