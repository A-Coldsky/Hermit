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
import java.sql.Statement;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.report.ReportSettings;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.Origin;

public class LocalConchBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void importRejectsClashSecretsSizeAndKeepsAccountsOnReplace() throws Exception {
        try (CrabRuntime runtime = open(temp.resolve("import"))) {
            byte[] yaml = ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            LocalConches.ImportResult imported = runtime.localConches().importBytes(yaml, Actors.CLI);
            assertThat(imported.replaced()).isFalse();
            assertThat(imported.conch()).isEqualTo("local_ping");
            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            byte[] stored = Files.readAllBytes(runtime.paths().localConches().resolve("local_ping.yaml"));

            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    ping("local_ping", "1.1.0").getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("将覆盖已有的本地规则包 local_ping。确认后再导入。")
                    .satisfies(ex -> {
                        LocalConchException failure = (LocalConchException) ex;
                        assertThat(failure.status()).isEqualTo(409);
                        assertThat(failure.code()).isEqualTo(ErrorCode.CONFLICT);
                    });
            assertThat(Files.readAllBytes(runtime.paths().localConches().resolve("local_ping.yaml"))).isEqualTo(stored);
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.0.0");

            LocalConches.ImportResult replaced = runtime.localConches().importBytes(
                    ping("local_ping", "1.1.0").getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(replaced.replaced()).isTrue();
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.1.0");
            assertThat(runtime.admin().listAccounts("local_ping")).extracting(account -> account.account())
                    .containsExactly("alice");

            runtime.registry().put(ConchParser.parse(ping("official_ping", "1.0.0")), Origin.OFFICIAL);
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    ping("official_ping", "2.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("与已安装的正式规则包重名")
                    .satisfies(ex -> {
                        LocalConchException failure = (LocalConchException) ex;
                        assertThat(failure.status()).isEqualTo(409);
                        assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    });
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    ping("official_ping", "2.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI, true))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("与已安装的正式规则包重名");

            assertThatThrownBy(() -> runtime.localConches().importBytes(secretConfig(), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("规则包校验失败")
                    .satisfies(ex -> {
                        LocalConchException failure = (LocalConchException) ex;
                        assertThat(failure.status()).isEqualTo(400);
                        assertThat(failure.errors()).extracting(issue -> issue.code())
                                .contains(ValidationCodes.CONFIG_SECRET);
                    });

            assertThatThrownBy(() -> runtime.localConches().importBytes(new byte[] {(byte) 0xff}, Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("yaml is not valid UTF-8");
            assertThatThrownBy(() -> runtime.localConches().importBytes(new byte[LocalConches.MAX_BYTES + 1], Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("yaml exceeds 1 MiB");

            try (Connection connection = runtime.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO subscriptions (conch, subscribed_at, actor) VALUES ('subscribed_ping', CURRENT_TIMESTAMP, 'cli')");
            }
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    ping("subscribed_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("与已订阅的规则包重名");

            try (Connection connection = runtime.dataSource().getConnection();
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT target, detail FROM config_audit WHERE kind = ? ORDER BY id")) {
                query.setString(1, "LOCAL_CONCH_IMPORTED");
                try (ResultSet resultSet = query.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualTo("LOCAL/local_ping");
                    assertThat(resultSet.getString(2)).isEqualTo("{\"conch\":\"local_ping\",\"version\":\"1.0.0\"}");
                }
            }
        }
    }

    @Test
    void reloadFollowsTheDirectoryAndExportMatchesTheFile() throws Exception {
        Path root = temp.resolve("reload");
        try (CrabRuntime runtime = open(root)) {
            Path dir = runtime.paths().localConches();
            Files.writeString(dir.resolve("local_ping.yaml"), ping("local_ping", "1.0.0"));
            Files.writeString(dir.resolve("broken.yaml"), ":\n");
            Files.writeString(dir.resolve("renamed.yaml"), ping("local_ping", "1.0.0"));
            Path outside = root.resolve("outside.yaml");
            Files.writeString(outside, ping("linked_ping", "1.0.0"));
            Files.createSymbolicLink(dir.resolve("linked_ping.yaml"), outside);

            runtime.loadInstalledConches();
            assertThat(runtime.registry().origin("local_ping")).isEqualTo(Origin.LOCAL);
            assertThat(runtime.registry().find("broken")).isEmpty();
            assertThat(runtime.registry().find("renamed")).isEmpty();
            assertThat(runtime.registry().find("linked_ping")).isEmpty();
            assertThat(runtime.localConches().packages())
                    .anyMatch(pkg -> "local_ping".equals(pkg.conch()) && pkg.loaded() && !pkg.broken());
            assertThat(runtime.localConches().packages())
                    .anyMatch(pkg -> "broken".equals(pkg.conch()) && pkg.broken()
                            && pkg.reason().contains("broken.yaml")
                            && pkg.reason().contains("broken")
                            && pkg.reason().contains("无法解析"));
            assertThat(runtime.localConches().packages())
                    .anyMatch(pkg -> "renamed".equals(pkg.conch()) && pkg.broken()
                            && pkg.reason().contains("renamed.yaml")
                            && pkg.reason().contains("local_ping")
                            && pkg.reason().contains("与文件名不一致"));
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "linked_ping".equals(pkg.conch()));

            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            Files.writeString(dir.resolve("local_ping.yaml"), ping("local_ping", "1.0.0").replace("只在本机。", "仍是这一版。"));
            runtime.localConches().reload(Actors.CLI);
            int imported = auditCount(runtime, "LOCAL_CONCH_IMPORTED");

            Files.writeString(dir.resolve("local_ping.yaml"), ping("local_ping", "1.2.0"));
            Files.writeString(dir.resolve("local_two.yaml"), ping("local_two", "1.0.0"));
            Files.delete(dir.resolve("broken.yaml"));
            var results = runtime.localConches().reload(Actors.CLI);
            assertThat(results).anyMatch(row -> "local_ping.yaml".equals(row.file()) && row.ok() && "1.2.0".equals(row.version()));
            assertThat(results).anyMatch(row -> "local_two.yaml".equals(row.file()) && row.ok());
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.2.0");
            assertThat(runtime.registry().origin("local_two")).isEqualTo(Origin.LOCAL);
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "broken".equals(pkg.conch()));
            assertThat(runtime.localConches().packages()).anyMatch(pkg -> "renamed".equals(pkg.conch()) && pkg.broken());
            assertThat(auditCount(runtime, "LOCAL_CONCH_IMPORTED")).isGreaterThan(imported);

            Files.delete(dir.resolve("local_ping.yaml"));
            runtime.localConches().reload(Actors.CLI);
            assertThat(runtime.registry().find("local_ping")).isEmpty();
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "local_ping".equals(pkg.conch()));
            assertThat(accountOrigins(runtime, "local_ping")).containsExactly("LOCAL");
            assertThat(auditCount(runtime, "LOCAL_CONCH_DELETED")).isPositive();

            LocalConches.Export export = runtime.localConches().export("local_two");
            assertThat(export.bytes()).isEqualTo(Files.readAllBytes(dir.resolve("local_two.yaml")));
            ConchRegistry check = new ConchRegistry();
            assertThat(check.inspect(ConchParser.parse(new String(export.bytes(), StandardCharsets.UTF_8)))).isEmpty();
            assertThat(ConchValidator.validate(
                    ConchParser.parse(new String(export.bytes(), StandardCharsets.UTF_8)),
                    ConchValidator.Context.of(CrabVersion.CURRENT, Set.of("request", "extract", "assert", "set", "script")))
                    .errors()).isEmpty();
        }
    }

    @Test
    void callsRecordLocalSourceAndSkipTide() throws Exception {
        try (CrabRuntime runtime = open(temp.resolve("calls"))) {
            runtime.localConches().importBytes(ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.localConches().importBytes(save("local_save", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.registry().put(ConchParser.parse(ping("official_ping", "1.0.0")));
            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            runtime.admin().createAccount("local_save", "alice", Actors.CLI);
            runtime.admin().createAccount("official_ping", "alice", Actors.CLI);
            runtime.setCapabilityDisabled("local_ping", "ping", true, Actors.CLI);
            assertThat(originOfSwitch(runtime, "local_ping")).isEqualTo("LOCAL");
            runtime.setCapabilityDisabled("local_ping", "ping", false, Actors.CLI);

            CreatedApiKey openKey = runtime.admin().createApiKey("all", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
            CreatedApiKey explicitKey = runtime.admin().createApiKey(
                    "explicit", ApiKeyScope.explicit(true, Set.of(), Set.of(), false), Actors.CLI);
            CreatedApiKey readOnlyKey = runtime.admin().createApiKey(
                    "reader", ApiKeyScope.explicit(true, Set.of(), Set.of(), true), Actors.CLI);
            CreatedApiKey namedKey = runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping", "official_ping"), Set.of(), false), Actors.CLI);
            CreatedApiKey narrowKey = runtime.admin().createApiKey(
                    "narrow", new ApiKeyScope(Set.of("official_ping"), Set.of(), false), Actors.CLI);
            CreatedApiKey actionKey = runtime.admin().createApiKey(
                    "action", new ApiKeyScope(Set.of(), Set.of("local_ping.ping"), false), Actors.CLI);
            ApiKeyPrincipal openPrincipal = runtime.apiKeys().authenticate(openKey.plaintext()).orElseThrow();
            ApiKeyPrincipal explicitPrincipal = runtime.apiKeys().authenticate(explicitKey.plaintext()).orElseThrow();
            ApiKeyPrincipal readOnlyPrincipal = runtime.apiKeys().authenticate(readOnlyKey.plaintext()).orElseThrow();
            ApiKeyPrincipal namedPrincipal = runtime.apiKeys().authenticate(namedKey.plaintext()).orElseThrow();
            ApiKeyPrincipal narrowPrincipal = runtime.apiKeys().authenticate(narrowKey.plaintext()).orElseThrow();
            ApiKeyPrincipal actionPrincipal = runtime.apiKeys().authenticate(actionKey.plaintext()).orElseThrow();
            ReportQueue reports = new ReportQueue(
                    new ReportSettings(true, URI.create("http://127.0.0.1:9/"), Optional.empty(), UUID.randomUUID(), "1.0.0"),
                    runtime.dataSource(),
                    Clock.systemUTC());
            runtime.invoker().bindHooks(reports);

            InvokeOutcome openLocal = runtime.invoker().invoke(
                    openPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-open");
            assertThat(openLocal.status()).isEqualTo(200);
            InvokeOutcome explicitLocal = runtime.invoker().invoke(
                    explicitPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-explicit-local");
            assertThat(explicitLocal.status()).isEqualTo(200);
            InvokeOutcome explicitOfficial = runtime.invoker().invoke(
                    explicitPrincipal, "official_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-explicit-official");
            assertThat(explicitOfficial.status()).isEqualTo(200);
            InvokeOutcome readOnlyLocal = runtime.invoker().invoke(
                    readOnlyPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-readonly");
            assertThat(readOnlyLocal.status()).isEqualTo(200);
            InvokeOutcome readOnlyWrite = runtime.invoker().invoke(
                    readOnlyPrincipal, "local_save", "save", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-readonly-write");
            assertThat(readOnlyWrite.status()).isEqualTo(403);
            InvokeOutcome narrowLocal = runtime.invoker().invoke(
                    narrowPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-narrow");
            assertThat(narrowLocal.status()).isEqualTo(403);
            InvokeOutcome actionLocal = runtime.invoker().invoke(
                    actionPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-action");
            assertThat(actionLocal.status()).isEqualTo(403);
            InvokeOutcome local = runtime.invoker().invoke(
                    namedPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-local");
            assertThat(local.status()).isEqualTo(200);
            InvokeOutcome official = runtime.invoker().invoke(
                    namedPrincipal, "official_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-official");
            assertThat(official.status()).isEqualTo(200);

            assertThat(callSource(runtime, "trace-open")).containsExactly("LOCAL", "LOCAL");
            assertThat(callSource(runtime, "trace-explicit-local")).containsExactly("LOCAL", "LOCAL");
            assertThat(callSource(runtime, "trace-readonly")).containsExactly("LOCAL", "LOCAL");
            assertThat(callSource(runtime, "trace-local")).containsExactly("LOCAL", "LOCAL");
            assertThat(callSource(runtime, "trace-official")).containsExactly("OFFICIAL", "REEF");
            reports.flushMemory();
            assertThat(hourlyConches(runtime)).contains("official_ping").doesNotContain("local_ping", "local_save");
            assertThat(reportConches(runtime)).doesNotContain("local_ping", "local_save");
        }
    }

    @Test
    void brokenFileIsListedNotCallableAndCanBeReimported() throws Exception {
        Path root = temp.resolve("broken-reload");
        try (CrabRuntime runtime = open(root)) {
            runtime.localConches().importBytes(ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.localConches().importBytes(ping("local_two", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            runtime.admin().createAccount("local_two", "alice", Actors.CLI);
            CreatedApiKey namedKey = runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping", "local_two"), Set.of(), false), Actors.CLI);
            ApiKeyPrincipal principal = runtime.apiKeys().authenticate(namedKey.plaintext()).orElseThrow();
            assertThat(runtime.invoker().invoke(
                    principal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-ok")
                    .status()).isEqualTo(200);

            Path dir = runtime.paths().localConches();
            Path pingFile = dir.resolve("local_ping.yaml");
            Files.writeString(pingFile, ":\n");
            int deleted = auditCount(runtime, "LOCAL_CONCH_DELETED");
            var results = runtime.localConches().reload(Actors.CLI);

            assertThat(runtime.registry().find("local_ping")).isEmpty();
            assertThat(runtime.registry().find("local_two").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readString(pingFile)).isEqualTo(":\n");
            try (var children = Files.list(dir)) {
                assertThat(children.map(path -> path.getFileName().toString()).toList())
                        .containsExactlyInAnyOrder("local_ping.yaml", "local_two.yaml");
            }
            assertThat(auditCount(runtime, "LOCAL_CONCH_DELETED")).isEqualTo(deleted);
            assertThat(results).anyMatch(row -> "local_ping.yaml".equals(row.file())
                    && !row.ok()
                    && "local_ping".equals(row.conch())
                    && row.issues().get(0).message().contains("文件 local_ping.yaml")
                    && row.issues().get(0).message().contains("规则包 local_ping")
                    && row.issues().get(0).message().contains("无法解析")
                    && !row.issues().get(0).message().contains("conch validation failed"));
            LocalConches.LocalPackage broken = runtime.localConches().packages().stream()
                    .filter(pkg -> "local_ping".equals(pkg.conch()))
                    .findFirst()
                    .orElseThrow();
            assertThat(broken.broken()).isTrue();
            assertThat(broken.loaded()).isFalse();
            assertThat(broken.version()).isNull();
            assertThat(broken.file()).isEqualTo("local_ping.yaml");
            assertThat(broken.reason()).contains("文件 local_ping.yaml").contains("规则包 local_ping").contains("无法解析");
            assertThat(runtime.localConches().packages())
                    .anyMatch(pkg -> "local_two".equals(pkg.conch()) && pkg.loaded() && "1.0.0".equals(pkg.version()));
            assertThat(runtime.invoker().invoke(
                    principal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-broken")
                    .status()).isEqualTo(404);
            assertThat(runtime.invoker().invoke(
                    principal, "local_two", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-two")
                    .status()).isEqualTo(200);
            assertThatThrownBy(() -> runtime.subscribe("local_ping", Actors.CLI))
                    .isInstanceOf(SubscriptionException.class)
                    .hasMessage("与本机本地规则包同名");

            runtime.localConches().delete("local_ping", Actors.CLI);
            assertThat(pingFile).doesNotExist();
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "local_ping".equals(pkg.conch()));
            assertThat(accountOrigins(runtime, "local_ping")).containsExactly("LOCAL");

            LocalConches.ImportResult again = runtime.localConches().importBytes(
                    ping("local_ping", "1.0.1").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            assertThat(again.replaced()).isFalse();
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.0.1");
            assertThat(runtime.localConches().packages())
                    .anyMatch(pkg -> "local_ping".equals(pkg.conch()) && pkg.loaded() && !pkg.broken());
            assertThat(runtime.invoker().invoke(
                    principal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-again")
                    .status()).isEqualTo(200);
            assertThat(runtime.admin().listAccounts("local_ping")).extracting(account -> account.account())
                    .containsExactly("alice");
        }

        Path home = temp.resolve("broken-reload");
        Files.writeString(home.resolve("local-conches").resolve("local_two.yaml"), ":\n");
        try (CrabRuntime restarted = open(home)) {
            restarted.loadInstalledConches();
            assertThat(restarted.registry().find("local_ping")).isPresent();
            assertThat(restarted.registry().find("local_two")).isEmpty();
            assertThat(restarted.localConches().packages())
                    .anyMatch(pkg -> "local_two".equals(pkg.conch()) && pkg.broken()
                            && pkg.reason().contains("local_two.yaml")
                            && pkg.reason().contains("无法解析"));
            assertThat(Files.readString(home.resolve("local-conches").resolve("local_two.yaml"))).isEqualTo(":\n");
        }
    }

    @Test
    void mismatchedNameDoesNotOverwriteAndImportRequiresConfirm() throws Exception {
        Path root = temp.resolve("name-mismatch");
        try (CrabRuntime runtime = open(root)) {
            byte[] yaml = ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            runtime.localConches().importBytes(yaml, Actors.CLI);
            Path dir = runtime.paths().localConches();
            Path installed = dir.resolve("local_ping.yaml");
            byte[] stored = Files.readAllBytes(installed);
            Files.writeString(dir.resolve("b.yaml"), ping("local_ping", "9.9.9"));
            Files.writeString(dir.resolve("renamed.yaml"), ping("local_ping", "9.9.9"));
            byte[] huge = new byte[LocalConches.MAX_BYTES + 1];
            Files.write(dir.resolve("local_big.yaml"), huge);

            var results = runtime.localConches().reload(Actors.CLI);
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.0.0");
            assertThat(Files.readAllBytes(installed)).isEqualTo(stored);
            assertThat(results).anyMatch(row -> "b.yaml".equals(row.file())
                    && !row.ok()
                    && row.conch() == null
                    && row.issues().get(0).message().contains("文件 b.yaml")
                    && row.issues().get(0).message().contains("规则包是 local_ping")
                    && row.issues().get(0).message().contains("与文件名不一致")
                    && !row.issues().get(0).message().contains("file name does not match conch"));
            assertThat(results).anyMatch(row -> "local_big.yaml".equals(row.file()) && !row.ok()
                    && row.issues().get(0).message().contains("文件 local_big.yaml")
                    && row.issues().get(0).message().contains("规则包 local_big")
                    && row.issues().get(0).message().contains("超过 1 MiB"));
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "b".equals(pkg.conch()));
            LocalConches.LocalPackage renamed = runtime.localConches().packages().stream()
                    .filter(pkg -> "renamed".equals(pkg.conch()))
                    .findFirst()
                    .orElseThrow();
            assertThat(renamed.broken()).isTrue();
            assertThat(renamed.reason()).contains("renamed.yaml").contains("local_ping").contains("与文件名不一致");
            assertThat(runtime.localConches().packages())
                    .anyMatch(pkg -> "local_big".equals(pkg.conch()) && pkg.broken()
                            && pkg.reason().contains("超过 1 MiB"));
            assertThat(runtime.registry().find("local_big")).isEmpty();
            assertThat(runtime.registry().find("renamed")).isEmpty();

            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    ping("local_ping", "2.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("将覆盖已有的本地规则包 local_ping。确认后再导入。")
                    .extracting(ex -> ((LocalConchException) ex).code())
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(Files.readAllBytes(installed)).isEqualTo(stored);
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.0.0");

            LocalConches.ImportResult replaced = runtime.localConches().importBytes(
                    ping("local_ping", "2.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(replaced.replaced()).isTrue();
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("2.0.0");

            runtime.localConches().delete("renamed", Actors.CLI);
            runtime.localConches().delete("local_big", Actors.CLI);
            assertThat(dir.resolve("renamed.yaml")).doesNotExist();
            assertThat(dir.resolve("local_big.yaml")).doesNotExist();
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "renamed".equals(pkg.conch()));
            assertThat(runtime.localConches().packages()).noneMatch(pkg -> "local_big".equals(pkg.conch()));
            assertThat(runtime.registry().find("local_ping")).isPresent();
        }
    }

    @Test
    void rejectedImportKeepsChineseReasonsAndDoesNotStoreTheFile() {
        try (CrabRuntime runtime = open(temp.resolve("reject-lines"))) {
            String marker = "zz_body_marker_should_not_leak";
            String missing = ping("local_ping", "1.0.0")
                    .replace("min_crab_version: 1.0.0\n", "")
                    .replace("title: 本地探测", "title: " + marker);
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    missing.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("规则包校验失败")
                    .satisfies(error -> {
                        LocalConchException failure = (LocalConchException) error;
                        assertThat(failure.errors()).singleElement().satisfies(issue -> {
                            assertThat(issue.code()).isEqualTo("PARSE");
                            assertThat(issue.path()).isEqualTo("min_crab_version");
                            assertThat(issue.message()).isEqualTo("min_crab_version: 缺少 min_crab_version");
                            assertThat(issue.line()).isNull();
                            assertThat(issue.message()).doesNotContain(marker);
                        });
                    });
            assertThat(runtime.paths().localConches().resolve("local_ping.yaml")).doesNotExist();
            assertThat(runtime.registry().find("local_ping")).isEmpty();

            String driver = ping("bad_driver", "1.0.0")
                    .replace("driver: http", "driver: shell")
                    .replace("title: 本地探测", "title: " + marker);
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    driver.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .satisfies(error -> {
                        LocalConchException failure = (LocalConchException) error;
                        assertThat(failure.errors()).anySatisfy(issue -> {
                            assertThat(issue.code()).isEqualTo("DRIVER");
                            assertThat(issue.path()).isEqualTo("driver");
                            assertThat(issue.message()).isEqualTo("driver 只允许 http 或 mcp");
                            assertThat(issue.line()).isEqualTo(sourceLine(driver, "driver:"));
                            assertThat(issue.message()).doesNotContain(marker).doesNotContain("shell");
                        });
                    });
            assertThat(runtime.paths().localConches().resolve("bad_driver.yaml")).doesNotExist();

            String broken = "conch: \"unterminated\nversion: 1.0.0\n";
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    broken.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .satisfies(error -> {
                        LocalConchException failure = (LocalConchException) error;
                        assertThat(failure.errors()).singleElement().satisfies(issue -> {
                            assertThat(issue.code()).isEqualTo("PARSE");
                            assertThat(issue.message()).contains("YAML 无法解析").doesNotContain("unterminated");
                            assertThat(issue.line()).isNotNull();
                            assertThat(issue.message()).contains("第 " + issue.line() + " 行");
                        });
                    });
        }
    }

    private static int sourceLine(String text, String token) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(token)) {
                return i + 1;
            }
        }
        throw new AssertionError(token);
    }

    private static CrabRuntime open(Path root) {
        return CrabRuntime.open(new CrabPaths(root), CrabConfig.defaults(), name -> null);
    }

    private static java.util.List<String> accountOrigins(CrabRuntime runtime, String conch) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT origin FROM accounts WHERE conch = ?")) {
            query.setString(1, conch);
            try (ResultSet resultSet = query.executeQuery()) {
                java.util.ArrayList<String> origins = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    origins.add(resultSet.getString(1));
                }
                return origins;
            }
        }
    }

    private static int auditCount(CrabRuntime runtime, String action) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM config_audit WHERE kind = ?")) {
            query.setString(1, action);
            try (ResultSet resultSet = query.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private static String originOfSwitch(CrabRuntime runtime, String conch) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT origin FROM capability_switches WHERE conch = ?")) {
            query.setString(1, conch);
            try (ResultSet resultSet = query.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private static java.util.List<String> callSource(CrabRuntime runtime, String traceId) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT origin, source FROM call_logs WHERE trace_id = ?")) {
            query.setString(1, traceId);
            try (ResultSet resultSet = query.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return java.util.List.of(resultSet.getString(1), resultSet.getString(2));
            }
        }
    }

    private static java.util.List<String> hourlyConches(CrabRuntime runtime) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT conch FROM hourly_counts")) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names;
        }
    }

    private static java.util.List<String> reportConches(CrabRuntime runtime) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT conch FROM report_queue")) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names;
        }
    }

    private static byte[] secretConfig() {
        return """
                conch: secret_ping
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 本地探测
                description: 只在本机。
                credentials: {}
                config:
                  access_value: { type: secret, label: Access value }
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                      - set:
                          marker: ok
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                """.getBytes(StandardCharsets.UTF_8);
    }

    /** 非只读的本地写入能力。全范围只读 Key 不能调用它。 */
    public static String save(String name, String version) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 本地写入
                description: 只在本机。
                credentials: {}
                actions:
                  save:
                    title: 写入
                    description: 写入标记。
                    method: POST
                    steps:
                      - set:
                          marker: ok
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地写入
                """.formatted(name, version);
    }

    /** 同名能力在本地也要拦下，和 Reef 上传同一条规则，并给出行号。 */
    @Test
    void duplicateActionNamesAreRejectedLocallyWithALine() throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp.resolve("duplicate-actions"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource);
            String once = ping("dup_ping", "1.0.0");
            String action = once.substring(once.indexOf("  ping:"));
            String twice = once + action.replace("返回标记。", "第二次声明。");
            LocalConches.CheckResult checked = local.check(twice.getBytes(StandardCharsets.UTF_8));
            assertThat(checked.ok()).isFalse();
            assertThat(checked.errors()).anySatisfy(issue -> {
                assertThat(issue.code()).isEqualTo("DUPLICATE_ACTION");
                assertThat(issue.line()).isNotNull();
            });
            assertThatThrownBy(() -> local.importBytes(twice.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class);
            assertThat(crab.paths.localConches().resolve("dup_ping.yaml")).doesNotExist();
        }
    }

    @Test
    void duplicateScriptLibrariesAreRejectedLocallyWithALine() throws Exception {
        try (OpenCrab crab = OpenCrab.open(temp.resolve("duplicate-scripts"))) {
            LocalConches local = new LocalConches(crab.paths, new ConchRegistry(), crab.dataSource);
            String yaml = """
                    conch: script_dup
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.2.0
                    title: 标题
                    description: 说明
                    scripts:
                      lib: |
                        function tag() { return "A"; }
                      lib: |
                        function tag() { return "source-marker"; }
                    credentials: {}
                    actions:
                      run:
                        title: 能力
                        description: 说明
                        output:
                          value: { type: string, description: 结果, value: ok }
                        examples:
                          - input: {}
                            output: { value: ok }
                        notes: 注意
                        related: [run]
                        steps:
                          - script:
                              use: [lib]
                              code: |
                                return { value: tag() };
                    """;
            LocalConches.CheckResult checked = local.check(yaml.getBytes(StandardCharsets.UTF_8));
            assertThat(checked.ok()).isFalse();
            assertThat(checked.errors()).anySatisfy(issue -> {
                assertThat(issue.code()).isEqualTo("SCRIPT_STEP");
                assertThat(issue.message()).isEqualTo("脚本库 lib 重复");
                assertThat(issue.message()).doesNotContain("source-marker");
                assertThat(issue.line()).isNotNull();
            });
            assertThatThrownBy(() -> local.importBytes(yaml.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class);
            assertThat(crab.paths.localConches().resolve("script_dup.yaml")).doesNotExist();
        }
    }

    public static String ping(String name, String version) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 本地探测
                description: 只在本机。
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                      - set:
                          marker: ok
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                    mcp: { read_only: true }
                """.formatted(name, version);
    }
}
