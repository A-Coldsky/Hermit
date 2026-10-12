package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.ShellPackage;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.FakeReef;

class ShellSyncTest {

    private static final UUID INSTANCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String TOKEN = "HermitSyncToken-should-not-leak";
    private static final String SECRET = "HermitSyncSecret-aa11";

    @TempDir
    Path temp;

    @Test
    void installsHotReloadsAndLeavesCurrentAsARegularFile() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            SyncReport first = sync.syncOnce();

            assertThat(first.notModified()).isFalse();
            assertThat(first.credentialChanges()).isEmpty();
            assertThat(first.latestCrab()).contains(new CrabRelease(reef.latestVersion(), reef.downloadUrl()));
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            Path current = crab.paths.shells().resolve("legacy_oa").resolve("current");
            assertThat(Files.isSymbolicLink(current)).isFalse();
            assertThat(Files.isRegularFile(current)).isTrue();
            assertThat(Files.readString(current).trim()).isEqualTo("1.0.0");
            assertThat(ShellPackage.readVerified(
                    Files.readAllBytes(crab.paths.shells().resolve("legacy_oa/1.0.0/package.zip")),
                    reef.keys().getPublic()).manifest().version()).isEqualTo("1.0.0");
            assertThat(reef.paths()).doesNotContain("/agent/crab.jar");

            reef.publish(ShellTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
        }
    }

    @Test
    void secondSyncWithTheSameManifestDoesNothing() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            int downloads = reef.downloads();

            SyncReport again = sync.syncOnce();

            assertThat(again.notModified()).isTrue();
            assertThat(again.shells()).isEmpty();
            assertThat(again.removed()).isEmpty();
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
        }
    }

    @Test
    void rejectsBadPackagesWithoutBlockingAGoodShell() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            reef.publishTamperedSignature(ShellTexts.standard("bad_sig", "1.0.0"));
            var other = Ed25519Keys.generate();
            reef.publishSignedBy(ShellTexts.standard("bad_key", "1.0.0"), other.getPrivate(), other.getPublic());
            reef.publishWrongSha(ShellTexts.standard("bad_meta", "1.0.0"));
            reef.publishOversize("big_shell");
            reef.publish(ShellTexts.of("future_oa", "1.0.0", "9.0.0", """
                    username: { type: text, required: true }
                    """));
            ShellRegistry registry = new ShellRegistry();
            SyncReport report = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC()).syncOnce();

            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
            assertThat(outcome(report, "bad_sig").reason()).isEqualTo("BAD_SIGNATURE");
            assertThat(outcome(report, "bad_key").reason()).isEqualTo("BAD_SIGNATURE");
            assertThat(outcome(report, "bad_meta").reason()).isEqualTo(SyncReasons.MANIFEST_ENTRY_MISMATCH);
            assertThat(outcome(report, "big_shell").reason()).isEqualTo(SyncReasons.ZIP_TOO_LARGE);
            assertThat(outcome(report, "future_oa").kind()).isEqualTo(OutcomeKind.SKIPPED_MIN_VERSION);
            assertThat(reef.paths()).doesNotContain("/agent/shells/future_oa/1.0.0");
            assertThat(reef.paths()).contains("/agent/shells/legacy_oa/1.0.0");
        }
    }

    @Test
    void aRejectedUpgradeLeavesThePreviousVersionLoaded() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.publishTamperedSignature(ShellTexts.standard("legacy_oa", "1.0.1"));

            SyncReport report = sync.syncOnce();

            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.REJECTED);
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readString(crab.paths.shells().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.0");
        }
    }

    @Test
    void removesShellsThatLeftTheManifestAndKeepsTheirFiles() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            reef.publish(ShellTexts.standard("other_sys", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.remove("other_sys");

            SyncReport report = sync.syncOnce();

            assertThat(report.removed()).containsExactly("other_sys");
            assertThat(registry.find("other_sys")).isEmpty();
            assertThat(registry.find("legacy_oa")).isPresent();
            assertThat(Files.exists(crab.paths.shells().resolve("other_sys/1.0.0/package.zip"))).isTrue();
        }
    }

    @Test
    void reportsCredentialDeclarationChangesAndDeletesRemovedOfficialFields() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            crab.accounts.create(Origin.OFFICIAL, "legacy_oa", "alice", "cli");
            crab.accounts.create(Origin.LOCAL, "legacy_oa", "alice", "cli");
            crab.vault.write(Origin.OFFICIAL, "legacy_oa", "alice", Map.of("password", SECRET, "username", "alice"), "cli");
            crab.vault.write(Origin.LOCAL, "legacy_oa", "alice", Map.of("password", SECRET), "cli");
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            assertThat(sync.syncOnce().credentialChanges()).isEmpty();

            reef.publish(ShellTexts.withoutPassword("legacy_oa", "1.0.1"));
            SyncReport report = sync.syncOnce();

            assertThat(report.credentialChanges()).singleElement().satisfies(change -> {
                assertThat(change.shell()).isEqualTo("legacy_oa");
                assertThat(change.addedRequired()).containsExactly("otp");
                assertThat(change.removed()).containsExactly("password");
            });
            assertThat(crab.vault.fieldNames(Origin.OFFICIAL, "legacy_oa", "alice")).containsExactly("username");
            assertThat(crab.vault.decryptForExecution(Origin.LOCAL, "legacy_oa", "alice")).containsEntry("password", SECRET);
            assertThat(report.toString()).doesNotContain(SECRET);
        }
    }

    @Test
    void aFailedCurrentPointerLeavesTheRegistryAndCredentialsUntouched() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            crab.accounts.create(Origin.OFFICIAL, "legacy_oa", "alice", "cli");
            crab.vault.write(Origin.OFFICIAL, "legacy_oa", "alice", Map.of("password", SECRET), "cli");
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();

            Path current = crab.paths.shells().resolve("legacy_oa").resolve("current");
            Files.delete(current);
            Files.createDirectory(current);
            reef.publish(ShellTexts.withoutPassword("legacy_oa", "1.0.1"));
            SyncReport report = sync.syncOnce();

            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.REJECTED);
            assertThat(outcome(report, "legacy_oa").reason()).isEqualTo(SyncReasons.CURRENT_WRITE_FAILED);
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(crab.vault.decryptForExecution(Origin.OFFICIAL, "legacy_oa", "alice"))
                    .containsEntry("password", SECRET);
            assertThat(Files.exists(crab.paths.shells().resolve("legacy_oa/1.0.1"))).isFalse();
            assertThat(Files.exists(crab.paths.shells().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
        }
    }

    @Test
    void retriesAFailedCredentialDeleteOnTheNextSync() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            crab.accounts.create(Origin.OFFICIAL, "legacy_oa", "alice", "cli");
            crab.vault.write(Origin.OFFICIAL, "legacy_oa", "alice", Map.of("password", SECRET, "username", "alice"), "cli");
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.publish(ShellTexts.withoutPassword("legacy_oa", "1.0.1"));
            sync.failNextCredentialDelete();

            Logger logger = Logger.getLogger(ShellSync.class.getName());
            List<String> messages = new ArrayList<>();
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(String.valueOf(record.getMessage()) + " " + java.util.Arrays.toString(record.getParameters()));
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
            Level previous = logger.getLevel();
            logger.setLevel(Level.ALL);
            try {
                SyncReport report = sync.syncOnce();
                assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
                assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
                assertThat(crab.vault.decryptForExecution(Origin.OFFICIAL, "legacy_oa", "alice"))
                        .containsEntry("password", SECRET);

                sync.syncOnce();
                assertThat(crab.vault.fieldNames(Origin.OFFICIAL, "legacy_oa", "alice")).containsExactly("username");
            } finally {
                logger.removeHandler(handler);
                logger.setLevel(previous);
            }
            assertThat(messages).anyMatch(message -> message.contains("credential delete failed"));
            assertThat(messages).noneMatch(message -> message.contains(SECRET));
        }
    }

    @Test
    void anIllegalShellNameDoesNotBlockTheOtherEntries() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.catalog("../x", "1.0.0");
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            ShellRegistry registry = new ShellRegistry();
            SyncReport report = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC()).syncOnce();

            assertThat(outcome(report, "../x").kind()).isEqualTo(OutcomeKind.REJECTED);
            assertThat(outcome(report, "../x").reason()).isEqualTo(SyncReasons.SHELL_NAME);
            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
            assertThat(reef.paths()).noneMatch(path -> path.contains(".."));
            assertThat(Files.exists(crab.paths.shells().getParent().resolve("x"))).isFalse();
        }
    }

    @Test
    void keepsTheCurrentVersionThePreviousSuccessAndThreeRecentDirectories() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), new SteppingClock());
            publish(reef, sync, "1.0.0");
            new RollbackGuard(
                    crab.paths,
                    reef.keys().getPublic(),
                    registry,
                    crab.dataSource,
                    new dev.hermit.crab.report.ReportQueue(new dev.hermit.crab.report.ReportSettings(
                            false, reef.base(), Optional.empty(), INSTANCE, "1.0.0"), crab.dataSource))
                    .onResult("legacy_oa", "1.0.0", success("1.0.0"));
            publish(reef, sync, "1.0.1");
            publish(reef, sync, "1.0.2");
            publish(reef, sync, "1.0.3");
            publish(reef, sync, "1.0.4");

            assertThat(versionDirs(crab)).containsExactlyInAnyOrder("1.0.0", "1.0.2", "1.0.3", "1.0.4");
        }
    }

    @Test
    void dropsVersionsOutsideTheThreeMostRecentWhenNothingSucceeded() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ShellRegistry registry = new ShellRegistry();
            ShellSync sync = sync(reef, crab, registry, Optional.empty(), new SteppingClock());
            for (String version : List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4")) {
                publish(reef, sync, version);
            }
            assertThat(versionDirs(crab)).containsExactlyInAnyOrder("1.0.2", "1.0.3", "1.0.4");
        }
    }

    @Test
    void doesNotFollowRedirectsOrLogTheToken() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.redirectManifest();
            reef.expectToken(TOKEN);
            ShellSync sync = sync(reef, crab, new ShellRegistry(), Optional.of(TOKEN), Clock.systemUTC());
            assertThatThrownBy(sync::syncOnce)
                    .isInstanceOf(SyncException.class)
                    .hasMessage(SyncReasons.http(302));
            assertThat(reef.paths()).doesNotContain("/agent/stolen");
            assertThat(sync.toString()).doesNotContain(TOKEN);

            Logger logger = Logger.getLogger(ShellSync.class.getName());
            List<String> messages = new ArrayList<>();
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(String.valueOf(record.getMessage()) + " " + java.util.Arrays.toString(record.getParameters()));
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
            Level previous = logger.getLevel();
            logger.setLevel(Level.ALL);
            try {
                sync.runScheduled();
            } finally {
                logger.removeHandler(handler);
                logger.setLevel(previous);
            }
            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(message -> message.contains(TOKEN));
            assertThat(reef.tokenMatched()).isTrue();
        }
    }

    @Test
    void settingsHideTheTokenAndStartSchedulesOnce() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            SyncSettings settings = settings(reef, Optional.of(TOKEN), Duration.ofMillis(200));
            assertThat(settings.toString()).doesNotContain(TOKEN);
            ShellSync sync = new ShellSync(settings, crab.paths, new ShellRegistry(), crab.vault, crab.dataSource);
            AtomicInteger scheduled = new AtomicInteger();
            long[] delays = new long[2];
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
                @Override
                public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                        Runnable command, long initialDelay, long delay, TimeUnit unit) {
                    scheduled.incrementAndGet();
                    delays[0] = unit.toMillis(initialDelay);
                    delays[1] = unit.toMillis(delay);
                    return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
                }
            };
            try {
                sync.start(executor);
                sync.start(executor);
                assertThat(scheduled).hasValue(1);
                assertThat(delays).containsExactly(200L, 200L);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void publish(FakeReef reef, ShellSync sync, String version) {
        reef.publish(ShellTexts.standard("legacy_oa", version));
        SyncReport report = sync.syncOnce();
        assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
    }

    private static ShellOutcome outcome(SyncReport report, String shell) {
        return report.shells().stream().filter(item -> shell.equals(item.shell())).findFirst().orElseThrow();
    }

    private static List<String> versionDirs(OpenCrab crab) throws Exception {
        try (var children = Files.list(crab.paths.shells().resolve("legacy_oa"))) {
            return children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).toList();
        }
    }

    private static ShellSync sync(
            FakeReef reef,
            OpenCrab crab,
            ShellRegistry registry,
            Optional<String> token,
            Clock clock
    ) {
        return new ShellSync(
                settings(reef, token, Duration.ofMinutes(5)),
                crab.paths,
                registry,
                crab.vault,
                crab.dataSource,
                clock);
    }

    private static SyncSettings settings(FakeReef reef, Optional<String> token, Duration interval) {
        return new SyncSettings(reef.base(), token, reef.keys().getPublic(), interval, INSTANCE);
    }

    private static dev.hermit.crab.engine.ExecutionResult success(String version) {
        return new dev.hermit.crab.engine.ExecutionResult(true, null, null, 1, List.of(), version, "trace-ok");
    }

    private static final class SteppingClock extends Clock {
        private long millis = 1_700_000_000_000L;

        @Override
        public Instant instant() {
            millis += 1000;
            return Instant.ofEpochMilli(millis);
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
