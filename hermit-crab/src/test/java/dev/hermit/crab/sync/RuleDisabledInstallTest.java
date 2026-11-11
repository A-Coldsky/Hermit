package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.Actors;
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
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
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

            assertThatThrownBy(() -> sync.rollback("legacy_oa", Actors.CONSOLE))
                    .isInstanceOf(UpdateException.class)
                    .hasMessageContaining("已停用")
                    .hasMessageContaining("当前可用的版本没有改动");
            assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(Files.readString(crab.paths.conches().resolve("legacy_oa/current")).trim()).isEqualTo("1.0.1");
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip"))).isTrue();
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
