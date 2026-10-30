package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
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
import dev.hermit.common.pkg.PackageReason;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.testing.FakeReef;

class ConchSyncTest {

    private static final UUID INSTANCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String TOKEN = "HermitSyncToken-should-not-leak";
    private static final String SECRET = "HermitSyncSecret-aa11";

    @TempDir
    Path temp;

    @Test
    void classifiesRetryablePermanentAndUnknownReasons() {
        assertRetryable(SyncReasons.http(500));
        assertRetryable(SyncReasons.http(404));
        assertRetryable(SyncReasons.CURRENT_WRITE_FAILED);
        assertRetryable(SyncReasons.REGISTRY_REJECTED);
        assertRetryable(SyncReasons.REGISTRY_REJECTED + ":CONCH_NAME");
        assertRetryable(SyncReasons.REGISTRY_REJECTED + ":A:B");
        for (PackageReason reason : PackageReason.values()) {
            assertRetryable(reason.name());
        }
        assertRetryable("SYNC_FAILED");
        assertRetryable("package unreadable");
        assertRetryable(SyncReasons.PACKAGE_MISSING);
        assertRetryable("NOT_A_KNOWN_REASON");
        assertRetryable(null);

        assertPermanent(SyncReasons.CONCH_NAME);
        assertPermanent(SyncReasons.VERSION_INVALID);
        assertPermanent(SyncReasons.PATH_UNSAFE);
        assertPermanent(SyncReasons.ZIP_TOO_LARGE);
        assertPermanent(SyncReasons.MANIFEST_ENTRY_MISMATCH);
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.NOT_SUBSCRIBED, null))).isFalse();

        assertThat(SyncFailures.retryable(outcome(OutcomeKind.INSTALLED, null))).isFalse();
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION))).isFalse();
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION)))
                .isFalse();
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.SKIPPED_PINNED, SyncReasons.PINNED))).isFalse();
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.ROLLED_BACK, RollbackGuard.REASON))).isFalse();
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.DISABLED, SyncReasons.DISABLED))).isFalse();
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.INSTALLED, "HTTP_500"))).isFalse();
    }

    @Test
    void installsHotReloadsAndLeavesCurrentAsARegularFile() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            SyncReport first = sync.syncOnce();

            assertThat(first.notModified()).isFalse();
            assertThat(first.credentialChanges()).isEmpty();
            assertThat(first.latestCrab()).contains(new CrabRelease(reef.latestVersion(), reef.downloadUrl()));
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            Path current = crab.paths.conches().resolve("legacy_oa").resolve("current");
            assertThat(Files.isSymbolicLink(current)).isFalse();
            assertThat(Files.isRegularFile(current)).isTrue();
            assertThat(Files.readString(current).trim()).isEqualTo("1.0.0");
            assertThat(ConchPackage.readVerified(
                    Files.readAllBytes(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip")),
                    reef.keys().getPublic()).manifest().version()).isEqualTo("1.0.0");
            assertThat(reef.paths()).doesNotContain("/agent/crab.jar");

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
        }
    }

    @Test
    void secondSyncWithTheSameManifestDoesNothing() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            int downloads = reef.downloads();

            SyncReport again = sync.syncOnce();

            assertThat(again.notModified()).isTrue();
            assertThat(again.conches()).isEmpty();
            assertThat(again.removed()).isEmpty();
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
        }
    }

    @Test
    void retriesAPackageAfterTheDownloadFails() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.downloadStatus("legacy_oa", 500);
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());

            SyncReport failed = sync.syncOnce();

            assertThat(outcome(failed, "legacy_oa").reason()).isEqualTo(SyncReasons.http(500));
            assertThat(syncState(crab, "manifest_etag")).isNull();
            assertThat(syncState(crab, "manifest_snapshot")).isNull();
            int downloads = reef.downloads();

            reef.downloadStatus("legacy_oa", 200);
            SyncReport installed = sync.syncOnce();

            assertThat(installed.notModified()).isFalse();
            assertThat(reef.downloads()).isGreaterThan(downloads);
            assertThat(outcome(installed, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.0");
        }
    }

    @Test
    void retriesAnInstallAfterTheCurrentPointerCannotBeWritten() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            assertThat(syncState(crab, "manifest_etag")).isNotNull();

            Path current = crab.paths.conches().resolve("legacy_oa").resolve("current");
            Files.delete(current);
            Files.createDirectory(current);
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            SyncReport failed = sync.syncOnce();

            assertThat(outcome(failed, "legacy_oa").reason()).isEqualTo(SyncReasons.CURRENT_WRITE_FAILED);
            assertThat(syncState(crab, "manifest_etag")).isNull();
            assertThat(syncState(crab, "manifest_snapshot")).isNull();
            int downloads = reef.downloads();

            Files.delete(current);
            SyncReport installed = sync.syncOnce();

            assertThat(installed.notModified()).isFalse();
            assertThat(reef.downloads()).isGreaterThan(downloads);
            assertThat(outcome(installed, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.1");
        }
    }

    @Test
    void aPermanentRejectionStillStoresTheEtag() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publishWrongSha(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchSync sync = sync(reef, crab, new ConchRegistry(), Optional.empty(), Clock.systemUTC());
            SyncReport rejected = sync.syncOnce();

            assertThat(outcome(rejected, "legacy_oa").reason()).isEqualTo(SyncReasons.MANIFEST_ENTRY_MISMATCH);
            assertThat(syncState(crab, "manifest_etag")).isNotBlank();
            assertThat(syncState(crab, "manifest_snapshot")).isEqualTo("{}");
            int downloads = reef.downloads();

            SyncReport again = sync.syncOnce();

            assertThat(again.notModified()).isTrue();
            assertThat(again.conches()).isEmpty();
            assertThat(reef.downloads()).isEqualTo(downloads);
        }
    }

    @Test
    void refetchesWhenTheLocalCurrentDriftsFromTheSnapshot() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            Path current = crab.paths.conches().resolve("legacy_oa").resolve("current");
            assertThat(syncState(crab, "manifest_snapshot")).isEqualTo("{\"legacy_oa\":\"1.0.0\"}");

            Files.writeString(current, "9.9.9\n");
            int downloads = reef.downloads();
            int manifests = manifestRequests(reef);
            SyncReport changed = sync.syncOnce();

            assertThat(changed.notModified()).isFalse();
            assertThat(outcome(changed, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(Files.readString(current).trim()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(reef.downloads()).isGreaterThan(downloads);
            assertThat(manifestRequests(reef) - manifests).isEqualTo(2);

            Files.delete(current);
            downloads = reef.downloads();
            manifests = manifestRequests(reef);
            SyncReport deleted = sync.syncOnce();

            assertThat(deleted.notModified()).isFalse();
            assertThat(outcome(deleted, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(Files.readString(current).trim()).isEqualTo("1.0.0");
            assertThat(reef.downloads()).isGreaterThan(downloads);
            assertThat(manifestRequests(reef) - manifests).isEqualTo(2);
        }
    }

    @Test
    void refetchesWhenTheStoredSnapshotIsMissing() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchSync sync = sync(reef, crab, new ConchRegistry(), Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            try (Connection connection = crab.dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM sync_state WHERE name = 'manifest_snapshot'")) {
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }

            int downloads = reef.downloads();
            int manifests = manifestRequests(reef);
            SyncReport report = sync.syncOnce();

            assertThat(report.notModified()).isFalse();
            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.SKIPPED_SAME);
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(manifestRequests(reef) - manifests).isEqualTo(2);
            assertThat(syncState(crab, "manifest_etag")).isNotBlank();
            assertThat(syncState(crab, "manifest_snapshot")).isEqualTo("{\"legacy_oa\":\"1.0.0\"}");
        }
    }

    @Test
    void aRetryableFailureDoesNotCheckpointASuccessfulSibling() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publishTamperedSignature(ConchTexts.standard("other_sys", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());

            SyncReport first = sync.syncOnce();

            assertThat(outcome(first, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(outcome(first, "other_sys").reason()).isEqualTo("BAD_SIGNATURE");
            assertThat(syncState(crab, "manifest_etag")).isNull();
            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");

            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            int before = reef.paths().size();
            SyncReport second = sync.syncOnce();
            List<String> paths = reef.paths();
            List<String> added = paths.subList(before, paths.size());

            assertThat(outcome(second, "legacy_oa").kind()).isEqualTo(OutcomeKind.SKIPPED_SAME);
            assertThat(outcome(second, "other_sys").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(added).noneMatch(path -> path.startsWith("/agent/conches/legacy_oa/"));
            assertThat(added).anyMatch(path -> path.startsWith("/agent/conches/other_sys/"));
        }
    }

    @Test
    void rejectsBadPackagesWithoutBlockingAGoodConch() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publishTamperedSignature(ConchTexts.standard("bad_sig", "1.0.0"));
            var other = Ed25519Keys.generate();
            reef.publishSignedBy(ConchTexts.standard("bad_key", "1.0.0"), other.getPrivate(), other.getPublic());
            reef.publishWrongSha(ConchTexts.standard("bad_meta", "1.0.0"));
            reef.publishOversize("big_conch");
            reef.publish(ConchTexts.of("future_oa", "1.0.0", "9.0.0", """
                    username: { type: text, required: true }
                    """));
            ConchRegistry registry = new ConchRegistry();
            SyncReport report = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC()).syncOnce();

            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
            assertThat(outcome(report, "bad_sig").reason()).isEqualTo("BAD_SIGNATURE");
            assertThat(outcome(report, "bad_key").reason()).isEqualTo("BAD_SIGNATURE");
            assertThat(outcome(report, "bad_meta").reason()).isEqualTo(SyncReasons.MANIFEST_ENTRY_MISMATCH);
            assertThat(outcome(report, "big_conch").reason()).isEqualTo(SyncReasons.ZIP_TOO_LARGE);
            assertThat(outcome(report, "future_oa").kind()).isEqualTo(OutcomeKind.SKIPPED_MIN_VERSION);
            assertThat(reef.paths()).doesNotContain("/agent/conches/future_oa/1.0.0");
            assertThat(reef.paths()).contains("/agent/conches/legacy_oa/1.0.0");
        }
    }

    @Test
    void aRejectedUpgradeLeavesThePreviousVersionLoaded() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.publishTamperedSignature(ConchTexts.standard("legacy_oa", "1.0.1"));

            SyncReport report = sync.syncOnce();

            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.REJECTED);
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.0");
        }
    }

    @Test
    void removesConchesThatLeftTheManifestAndKeepsTheirFiles() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.remove("other_sys");

            SyncReport report = sync.syncOnce();

            assertThat(report.removed()).containsExactly("other_sys");
            assertThat(outcome(report, "other_sys").kind()).isEqualTo(OutcomeKind.DISABLED);
            assertThat(outcome(report, "other_sys").version()).isEqualTo("1.0.0");
            assertThat(outcome(report, "other_sys").reason()).isEqualTo(SyncReasons.DISABLED);
            assertThat(registry.find("other_sys")).isEmpty();
            assertThat(registry.find("legacy_oa")).isPresent();
            assertThat(Files.exists(crab.paths.conches().resolve("other_sys/1.0.0/package.zip"))).isTrue();
            assertThat(Files.exists(crab.paths.conches().resolve("other_sys/current"))).isFalse();
            assertThat(sync.disabledConches().contains("other_sys")).isTrue();
        }
    }

    @Test
    void aWithdrawnConchStaysDisabledAcross304AndRestartsUntilItReturns() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp);
                SessionManager sessions = new SessionManager()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.remove("other_sys");
            sync.syncOnce();

            SyncReport unchanged = sync.syncOnce();
            assertThat(unchanged.notModified()).isTrue();
            SyncReport shown = sync.visibleStatus(unchanged);
            assertThat(outcome(shown, "other_sys").kind()).isEqualTo(OutcomeKind.DISABLED);
            assertThat(shown.removed()).contains("other_sys");
            assertThat(shown.notModified()).isTrue();

            ConchRegistry restarted = new ConchRegistry();
            InstalledConches.loadAll(crab.paths, reef.keys().getPublic(), restarted, sync.disabledConches().names());
            assertThat(restarted.find("other_sys")).isEmpty();
            assertThat(restarted.find("legacy_oa")).isPresent();

            Invoker invoker = new Invoker(
                    registry,
                    crab.accounts,
                    crab.vault,
                    new ConchConfigStore(crab.dataSource),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(crab.dataSource, Clock.systemUTC()),
                    new IdempotencyStore(crab.dataSource, new CredentialCipher(new byte[32]), Clock.systemUTC()),
                    (conch, action) -> false,
                    sync.disabledConches());
            ApiKeyPrincipal principal = new ApiKeyPrincipal("key", "key", new ApiKeyScope(java.util.Set.of(), java.util.Set.of(), false));
            String disabledBody = new String(invoker.invoke(
                    principal, "other_sys", "list_todo", Map.of(), Optional.empty(), Optional.empty(), "trace-disabled")
                    .body(), StandardCharsets.UTF_8);
            assertThat(disabledBody).contains("CONCH_DISABLED");
            assertThat(registry.find("other_sys")).isEmpty();

            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            SyncReport restored = sync.syncOnce();
            assertThat(outcome(restored, "other_sys").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(sync.disabledConches().contains("other_sys")).isFalse();
            assertThat(registry.find("other_sys")).isPresent();
            assertThat(Files.readString(crab.paths.conches().resolve("other_sys/current")).trim()).isEqualTo("1.0.0");
            assertThat(ConchPackage.readVerified(
                    Files.readAllBytes(crab.paths.conches().resolve("other_sys/1.0.0/package.zip")),
                    reef.keys().getPublic()).manifest().conch()).isEqualTo("other_sys");
            String resumed = new String(invoker.invoke(
                    principal, "other_sys", "list_todo", Map.of(), Optional.empty(), Optional.empty(), "trace-back")
                    .body(), StandardCharsets.UTF_8);
            assertThat(resumed).doesNotContain("CONCH_DISABLED");
        }
    }

    @Test
    void reportsCredentialDeclarationChangesAndDeletesRemovedOfficialFields() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            crab.accounts.create(Origin.OFFICIAL, "legacy_oa", "alice", "cli");
            crab.accounts.create(Origin.LOCAL, "legacy_oa", "alice", "cli");
            crab.vault.write(Origin.OFFICIAL, "legacy_oa", "alice", Map.of("password", SECRET, "username", "alice"), "cli");
            crab.vault.write(Origin.LOCAL, "legacy_oa", "alice", Map.of("password", SECRET), "cli");
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            assertThat(sync.syncOnce().credentialChanges()).isEmpty();

            reef.publish(ConchTexts.withoutPassword("legacy_oa", "1.0.1"));
            SyncReport report = sync.syncOnce();

            assertThat(report.credentialChanges()).singleElement().satisfies(change -> {
                assertThat(change.conch()).isEqualTo("legacy_oa");
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
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();

            Path current = crab.paths.conches().resolve("legacy_oa").resolve("current");
            Files.delete(current);
            Files.createDirectory(current);
            reef.publish(ConchTexts.withoutPassword("legacy_oa", "1.0.1"));
            SyncReport report = sync.syncOnce();

            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.REJECTED);
            assertThat(outcome(report, "legacy_oa").reason()).isEqualTo(SyncReasons.CURRENT_WRITE_FAILED);
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(crab.vault.decryptForExecution(Origin.OFFICIAL, "legacy_oa", "alice"))
                    .containsEntry("password", SECRET);
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.1"))).isFalse();
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
        }
    }

    @Test
    void retriesAFailedCredentialDeleteOnTheNextSync() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            crab.accounts.create(Origin.OFFICIAL, "legacy_oa", "alice", "cli");
            crab.vault.write(Origin.OFFICIAL, "legacy_oa", "alice", Map.of("password", SECRET, "username", "alice"), "cli");
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC());
            sync.syncOnce();
            reef.publish(ConchTexts.withoutPassword("legacy_oa", "1.0.1"));
            sync.failNextCredentialDelete();

            Logger logger = Logger.getLogger(ConchSync.class.getName());
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
    void anIllegalConchNameDoesNotBlockTheOtherEntries() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.catalog("../x", "1.0.0");
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            SyncReport report = sync(reef, crab, registry, Optional.empty(), Clock.systemUTC()).syncOnce();

            assertThat(outcome(report, "../x").kind()).isEqualTo(OutcomeKind.REJECTED);
            assertThat(outcome(report, "../x").reason()).isEqualTo(SyncReasons.CONCH_NAME);
            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
            assertThat(reef.paths()).noneMatch(path -> path.contains(".."));
            assertThat(Files.exists(crab.paths.conches().getParent().resolve("x"))).isFalse();
        }
    }

    @Test
    void keepsTheCurrentVersionThePreviousSuccessAndThreeRecentDirectories() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), new SteppingClock());
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
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry, Optional.empty(), new SteppingClock());
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
            ConchSync sync = sync(reef, crab, new ConchRegistry(), Optional.of(TOKEN), Clock.systemUTC());
            assertThatThrownBy(sync::syncOnce)
                    .isInstanceOf(SyncException.class)
                    .hasMessage(SyncReasons.http(302));
            assertThat(reef.paths()).doesNotContain("/agent/stolen");
            assertThat(sync.toString()).doesNotContain(TOKEN);

            Logger logger = Logger.getLogger(ConchSync.class.getName());
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
            ConchSync sync = new ConchSync(settings, crab.paths, new ConchRegistry(), crab.vault, crab.dataSource);
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

    private static void publish(FakeReef reef, ConchSync sync, String version) {
        reef.publish(ConchTexts.standard("legacy_oa", version));
        SyncReport report = sync.syncOnce();
        assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
    }

    private static ConchOutcome outcome(SyncReport report, String conch) {
        return report.conches().stream().filter(item -> conch.equals(item.conch())).findFirst().orElseThrow();
    }

    private static String syncState(OpenCrab crab, String name) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT \"value\" FROM sync_state WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getString(1);
            }
        }
    }

    private static int manifestRequests(FakeReef reef) {
        int count = 0;
        for (String path : reef.paths()) {
            if ("/agent/conches/manifest".equals(path)) {
                count++;
            }
        }
        return count;
    }

    private static void assertRetryable(String reason) {
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.REJECTED, reason))).as(String.valueOf(reason)).isTrue();
    }

    private static void assertPermanent(String reason) {
        assertThat(SyncFailures.retryable(outcome(OutcomeKind.REJECTED, reason))).as(reason).isFalse();
    }

    private static ConchOutcome outcome(OutcomeKind kind, String reason) {
        return new ConchOutcome("legacy_oa", "1.0.0", kind, reason);
    }

    private static List<String> versionDirs(OpenCrab crab) throws Exception {
        try (var children = Files.list(crab.paths.conches().resolve("legacy_oa"))) {
            return children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).toList();
        }
    }

    private static ConchSync sync(
            FakeReef reef,
            OpenCrab crab,
            ConchRegistry registry,
            Optional<String> token,
            Clock clock
    ) {
        TestSubscriptions.grantKnown(crab.dataSource);
        return new ConchSync(
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
