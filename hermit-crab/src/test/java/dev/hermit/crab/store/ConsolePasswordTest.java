package dev.hermit.crab.store;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ConsoleSessions;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsolePasswordTest {
    private static final String PASSWORD = "console-secret-1";

    @TempDir
    Path temp;

    @Test
    void lockoutDoesNotExtendTheWindow() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("lock"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
            ConsolePassword passwords = new ConsolePassword(fixture.dataSource, clock);
            passwords.set(PASSWORD);
            assertThat(storedIterations(fixture)).isEqualTo(ConsolePassword.ITERATIONS);
            assertThat(storedLength(fixture, "salt")).isEqualTo(ConsolePassword.SALT_BYTES);
            assertThat(storedLength(fixture, "hash")).isEqualTo(ConsolePassword.HASH_BYTES);

            for (int i = 0; i < 4; i++) {
                assertThat(passwords.verify("wrong-password")).isEqualTo(ConsolePassword.Result.REJECTED);
            }
            assertThat(failureRows(fixture)).isEqualTo(4);
            assertThat(passwords.verify(PASSWORD)).isEqualTo(ConsolePassword.Result.ACCEPTED);
            assertThat(failureRows(fixture)).isZero();

            for (int i = 0; i < ConsolePassword.MAX_FAILURES; i++) {
                assertThat(passwords.verify("wrong-password")).isEqualTo(ConsolePassword.Result.REJECTED);
            }
            assertThat(failureRows(fixture)).isEqualTo(ConsolePassword.MAX_FAILURES);
            Instant newest = newestFailure(fixture);

            clock.advance(Duration.ofMinutes(14));
            assertThat(passwords.verify(PASSWORD)).isEqualTo(ConsolePassword.Result.REJECTED);
            assertThat(passwords.verify("other-wrong-pw")).isEqualTo(ConsolePassword.Result.REJECTED);
            assertThat(failureRows(fixture)).isEqualTo(ConsolePassword.MAX_FAILURES);
            assertThat(newestFailure(fixture)).isEqualTo(newest);

            clock.advance(Duration.ofMinutes(1));
            assertThat(passwords.verify(PASSWORD)).isEqualTo(ConsolePassword.Result.ACCEPTED);
            assertThat(failureRows(fixture)).isZero();
        }
    }

    @Test
    void concurrentVerifyCannotBypassLockoutCap() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("concurrent-lock"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
            ConsolePassword passwords = new ConsolePassword(fixture.dataSource, clock);
            passwords.set(PASSWORD);
            for (int i = 0; i < ConsolePassword.MAX_FAILURES - 1; i++) {
                assertThat(passwords.verify("wrong-password")).isEqualTo(ConsolePassword.Result.REJECTED);
            }
            assertThat(failureRows(fixture)).isEqualTo(ConsolePassword.MAX_FAILURES - 1);

            int threads = 8;
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                java.util.List<java.util.concurrent.Future<ConsolePassword.Result>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return passwords.verify("wrong-password");
                    }));
                }
                assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for (java.util.concurrent.Future<ConsolePassword.Result> future : futures) {
                    assertThat(future.get(15, java.util.concurrent.TimeUnit.SECONDS))
                            .isEqualTo(ConsolePassword.Result.REJECTED);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(failureRows(fixture)).isEqualTo(ConsolePassword.MAX_FAILURES);
            assertThat(passwords.verify(PASSWORD)).isEqualTo(ConsolePassword.Result.REJECTED);
        }
    }

    @Test
    void rejectsPasswordsOutsideTheLengthBounds() {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("length"))) {
            ConsolePassword passwords = new ConsolePassword(fixture.dataSource, Clock.systemUTC());
            assertThatThrownBy(() -> passwords.set("short"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ConsolePassword.LENGTH_MESSAGE);
            assertThatThrownBy(() -> passwords.set("x".repeat(ConsolePassword.MAX_LENGTH + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ConsolePassword.LENGTH_MESSAGE);
            assertThat(passwords.isSet()).isFalse();
            assertThat(passwords.verify(PASSWORD)).isEqualTo(ConsolePassword.Result.UNSET);
        }
    }

    @Test
    void environmentPasswordIsHashedOnceAndStaysOutOfFiles() throws Exception {
        Path home = temp.resolve("env");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), "server:\n  host: \"127.0.0.1\"\n  port: 7777\n");
        CrabPaths paths = new CrabPaths(home);
        try (CrabRuntime runtime = CrabRuntime.open(paths, CrabConfig.defaults(), env(home, PASSWORD))) {
            assertThat(runtime.consolePassword().isSet()).isTrue();
            assertThat(runtime.consolePassword().verify(PASSWORD)).isEqualTo(ConsolePassword.Result.ACCEPTED);
        }
        try (CrabRuntime runtime = CrabRuntime.open(paths, CrabConfig.defaults(), env(home, "replaced-secret-value"))) {
            assertThat(runtime.consolePassword().verify(PASSWORD)).isEqualTo(ConsolePassword.Result.ACCEPTED);
            assertThat(runtime.consolePassword().verify("replaced-secret-value"))
                    .isEqualTo(ConsolePassword.Result.REJECTED);
        }
        SecretChecks.absent(Files.readString(home.resolve("config.yml")), PASSWORD);
        SecretChecks.absent(Files.readString(home.resolve("config.yml")), "replaced-secret-value");
        try (var walk = Files.walk(home)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                SecretChecks.absent(bytes, PASSWORD);
                SecretChecks.absent(bytes, "replaced-secret-value");
            }
        }
    }

    @Test
    void blankOrShortEnvironmentPasswordDoesNotSetAHash() {
        Path blankHome = temp.resolve("blank");
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(blankHome), CrabConfig.defaults(), env(blankHome, ""))) {
            assertThat(runtime.consolePassword().isSet()).isFalse();
        }
        Path shortHome = temp.resolve("short-env");
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> CrabRuntime.open(new CrabPaths(shortHome), CrabConfig.defaults(), env(shortHome, "short")));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ConsolePassword.ENV_LENGTH_MESSAGE);
        SecretChecks.absent(thrown, "short");
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(shortHome), CrabConfig.defaults(), name -> null)) {
            assertThat(runtime.consolePassword().isSet()).isFalse();
        }
    }

    @Test
    void sessionExpiresAbsolutelyAndClearsTogether() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
        ConsoleSessions sessions = new ConsoleSessions(clock);
        String token = sessions.issue();
        assertThat(token).hasSize(64);
        assertThat(sessions.valid(token)).isTrue();
        String header = ConsoleSessions.setCookie(token);
        assertThat(header).contains("Path=/console", "HttpOnly", "SameSite=Strict", "Max-Age=43200");
        assertThat(header).doesNotContain("Secure");
        clock.advance(Duration.ofHours(12));
        assertThat(sessions.valid(token)).isFalse();
        String fresh = sessions.issue();
        sessions.clear();
        assertThat(sessions.valid(fresh)).isFalse();
    }

    private static Function<String, String> env(Path home, String password) {
        return name -> switch (name) {
            case "HERMIT_HOME" -> home.toString();
            case ConsolePassword.ENV -> password;
            default -> null;
        };
    }

    private static int storedIterations(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT iterations FROM console_password WHERE id = 1");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static int storedLength(StoreFixture fixture, String column) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM console_password WHERE id = 1");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getBytes(1).length;
        }
    }

    private static int failureRows(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM console_login_failures");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static Instant newestFailure(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT MAX(failed_at) AS failed_at FROM console_login_failures");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return Db.instant(resultSet, "failed_at");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
