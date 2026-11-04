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

            LocalConches.ImportResult replaced = runtime.localConches().importBytes(
                    ping("local_ping", "1.1.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            assertThat(replaced.replaced()).isTrue();
            assertThat(runtime.registry().find("local_ping").orElseThrow().version().toString()).isEqualTo("1.1.0");
            assertThat(runtime.admin().listAccounts("local_ping")).extracting(account -> account.account())
                    .containsExactly("alice");

            runtime.registry().put(ConchParser.parse(ping("official_ping", "1.0.0")), Origin.OFFICIAL);
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    ping("official_ping", "2.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
                    .hasMessage("与已安装的正式规则包重名")
                    .extracting(ex -> ((LocalConchException) ex).status())
                    .isEqualTo(409);

            assertThatThrownBy(() -> runtime.localConches().importBytes(secretConfig(), Actors.CLI))
                    .isInstanceOf(LocalConchException.class)
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
            assertThat(auditCount(runtime, "LOCAL_CONCH_IMPORTED")).isGreaterThan(imported);

            Files.delete(dir.resolve("local_ping.yaml"));
            runtime.localConches().reload(Actors.CLI);
            assertThat(runtime.registry().find("local_ping")).isEmpty();
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
            runtime.registry().put(ConchParser.parse(ping("official_ping", "1.0.0")));
            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            runtime.admin().createAccount("official_ping", "alice", Actors.CLI);
            runtime.setCapabilityDisabled("local_ping", "ping", true, Actors.CLI);
            assertThat(originOfSwitch(runtime, "local_ping")).isEqualTo("LOCAL");
            runtime.setCapabilityDisabled("local_ping", "ping", false, Actors.CLI);

            CreatedApiKey openKey = runtime.admin().createApiKey("all", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
            CreatedApiKey namedKey = runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping", "official_ping"), Set.of(), false), Actors.CLI);
            ApiKeyPrincipal openPrincipal = runtime.apiKeys().authenticate(openKey.plaintext()).orElseThrow();
            ApiKeyPrincipal namedPrincipal = runtime.apiKeys().authenticate(namedKey.plaintext()).orElseThrow();
            ReportQueue reports = new ReportQueue(
                    new ReportSettings(true, URI.create("http://127.0.0.1:9/"), Optional.empty(), UUID.randomUUID(), "1.0.0"),
                    runtime.dataSource(),
                    Clock.systemUTC());
            runtime.invoker().bindHooks(reports);

            InvokeOutcome denied = runtime.invoker().invoke(
                    openPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-deny");
            assertThat(denied.status()).isEqualTo(403);
            InvokeOutcome local = runtime.invoker().invoke(
                    namedPrincipal, "local_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-local");
            assertThat(local.status()).isEqualTo(200);
            InvokeOutcome official = runtime.invoker().invoke(
                    namedPrincipal, "official_ping", "ping", java.util.Map.of(), Optional.empty(), Optional.empty(), "trace-official");
            assertThat(official.status()).isEqualTo(200);

            assertThat(callSource(runtime, "trace-local")).containsExactly("LOCAL", "LOCAL");
            assertThat(callSource(runtime, "trace-official")).containsExactly("OFFICIAL", "REEF");
            reports.flushMemory();
            assertThat(hourlyConches(runtime)).contains("official_ping").doesNotContain("local_ping");
            assertThat(reportConches(runtime)).doesNotContain("local_ping");
        }
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
