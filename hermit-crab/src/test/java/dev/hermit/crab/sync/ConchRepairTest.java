package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.PackageReason;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.testing.FakeReef;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.store.IdempotencyStore;

/**
 * A subscribed conch that was installed must not be downloaded again when its pointer or
 * package cannot be loaded. The user confirms that with {@code update}.
 */
class ConchRepairTest {

    private static final UUID INSTANCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @TempDir
    Path temp;

    @Test
    void firstSyncStillInstallsTheManifestVersion() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            SyncReport installed = sync(reef, crab, registry).syncOnce();

            assertThat(outcome(installed, "legacy_oa").kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(outcome(installed, "legacy_oa").version()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa")).isPresent();
            assertThat(Files.readString(current(crab)).trim()).isEqualTo("1.0.0");
            assertThat(auditCount(crab, "CONCH_REPAIR_REQUIRED")).isZero();
        }
    }

    @Test
    void lostPointerDoesNotDownloadAndWritesOneAudit() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            int downloads = reef.downloads();

            registry.remove("legacy_oa");
            Files.delete(current(crab));
            SyncReport repair = sync.syncOnce();

            assertThat(repair.notModified()).isFalse();
            assertThat(outcome(repair, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(outcome(repair, "legacy_oa").kind()).isNotEqualTo(OutcomeKind.INSTALLED);
            assertThat(outcome(repair, "legacy_oa").reason()).isEqualTo(SyncReasons.POINTER_MISSING);
            assertThat(outcome(repair, "legacy_oa").version()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa")).isEmpty();
            assertThat(Files.exists(current(crab))).isFalse();
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(auditCount(crab, "CONCH_REPAIR_REQUIRED")).isEqualTo(1);
            assertThat(auditRow(crab, "CONCH_REPAIR_REQUIRED")).contains("sync").contains("OFFICIAL/legacy_oa")
                    .contains("POINTER_MISSING");

            SyncReport again = sync.syncOnce();
            assertThat(again.notModified()).isFalse();
            assertThat(outcome(again, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(auditCount(crab, "CONCH_REPAIR_REQUIRED")).isEqualTo(1);
            assertThat(registry.find("legacy_oa")).isEmpty();
        }
    }

    @Test
    void unparsablePointerDoesNotDownload() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            registry.remove("legacy_oa");
            Files.writeString(current(crab), "not-a-version\n");
            int downloads = reef.downloads();

            SyncReport repair = sync.syncOnce();

            assertThat(outcome(repair, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(outcome(repair, "legacy_oa").reason()).isEqualTo(SyncReasons.VERSION_INVALID);
            assertThat(outcome(repair, "legacy_oa").version()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa")).isEmpty();
            assertThat(Files.readString(current(crab)).trim()).isEqualTo("not-a-version");
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(auditCount(crab, "CONCH_REPAIR_REQUIRED")).isEqualTo(1);
        }
    }

    @Test
    void corruptPackageIsNotAnEmpty304AndInvokeIsNotSuccess() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp);
                SessionManager sessions = new SessionManager()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            tamper(crab);
            registry.remove("legacy_oa");
            int downloads = reef.downloads();

            SyncReport repair = sync.syncOnce();

            assertThat(repair.notModified()).isFalse();
            assertThat(repair.conches()).isNotEmpty();
            assertThat(outcome(repair, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(PackageReason.valueOf(outcome(repair, "legacy_oa").reason())).isNotNull();
            assertThat(outcome(repair, "legacy_oa").version()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa")).isEmpty();
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(auditCount(crab, "CONCH_REPAIR_REQUIRED")).isEqualTo(1);

            SyncReport again = sync.syncOnce();
            assertThat(again.notModified()).isFalse();
            assertThat(outcome(again, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(reef.downloads()).isEqualTo(downloads);

            var called = invoke(crab, registry, sync, sessions);
            String body = new String(called.body(), StandardCharsets.UTF_8);
            assertThat(called.status()).isEqualTo(404);
            assertThat(body).contains(ErrorCode.NOT_FOUND.name()).doesNotContain("\"value\":\"ok\"");
            assertThat(registry.find("legacy_oa")).isEmpty();
        }
    }

    @Test
    void wrongPublicKeyIsNotAnEmpty304AndDoesNotDownload() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp);
                SessionManager sessions = new SessionManager()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchSync installed = sync(reef, crab, new ConchRegistry());
            installed.syncOnce();
            int downloads = reef.downloads();
            ConchRegistry restarted = new ConchRegistry();
            ConchSync sync = new ConchSync(
                    new SyncSettings(
                            reef.base(),
                            Optional.empty(),
                            Ed25519Keys.generate().getPublic(),
                            Duration.ofMinutes(5),
                            INSTANCE),
                    crab.paths,
                    restarted,
                    crab.vault,
                    crab.dataSource,
                    Clock.systemUTC());

            SyncReport repair = sync.syncOnce();

            assertThat(repair.notModified()).isFalse();
            assertThat(outcome(repair, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(outcome(repair, "legacy_oa").reason()).isEqualTo(PackageReason.BAD_SIGNATURE.name());
            assertThat(restarted.find("legacy_oa")).isEmpty();
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(Files.readString(current(crab)).trim()).isEqualTo("1.0.0");
            var called = invoke(crab, restarted, sync, sessions);
            assertThat(called.status()).isEqualTo(404);
            assertThat(new String(called.body(), StandardCharsets.UTF_8)).contains(ErrorCode.NOT_FOUND.name());
        }
    }

    @Test
    void explicitUpdateReloadsAVerifiedZipAndDoesNotJumpToAHigherVersion() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            SyncReport waiting = sync.syncOnce();
            assertThat(outcome(waiting, "legacy_oa").kind()).isEqualTo(OutcomeKind.UPDATE_AVAILABLE);
            assertThat(outcome(waiting, "legacy_oa").version()).isEqualTo("1.0.0");
            int downloads = reef.downloads();

            registry.remove("legacy_oa");
            Files.delete(current(crab));
            assertThat(outcome(sync.syncOnce(), "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);

            ConchOutcome restored = sync.update("legacy_oa", Actors.CONSOLE).outcome();

            assertThat(restored.kind()).isEqualTo(OutcomeKind.REPAIRED);
            assertThat(restored.reason()).isEqualTo(SyncReasons.RESTORED);
            assertThat(restored.version()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readString(current(crab)).trim()).isEqualTo("1.0.0");
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(sync.visibleStatus(new SyncReport(true, java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), Optional.empty())).conches())
                    .noneMatch(row -> row.kind() == OutcomeKind.REPAIR_REQUIRED);

            ConchOutcome updated = sync.update("legacy_oa", Actors.CONSOLE).outcome();
            assertThat(updated.kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(updated.version()).isEqualTo("1.0.1");
            assertThat(Files.readString(current(crab)).trim()).isEqualTo("1.0.1");
            assertThat(reef.downloads()).isGreaterThan(downloads);
        }
    }

    @Test
    void explicitUpdateInstallsTheCatalogVersionWhenTheZipDoesNotVerify() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            tamper(crab);
            registry.remove("legacy_oa");
            sync.syncOnce();
            int downloads = reef.downloads();

            ConchOutcome installed = sync.update("legacy_oa", Actors.CONSOLE).outcome();

            assertThat(installed.kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(installed.version()).isEqualTo("1.0.0");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readString(current(crab)).trim()).isEqualTo("1.0.0");
            assertThat(reef.downloads()).isGreaterThan(downloads);
            SyncReport after = sync.syncOnce();
            assertThat(outcome(after, "legacy_oa").kind()).isEqualTo(OutcomeKind.SKIPPED_SAME);
            assertThat(reef.downloads()).isEqualTo(downloads + 1);
        }
    }

    @Test
    void visibleStatusKeepsARepairRowOnAnEmpty304() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            registry.remove("legacy_oa");
            Files.delete(current(crab));

            SyncReport shown = sync.visibleStatus(
                    new SyncReport(true, java.util.List.of(), java.util.List.of(), java.util.List.of(), Optional.empty()));

            assertThat(shown.notModified()).isTrue();
            assertThat(outcome(shown, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
            assertThat(outcome(shown, "legacy_oa").reason()).isEqualTo(SyncReasons.POINTER_MISSING);
            assertThat(shown.conches()).isNotEmpty();
        }
    }

    @Test
    void disabledConchWithMissingFilesIsNotDownloaded() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            sync.syncOnce();
            reef.remove("legacy_oa");
            SyncReport disabled = sync.syncOnce();
            assertThat(outcome(disabled, "legacy_oa").kind()).isEqualTo(OutcomeKind.DISABLED);
            registry.remove("legacy_oa");
            Files.delete(current(crab));
            Files.delete(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"));
            int downloads = reef.downloads();

            SyncReport again = sync.syncOnce();

            assertThat(again.conches()).noneMatch(row -> "legacy_oa".equals(row.conch())
                    && row.kind() == OutcomeKind.INSTALLED);
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(registry.find("legacy_oa")).isEmpty();
            SyncReport shown = sync.visibleStatus(again);
            assertThat(outcome(shown, "legacy_oa").kind()).isEqualTo(OutcomeKind.DISABLED);
            assertThatThrownBy(() -> sync.update("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessage(DisabledConches.CALL_MESSAGE);
            assertThat(reef.downloads()).isEqualTo(downloads);
        }
    }

    private static dev.hermit.crab.runtime.InvokeOutcome invoke(
            OpenCrab crab,
            ConchRegistry registry,
            ConchSync sync,
            SessionManager sessions
    ) {
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
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                "key", "key", new ApiKeyScope(java.util.Set.of(), java.util.Set.of(), false));
        return invoker.invoke(
                principal, "legacy_oa", "list_todo", Map.of(), Optional.empty(), Optional.empty(), "trace-repair");
    }

    private static void tamper(OpenCrab crab) throws Exception {
        Path zip = crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip");
        byte[] bytes = Files.readAllBytes(zip);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(zip, bytes);
    }

    private static Path current(OpenCrab crab) {
        return crab.paths.conches().resolve("legacy_oa").resolve("current");
    }

    private static ConchOutcome outcome(SyncReport report, String conch) {
        return report.conches().stream().filter(item -> conch.equals(item.conch())).findFirst().orElseThrow();
    }

    private static int auditCount(OpenCrab crab, String kind) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM config_audit WHERE kind = ?")) {
            statement.setString(1, kind);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static String auditRow(OpenCrab crab, String kind) throws Exception {
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT actor, target, detail FROM config_audit WHERE kind = ?")) {
            statement.setString(1, kind);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1) + " " + resultSet.getString(2) + " " + resultSet.getString(3);
            }
        }
    }

    private static ConchSync sync(FakeReef reef, OpenCrab crab, ConchRegistry registry) {
        TestSubscriptions.grantKnown(crab.dataSource);
        return new ConchSync(
                new SyncSettings(reef.base(), Optional.empty(), reef.keys().getPublic(), Duration.ofMinutes(5), INSTANCE),
                crab.paths,
                registry,
                crab.vault,
                crab.dataSource,
                Clock.systemUTC());
    }
}
