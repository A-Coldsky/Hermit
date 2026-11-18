package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.report.RollbackEvent;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.FakeReef;

class RuleMismatchStaysTest {

    @TempDir
    java.nio.file.Path temp;

    @Test
    void fiveMismatchesLeaveTheInstalledVersionAndDoNotPinIt() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = new CrabConfig(
                    reef.base().toString(),
                    null,
                    Ed25519Keys.writePublicPem(reef.keys().getPublic()),
                    "127.0.0.1",
                    0,
                    30,
                    true,
                    60,
                    List.of(),
                    Map.of(),
                    60,
                    10,
                    3,
                    List.of(),
                    4,
                    null,
                    true);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp), config, name -> null)) {
                TestSubscriptions.grantKnown(runtime.dataSource());
                runtime.loadInstalledConches();
                reef.publish(mismatch("legacy_oa", "1.0.0"));
                SyncReport installed = runtime.syncOnce(null).orElseThrow();
                assertThat(outcome(installed).kind()).isEqualTo(OutcomeKind.INSTALLED);
                VersionSuccess.record(
                        runtime.dataSource(), "legacy_oa", "1.0.0", Instant.parse("2026-06-01T00:00:00Z"));

                reef.publish(mismatch("legacy_oa", "1.0.1"));
                SyncReport waiting = runtime.syncOnce(null).orElseThrow();
                assertThat(outcome(waiting).kind()).isEqualTo(OutcomeKind.UPDATE_AVAILABLE);
                assertThat(outcome(waiting).version()).isEqualTo("1.0.0");

                ConchOutcome updated = runtime.update("legacy_oa", Actors.CONSOLE);
                assertThat(updated.kind()).isEqualTo(OutcomeKind.INSTALLED);
                assertThat(updated.version()).isEqualTo("1.0.1");
                runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
                CreatedApiKey key = runtime.admin().createApiKey(
                        "caller", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
                for (int i = 0; i < 5; i++) {
                    InvokeOutcome outcome = runtime.invoker().invoke(
                            runtime.apiKeys().authenticate(key.plaintext()).orElseThrow(),
                            "legacy_oa",
                            "ping",
                            Map.of(),
                            Optional.empty(),
                            Optional.empty(),
                            "trace-mismatch-" + i);
                    assertThat(outcome.status()).isEqualTo(422);
                    assertThat(new String(outcome.body(), StandardCharsets.UTF_8)).contains("RULE_MISMATCH");
                }

                assertThat(Files.readString(runtime.paths().conches().resolve("legacy_oa/current")).trim())
                        .isEqualTo("1.0.1");
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");
                assertThat(tableNames(runtime)).doesNotContain("PINNED_ROLLBACKS");
                assertThat(runtime.reportSender().preview().batch().failures()).hasSize(5);
                assertThat(runtime.reportSender().preview().batch().events())
                        .extracting(RollbackEvent::reason)
                        .containsExactly(ReportQueue.MANUAL_UPDATE);
            }
        }
    }

    private static ConchOutcome outcome(SyncReport report) {
        return report.conches().stream().filter(item -> "legacy_oa".equals(item.conch())).findFirst().orElseThrow();
    }

    private static Set<String> tableNames(CrabRuntime runtime) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'
                     """)) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names;
        }
    }

    private static String mismatch(String conch, String version) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 失配
                description: 脚本会被沙箱拒绝。
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                      - script:
                          code: |
                            return { ok: eval("1") };
                          into: marker
                    output:
                      marker: { type: string, description: 标记 }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 失配
                """.formatted(conch, version);
    }
}
