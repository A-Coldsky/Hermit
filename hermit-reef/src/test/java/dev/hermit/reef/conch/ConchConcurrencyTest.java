package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(ReefTestConfig.class)
class ConchConcurrencyTest {

    private static final Path DATA = TestDataDirs.create("conch-concurrency");

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", DATA::toString);
    }

    @Autowired
    ConchUploadService uploads;

    @Autowired
    ConchPublishService publishService;

    @Autowired
    ConchAvailabilityService availability;

    @Autowired
    ConchDistributionService distribution;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM hermit_conch_versions");
        jdbc.update("DELETE FROM hermit_conch_availability");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
    }

    @Test
    void concurrentPublishesLeaveExactlyOneCurrentVersion() throws Exception {
        byte[] sample = sample();
        for (int i = 0; i < 8; i++) {
            jdbc.update("DELETE FROM hermit_conch_versions");
            jdbc.update("DELETE FROM hermit_audit_log");
            uploads.upload(TestSecrets.USERNAME, sample);
            uploads.upload(TestSecrets.USERNAME, withVersion(sample, "1.1.0"));
            together(
                    () -> publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.0.0"),
                    () -> publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.1.0"));
            assertThat(count("SELECT COUNT(*) FROM hermit_conch_versions WHERE is_current = TRUE")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM hermit_conch_versions WHERE status = 'PUBLISHED'")).isEqualTo(2);
        }
    }

    @Test
    void concurrentRollbacksLeaveExactlyOneCurrentVersion() throws Exception {
        byte[] sample = sample();
        for (int i = 0; i < 8; i++) {
            jdbc.update("DELETE FROM hermit_conch_versions");
            jdbc.update("DELETE FROM hermit_audit_log");
            uploads.upload(TestSecrets.USERNAME, sample);
            uploads.upload(TestSecrets.USERNAME, withVersion(sample, "1.1.0"));
            uploads.upload(TestSecrets.USERNAME, withVersion(sample, "1.2.0"));
            publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.0.0");
            publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.1.0");
            publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.2.0");
            together(
                    () -> publishService.rollback(TestSecrets.USERNAME, "legacy_oa", "1.0.0"),
                    () -> publishService.rollback(TestSecrets.USERNAME, "legacy_oa", "1.1.0"));
            assertThat(count("SELECT COUNT(*) FROM hermit_conch_versions WHERE is_current = TRUE")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM hermit_conch_versions WHERE status = 'PUBLISHED'")).isEqualTo(3);
        }
    }

    @Test
    void disableAndPublishLeaveTheConchOutOfTheManifest() throws Exception {
        byte[] sample = sample();
        for (int i = 0; i < 4; i++) {
            jdbc.update("DELETE FROM hermit_conch_versions");
            jdbc.update("DELETE FROM hermit_conch_availability");
            jdbc.update("DELETE FROM hermit_audit_log");
            uploads.upload(TestSecrets.USERNAME, sample);
            uploads.upload(TestSecrets.USERNAME, withVersion(sample, "1.1.0"));
            publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.0.0");
            together(
                    () -> {
                        try {
                            publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.1.0");
                        } catch (ApiException ex) {
                            if (ex.status() != 409 || !ConchAvailabilityService.DISABLED.equals(ex.getMessage())) {
                                throw ex;
                            }
                        }
                    },
                    () -> availability.disable(TestSecrets.USERNAME, "legacy_oa", "并发停用"));
            assertThat(availability.isDisabled("legacy_oa")).isTrue();
            assertThat(distribution.manifest(null).entries()).isEmpty();
            assertThat(count("SELECT COUNT(*) FROM hermit_conch_versions WHERE is_current = TRUE")).isEqualTo(1);
        }
    }

    @Test
    void concurrentDownloadsEachIncrementPullCount() throws Exception {
        uploads.upload(TestSecrets.USERNAME, sample());
        publishService.publish(TestSecrets.USERNAME, "legacy_oa", "1.0.0");
        int downloads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(downloads);
        CountDownLatch ready = new CountDownLatch(downloads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < downloads; i++) {
                futures.add(pool.submit(task(() -> {
                    byte[] zip = distribution.publishedZip("legacy_oa", "1.0.0");
                    if (zip.length < 2 || zip[0] != 'P' || zip[1] != 'K') {
                        throw new IllegalStateException("download was not a zip");
                    }
                }, ready, start, failure)));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent download failed", failure.get());
        }
        Long pulls = jdbc.queryForObject(
                "SELECT pull_count FROM hermit_conch_versions WHERE conch = 'legacy_oa' AND version = '1.0.0'",
                Long.class);
        assertThat(pulls).isEqualTo((long) downloads);
    }

    private static void together(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> left = pool.submit(task(first, ready, start, failure));
            Future<?> right = pool.submit(task(second, ready, start, failure));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            left.get(30, TimeUnit.SECONDS);
            right.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent conch update failed", failure.get());
        }
    }

    private static Runnable task(Runnable action, CountDownLatch ready, CountDownLatch start,
            AtomicReference<Throwable> failure) {
        return () -> {
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timeout");
                }
                action.run();
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        };
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static byte[] sample() throws IOException {
        try (InputStream in = ConchConcurrencyTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            if (in == null) {
                throw new IllegalStateException("missing samples/legacy_oa.yaml");
            }
            return in.readAllBytes();
        }
    }

    private static byte[] withVersion(byte[] yaml, String version) {
        String text = new String(yaml, StandardCharsets.UTF_8);
        String updated = text.replace("\nversion: 1.0.0\n", "\nversion: " + version + "\n");
        if (updated.equals(text)) {
            throw new IllegalStateException("version line not found");
        }
        return updated.getBytes(StandardCharsets.UTF_8);
    }
}
