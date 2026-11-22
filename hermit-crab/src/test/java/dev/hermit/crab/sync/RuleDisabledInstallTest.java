package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.testing.FakeReef;

class RuleDisabledInstallTest {

    @TempDir
    Path temp;

    @Test
    void anUnloadableInstallStaysOnDiskAndAHigherLegalVersionReplacesIt() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("official"))) {
            byte[] yaml = blocked("1.0.0").getBytes(StandardCharsets.UTF_8);
            byte[] zip = ConchPackage.write(yaml, reef.keys().getPrivate());
            ConchFiles.install(crab.paths, "legacy_oa", "1.0.0", zip, yaml);
            ConchFiles.writeCurrent(crab.paths, "legacy_oa", "1.0.0");
            new SyncStore(crab.dataSource).recordInstalled("legacy_oa", "1.0.0", Instant.parse("2026-11-01T00:00:00Z"));
            TestSubscriptions.grantKnown(crab.dataSource);

            Logger logger = Logger.getLogger(InstalledConches.class.getName());
            MemoryLog log = MemoryLog.attach(logger);
            ConchRegistry registry = new ConchRegistry();
            try {
                LoadReport loaded = InstalledConches.loadAll(crab.paths, reef.keys().getPublic(), registry);
                assertThat(loaded.loaded()).doesNotContain("legacy_oa");
                assertThat(registry.find("legacy_oa")).isEmpty();
                assertThat(log.text()).contains("REGISTRY_REJECTED").contains("UNDECLARED_VARIABLE").contains("lines=");
                assertThat(log.text()).containsPattern("lines=[0-9]");
            } finally {
                logger.removeHandler(log);
            }
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            SyncReport waiting = sync.syncOnce();
            assertThat(waiting.conches()).anySatisfy(outcome -> {
                assertThat(outcome.conch()).isEqualTo("legacy_oa");
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.RULE_DISABLED);
                assertThat(outcome.reason()).isEqualTo(SyncReasons.RULE_DISABLED);
            });
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.1"))).isFalse();

            ConchCatalog.Entry row = sync.catalog().conches().stream()
                    .filter(entry -> "legacy_oa".equals(entry.conch()))
                    .findFirst()
                    .orElseThrow();
            assertThat(row.installed()).isTrue();
            assertThat(row.repairRequired()).isFalse();
            assertThat(row.ruleDisabled()).isTrue();
            assertThat(row.message()).contains("已停用").contains("能力不能引用登录步骤里的变量").contains("第");
            assertThat(row.message()).doesNotContain("Reef 已停用").doesNotContain("CONCH_DISABLED");

            UpdateResult updated = sync.update("legacy_oa", Actors.CONSOLE);
            assertThat(updated.outcome().kind()).isEqualTo(OutcomeKind.INSTALLED);
            assertThat(updated.outcome().version()).isEqualTo("1.0.1");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.0");
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.1");

            sync.syncOnce();
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.0");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();

            ConchSync.PreviousState previous = sync.previousState("legacy_oa");
            assertThat(previous.version()).isEqualTo("1.0.0");
            assertThat(previous.disabled()).isTrue();
            assertThat(previous.present()).isTrue();
            assertThatThrownBy(() -> sync.rollback("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("上一版 v1.0.0 装不上")
                    .hasMessageContaining("当前版本没有改动")
                    .hasMessageNotContaining("这版规则暂时不能调用");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.1");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
        }
    }

    @Test
    void updatingToAnInvalidVersionKeepsTheLoadedOne() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("blocked-update"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            reef.publish(blocked("1.0.1"));
            SyncReport offered = sync.syncOnce();
            assertThat(sync.visibleStatus(offered).conches()).anySatisfy(outcome -> {
                assertThat(outcome.conch()).isEqualTo("legacy_oa");
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPDATE_AVAILABLE);
            });
            assertThatThrownBy(() -> sync.update("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .satisfies(error -> {
                        UpdateException update = (UpdateException) error;
                        assertThat(update.status()).isEqualTo(409);
                        assertThat(update.code()).isEqualTo(ErrorCode.CONFLICT);
                        assertThat(update.getMessage())
                                .startsWith("新版本 v1.0.1 装不上：")
                                .contains("第")
                                .contains("本机仍是可用的上一版 v1.0.0")
                                .doesNotContain("输入无效")
                                .doesNotContain("已停用");
                    });
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.0");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.1"))).isFalse();
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
            ConchCatalog.Entry after = sync.catalog().conches().stream()
                    .filter(entry -> "legacy_oa".equals(entry.conch()))
                    .findFirst()
                    .orElseThrow();
            assertThat(after.updateAvailable()).isFalse();
            // 概览读的是上次同步的结果：更新失败后不能再催用户去更新这个版本。
            assertThat(sync.visibleStatus(offered).conches()).anySatisfy(outcome -> {
                assertThat(outcome.conch()).isEqualTo("legacy_oa");
                assertThat(outcome.version()).isEqualTo("1.0.1");
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.REJECTED);
                assertThat(outcome.reason()).isEqualTo(SyncReasons.UPDATE_FAILED);
            });
        }
    }

    @Test
    void aRejectedUpdateKeepsThePreviousDirectory() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("keep-previous"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CONSOLE).outcome().version()).isEqualTo("1.0.1");
            reef.publish(blocked("1.0.2"));
            sync.syncOnce();
            assertThatThrownBy(() -> sync.update("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("新版本 v1.0.2 装不上")
                    .hasMessageContaining("本机仍是可用的上一版 v1.0.1");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.2"))).isFalse();
            ConchSync.PreviousState previous = sync.previousState("legacy_oa");
            assertThat(previous.version()).isEqualTo("1.0.0");
            assertThat(previous.disabled()).isFalse();
            assertThat(previous.present()).isTrue();
            assertThat(previous.rollbackable()).isTrue();
        }
    }

    @Test
    void aMissingPreviousFileStaysRecordedAndCannotRollBack() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("missing-previous"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CONSOLE).outcome().version()).isEqualTo("1.0.1");
            Files.delete(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"));
            ConchSync.PreviousState previous = sync.previousState("legacy_oa");
            assertThat(previous.version()).isEqualTo("1.0.0");
            assertThat(previous.disabled()).isFalse();
            assertThat(previous.present()).isFalse();
            assertThat(previous.rollbackable()).isFalse();
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.0");
            assertThatThrownBy(() -> sync.rollback("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("上一版无法读取")
                    .hasMessageContaining("当前版本没有改动");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(sync.previousState("legacy_oa").version()).isEqualTo("1.0.0");
            assertThat(sync.previousState("legacy_oa").present()).isFalse();
        }
    }

    @Test
    void aDisabledPreviousVersionIsMarkedAndRollbackStaysPut() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("both-disabled"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CONSOLE).outcome().version()).isEqualTo("1.0.1");
            Files.write(crab.paths.conches().resolve("legacy_oa/1.0.1/package.zip"),
                    ConchPackage.write(blocked("1.0.1").getBytes(StandardCharsets.UTF_8), reef.keys().getPrivate()));
            Files.write(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"),
                    ConchPackage.write(blocked("1.0.0").getBytes(StandardCharsets.UTF_8), reef.keys().getPrivate()));
            ConchRegistry restarted = new ConchRegistry();
            ConchSync again = new ConchSync(
                    settings(reef), crab.paths, restarted, crab.vault, crab.dataSource, Clock.systemUTC());
            again.syncOnce();
            ConchSync.DisabledRule disabled = again.disabledRule("legacy_oa").orElseThrow();
            assertThat(disabled.previousVersion()).isEqualTo("1.0.0");
            assertThat(disabled.previousDisabled()).isTrue();
            assertThat(disabled.previousPresent()).isTrue();
            assertThat(disabled.fixHint()).isEqualTo(RuleDisabled.WAIT_FOR_RELEASE);
            assertThat(disabled.fixHint()).doesNotContain("退回");
            assertThatThrownBy(() -> again.rollback("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("上一版 v1.0.0 装不上")
                    .hasMessageContaining("当前版本没有改动")
                    .hasMessageNotContaining("这版规则暂时不能调用");
            assertThat(restarted.find("legacy_oa")).isEmpty();
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.1");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.1/package.zip"))).isTrue();
        }
    }

    /** 同版本重装不能把当前版记成上一版；旧数据里上一版等于当前版时也当没有上一版。 */
    @Test
    void sameVersionReinstallDoesNotOfferRollbackToItself() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("self"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CLI).outcome().version()).isEqualTo("1.0.1");
            SyncStore store = new SyncStore(crab.dataSource);
            assertThat(store.previous("legacy_oa")).contains("1.0.0");

            // 当前版的包坏了、注册表里也没有：修复会重新下载同一个版本。
            Files.write(crab.paths.conches().resolve("legacy_oa/1.0.1/package.zip"), new byte[] {1, 2, 3});
            registry.remove("legacy_oa");
            assertThat(sync.update("legacy_oa", Actors.CLI).outcome().version()).isEqualTo("1.0.1");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(store.previous("legacy_oa")).contains("1.0.0");
            assertThat(sync.previousState("legacy_oa").version()).isEqualTo("1.0.0");

            // 旧版本 Crab 写下的「上一版 = 当前版」：读出来当没有。
            store.setPrevious("legacy_oa", "1.0.1");
            assertThat(sync.previousState("legacy_oa").version()).isNull();
            assertThatThrownBy(() -> sync.rollback("legacy_oa", Actors.CLI))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("没有可回退的上一版");
        }
    }

    @Test
    void rollbackKeepsOnlyTheVersionJustReplaced() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("rollback"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CLI).outcome().version()).isEqualTo("1.0.1");
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.0");

            assertThat(sync.rollback("legacy_oa", Actors.CLI).outcome().version()).isEqualTo("1.0.0");
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.1");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.2"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CLI).outcome().version()).isEqualTo("1.0.2");
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.0");
            sync.syncOnce();
            assertThat(new SyncStore(crab.dataSource).previous("legacy_oa")).contains("1.0.0");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.2/package.zip"))).isTrue();
        }
    }

    @Test
    void rollbackRecordsConchUpdatedAudit() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("rollback-audit"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(
                    settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CONSOLE).outcome().version()).isEqualTo("1.0.1");

            assertThat(sync.rollback("legacy_oa", Actors.CLI).outcome().version()).isEqualTo("1.0.0");

            List<String[]> rows = auditRows(crab, "CONCH_UPDATED");
            assertThat(rows).isNotEmpty();
            String[] last = rows.get(rows.size() - 1);
            assertThat(last[0]).isEqualTo(Actors.CLI);
            assertThat(last[1]).isEqualTo("OFFICIAL/legacy_oa");
            JsonNode detail = JSON.readTree(last[2]);
            assertThat(detail.path("from").asText()).isEqualTo("1.0.1");
            assertThat(detail.path("to").asText()).isEqualTo("1.0.0");
        }
    }

    @Test
    void ruleDisabledConchDroppedByReefBecomesDisabled() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("rule-disabled-drop"))) {
            byte[] yaml = blocked("1.0.0").getBytes(StandardCharsets.UTF_8);
            byte[] zip = ConchPackage.write(yaml, reef.keys().getPrivate());
            ConchFiles.install(crab.paths, "legacy_oa", "1.0.0", zip, yaml);
            ConchFiles.writeCurrent(crab.paths, "legacy_oa", "1.0.0");
            new SyncStore(crab.dataSource).recordInstalled("legacy_oa", "1.0.0", Instant.parse("2026-11-01T00:00:00Z"));
            TestSubscriptions.grantKnown(crab.dataSource);

            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(
                    settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            SyncReport waiting = sync.syncOnce();
            assertThat(waiting.conches()).anySatisfy(outcome -> {
                assertThat(outcome.conch()).isEqualTo("legacy_oa");
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.RULE_DISABLED);
            });

            reef.remove("legacy_oa");
            SyncReport dropped = sync.syncOnce();
            assertThat(dropped.conches()).anySatisfy(outcome -> {
                assertThat(outcome.conch()).isEqualTo("legacy_oa");
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.DISABLED);
                assertThat(outcome.reason()).isEqualTo(SyncReasons.DISABLED);
                assertThat(outcome.version()).isEqualTo("1.0.0");
            });
            assertThat(sync.disabledConches().contains("legacy_oa")).isTrue();
            assertThat(sync.disabledConches().version("legacy_oa")).isEqualTo("1.0.0");
            assertThat(auditRows(crab, "REEF_CONCH_DISABLED")).hasSize(1);
            assertThat(sync.visibleStatus(dropped).conches()).anySatisfy(outcome -> {
                assertThat(outcome.conch()).isEqualTo("legacy_oa");
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.DISABLED);
            });
            assertThatThrownBy(() -> sync.update("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .satisfies(error -> {
                        UpdateException update = (UpdateException) error;
                        assertThat(update.code()).isEqualTo(ErrorCode.CONCH_DISABLED);
                        assertThat(update.getMessage()).isEqualTo(DisabledConches.CALL_MESSAGE);
                    });
        }
    }

    private static List<String[]> auditRows(OpenCrab crab, String kind) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = crab.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT actor, target, detail FROM config_audit WHERE kind = ? ORDER BY id ASC")) {
            statement.setString(1, kind);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new String[] {resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)});
                }
            }
        }
        return rows;
    }

    @Test
    void aLocalPackageThatFailsValidationStaysAndSaysWhy() throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp.resolve("local"))) {
            Path file = crab.paths.localConches().resolve("legacy_oa.yaml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, blocked("1.0.0"));
            Logger logger = Logger.getLogger(LocalConches.class.getName());
            MemoryLog log = MemoryLog.attach(logger);
            try {
                LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource);
                local.reload(Actors.CLI);
                assertThat(local.packages()).singleElement().satisfies(pkg -> {
                    assertThat(pkg.broken()).isTrue();
                    assertThat(pkg.loaded()).isFalse();
                    assertThat(pkg.ruleDisabled()).isTrue();
                    assertThat(new String(local.export(pkg.conch()).bytes(), StandardCharsets.UTF_8)).contains("legacy_oa");
                    assertThat(pkg.reason()).contains("已停用").contains("第").contains("能力不能引用登录步骤里的变量");
                    assertThat(pkg.reason()).doesNotContain("文件损坏");
                });
                assertThat(log.text()).contains("UNDECLARED_VARIABLE").contains("lines=");
            } finally {
                logger.removeHandler(log);
            }
            assertThat(Files.exists(file)).isTrue();
        }
    }

    @Test
    void aFixedUpdateStillReportsTheDisabledPrevious() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("fixed-update"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = new ConchSync(settings(reef), crab.paths, registry, crab.vault, crab.dataSource, Clock.systemUTC());
            TestSubscriptions.grantKnown(crab.dataSource);
            sync.syncOnce();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            sync.syncOnce();
            assertThat(sync.update("legacy_oa", Actors.CONSOLE).outcome().version()).isEqualTo("1.0.1");
            Files.write(crab.paths.conches().resolve("legacy_oa/1.0.1/package.zip"),
                    ConchPackage.write(blocked("1.0.1").getBytes(StandardCharsets.UTF_8), reef.keys().getPrivate()));
            ConchRegistry restarted = new ConchRegistry();
            ConchSync again = new ConchSync(
                    settings(reef), crab.paths, restarted, crab.vault, crab.dataSource, Clock.systemUTC());
            again.syncOnce();
            assertThat(again.disabledRule("legacy_oa")).isPresent();
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.2"));
            again.syncOnce();
            assertThat(again.update("legacy_oa", Actors.CONSOLE).outcome().version()).isEqualTo("1.0.2");
            assertThat(restarted.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.2");
            ConchSync.PreviousState previous = again.previousState("legacy_oa");
            assertThat(previous.version()).isEqualTo("1.0.1");
            assertThat(previous.disabled()).isTrue();
            assertThat(previous.present()).isTrue();
            assertThat(previous.rollbackable()).isFalse();
            assertThatThrownBy(() -> again.rollback("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("上一版 v1.0.1 装不上");
            assertThat(restarted.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.2");
        }
    }

    @Test
    void detailOfALoadedVersionStillReturnsTheDisabledPrevious() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = CrabConfig.defaults()
                    .withReef(reef.base().toString(), null, Ed25519Keys.writePublicPem(reef.keys().getPublic()))
                    .withListen("127.0.0.1", 0);
            Path home = temp.resolve("detail-previous");
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> subscribed = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(subscribed.statusCode()).as(subscribed.body()).isEqualTo(200);
                reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
                HttpResponse<String> synced = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
                HttpResponse<String> updated = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/update", "{}");
                assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
                HttpResponse<String> created = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/accounts", "{\"name\":\"ada\"}");
                assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            }
            Files.write(new CrabPaths(home).conches().resolve("legacy_oa/1.0.1/package.zip"),
                    ConchPackage.write(blocked("1.0.1").getBytes(StandardCharsets.UTF_8), reef.keys().getPrivate()));
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                JsonNode whileDisabled = data(send(http, server.port(), "GET", "/console/api/conches/legacy_oa/accounts", null));
                assertThat(whileDisabled.path("accounts").get(0).path("name").asText()).isEqualTo("ada");
                HttpResponse<String> rolled = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/rollback", "{}");
                assertThat(rolled.statusCode()).as(rolled.body()).isEqualTo(200);
                JsonNode detail = data(send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null));
                assertThat(detail.path("version").asText()).isEqualTo("1.0.0");
                assertThat(detail.path("ruleDisabled").asBoolean()).isFalse();
                assertThat(detail.path("previousVersion").asText()).isEqualTo("1.0.1");
                assertThat(detail.path("previousDisabled").asBoolean()).isTrue();
                assertThat(detail.path("previousPresent").asBoolean()).isTrue();
                JsonNode listed = data(send(http, server.port(), "GET", "/console/api/conches", null))
                        .path("conches").get(0);
                assertThat(listed.path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(listed.path("previousVersion").asText()).isEqualTo("1.0.1");
                assertThat(listed.path("previousDisabled").asBoolean()).isTrue();
                assertThat(listed.path("previousPresent").asBoolean()).isTrue();

                Files.delete(new CrabPaths(home).conches().resolve("legacy_oa/1.0.1/package.zip"));
                JsonNode missing = data(send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null));
                assertThat(missing.path("previousVersion").asText()).isEqualTo("1.0.1");
                assertThat(missing.path("previousDisabled").asBoolean()).isFalse();
                assertThat(missing.path("previousPresent").asBoolean()).isFalse();
                JsonNode missingList = data(send(http, server.port(), "GET", "/console/api/conches", null))
                        .path("conches").get(0);
                assertThat(missingList.path("previousPresent").asBoolean()).isFalse();
                assertThat(missingList.path("previousDisabled").asBoolean()).isFalse();
                HttpResponse<String> blocked = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/rollback", "{}");
                assertThat(blocked.statusCode()).isEqualTo(409);
                assertThat(data(send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null))
                        .path("version").asText()).isEqualTo("1.0.0");
                assertThat(data(send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null))
                        .path("previousVersion").asText()).isEqualTo("1.0.1");
            }
        }
    }

    @Test
    void helpListsRollback() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"--help"},
                name -> null,
                InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("crab rollback <conch>");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    private static HttpResponse<String> send(HttpClient http, int port, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static SyncSettings settings(FakeReef reef) {
        return new SyncSettings(
                reef.base(),
                Optional.empty(),
                reef.keys().getPublic(),
                Duration.ofMinutes(5),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    }

    private static String blocked(String version) {
        return """
                conch: legacy_oa
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                    - extract:
                        token: { jsonpath: $.token }
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - request:
                          method: GET
                          path: "/{{token}}"
                """.formatted(version);
    }

    private static final class MemoryLog extends Handler {
        private final StringBuilder text = new StringBuilder();

        static MemoryLog attach(Logger logger) {
            MemoryLog log = new MemoryLog();
            logger.setLevel(Level.INFO);
            logger.addHandler(log);
            return log;
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() != null) {
                text.append(record.getMessage()).append('\n');
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String text() {
            return text.toString();
        }
    }
}
