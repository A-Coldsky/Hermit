package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.report.ReportSettings;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.FakeReef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrabWiringTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    java.nio.file.Path temp;

    @Test
    void missingPublicKeyFailsBeforeSync() {
        CrabConfig config = config("http://127.0.0.1:9/", null, true);
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("missing")), config, name -> null)) {
            assertThatThrownBy(runtime::loadInstalledShells)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("reef.url is set but reef.public_key is missing");
        }
    }

    @Test
    void syncAuditsFieldNamesAndCallsAreReported() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), true);
            CrabPaths paths = new CrabPaths(temp.resolve("wired"));
            try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
                runtime.loadInstalledShells();
                reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
                SyncReport first = runtime.syncOnce(null).orElseThrow();
                assertThat(first.shells()).anyMatch(outcome -> outcome.kind() == OutcomeKind.INSTALLED);

                var doc = runtime.registry().find("legacy_oa").orElseThrow();
                runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
                runtime.admin().writeCredentials(doc, "alice", Map.of("username", "alice", "password", "pw"), Actors.CLI);
                CreatedApiKey key = runtime.admin().createApiKey(
                        "caller", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
                var outcome = runtime.invoker().invoke(
                        runtime.apiKeys().authenticate(key.plaintext()).orElseThrow(),
                        "legacy_oa",
                        "list_todo",
                        Map.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        "trace-wire");
                assertThat(outcome.status()).as(new String(outcome.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
                assertThat(runtime.reportSender().preview().batch().counts()).isNotEmpty();

                reef.publish(ShellTexts.withoutPassword("legacy_oa", "1.1.0"));
                runtime.syncOnce(Actors.CONSOLE);
                String detail = auditDetail(runtimeDataSource(runtime));
                assertThat(detail).contains("otp").contains("password");
                assertThat(detail).doesNotContain("pw");
            }
        }
    }

    @Test
    void consoleSyncAndDisabledReports() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabPaths paths = new CrabPaths(temp.resolve("console"));
            CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null);
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> preview = send(http, server.port(), "GET", "/console/api/reports/preview", null);
                assertThat(preview.statusCode()).isEqualTo(409);
                assertThat(JSON.readTree(preview.body()).path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");

                HttpResponse<String> sync = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(sync.body()).path("data");
                assertThat(data.path("notModified").isBoolean()).isTrue();
                assertThat(data.path("latestCrab").path("latestVersion").asText()).isEqualTo(reef.latestVersion());
                HttpResponse<String> shells = send(http, server.port(), "GET", "/console/api/shells", null);
                assertThat(shells.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(shells.body()).path("data").path("shells").toString()).contains("legacy_oa");

                HttpResponse<String> status = send(http, server.port(), "GET", "/console/api/sync", null);
                assertThat(status.statusCode()).isEqualTo(200);
                JsonNode last = JSON.readTree(status.body()).path("data");
                assertThat(last.path("report").path("notModified").asBoolean()).isEqualTo(data.path("notModified").asBoolean());
                assertThat(last.path("latestCrab").path("latestVersion").asText()).isEqualTo(reef.latestVersion());
            }
        }
    }

    @Test
    void scheduledSyncIsVisibleOnConsoleAndFailureKeepsThePreviousReport() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("scheduled")), config, name -> null);
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                JsonNode startup = awaitSyncReport(http, server.port());
                assertThat(startup.path("report").path("shells").size()).isZero();

                reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
                runtime.shellSync().runScheduled();

                JsonNode scheduled = syncData(http, server.port());
                JsonNode shells = scheduled.path("report").path("shells");
                assertThat(shells.size()).isEqualTo(1);
                assertThat(shells.get(0).path("shell").asText()).isEqualTo("legacy_oa");
                assertThat(shells.get(0).path("version").asText()).isEqualTo("1.0.0");
                assertThat(shells.get(0).path("kind").asText()).isEqualTo("INSTALLED");

                reef.manifestStatus(500);
                runtime.shellSync().runScheduled();
                assertThat(syncData(http, server.port())).isEqualTo(scheduled);
            }
        }
    }

    @Test
    void consoleStatusShowsTheInstalledVersionAfterAutomaticRollback() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabPaths paths = new CrabPaths(temp.resolve("rollback-status"));
            CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null);
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                awaitSyncReport(http, server.port());

                reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
                runtime.shellSync().runScheduled();
                guard(reef, runtime).onResult("legacy_oa", "1.0.0", execution(true, "1.0.0", null));

                reef.publish(ShellTexts.standard("legacy_oa", "1.0.1"));
                runtime.shellSync().runScheduled();
                JsonNode installed = syncData(http, server.port()).path("report").path("shells");
                assertThat(installed.size()).isEqualTo(1);
                assertThat(installed.get(0).path("version").asText()).isEqualTo("1.0.1");
                assertThat(installed.get(0).path("kind").asText()).isEqualTo("INSTALLED");

                RollbackGuard guard = guard(reef, runtime);
                for (int i = 0; i < RollbackGuard.MISMATCH_LIMIT; i++) {
                    guard.onResult("legacy_oa", "1.0.1", execution(false, "1.0.1", ErrorCode.RULE_MISMATCH));
                }

                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
                assertThat(pinned(runtime)).containsExactly("1.0.1", "1.0.0");

                JsonNode status = syncData(http, server.port());
                JsonNode shells = status.path("report").path("shells");
                assertThat(shells.size()).isEqualTo(1);
                assertThat(shells.get(0).path("shell").asText()).isEqualTo("legacy_oa");
                assertThat(shells.get(0).path("version").asText()).isEqualTo("1.0.0");
                assertThat(shells.get(0).path("kind").asText()).isEqualTo("ROLLED_BACK");
                assertThat(shells.get(0).path("reason").asText()).isEqualTo(RollbackGuard.REASON);
                assertThat(shells.toString()).doesNotContain("INSTALLED");
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
                assertThat(pinned(runtime)).containsExactly("1.0.1", "1.0.0");
            }
        }
    }

    @Test
    void manualSyncWhenReefIsUnreachableReturnsUpstreamErrorAndRecordsTheFailure() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("reef-down")), config, name -> null);
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                awaitSyncReport(http, server.port());
                reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
                runtime.shellSync().runScheduled();
                JsonNode installed = syncData(http, server.port());
                assertThat(installed.path("report").path("shells").get(0).path("kind").asText()).isEqualTo("INSTALLED");

                reef.disconnect();
                HttpResponse<String> sync = send(http, server.port(), "POST", "/console/api/sync", "{}");
                JsonNode body = JSON.readTree(sync.body());
                assertThat(sync.statusCode()).as(sync.body()).isEqualTo(502);
                assertThat(body.path("success").asBoolean()).isFalse();
                assertThat(body.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
                String message = body.path("error").path("message").asText();
                assertThat(message).contains("无法连接 Reef").contains("再次同步");
                assertThat(message).doesNotContain("Exception").doesNotContain("reef request failed");
                assertThat(sync.body()).doesNotContain("INTERNAL");

                JsonNode status = syncData(http, server.port());
                assertThat(status.path("report").path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
                assertThat(status.path("report").path("error").path("message").asText()).isEqualTo(message);
                assertThat(status.path("report").path("shells").size()).isZero();
                assertThat(status.path("report").toString()).doesNotContain("INSTALLED");
                assertThat(status.path("latestCrab").isNull()).isTrue();

                reef.listen();
                HttpResponse<String> again = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
                JsonNode recovered = syncData(http, server.port());
                assertThat(recovered.path("report").path("error").isNull()).isTrue();
                assertThat(recovered.path("report").path("shells").toString()).doesNotContain("UPSTREAM_ERROR");
            }
        }
    }

    private static JsonNode awaitSyncReport(HttpClient http, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = syncData(http, port);
            if (last.path("report").isObject()) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("startup sync did not publish a report: " + last);
    }

    private static JsonNode syncData(HttpClient http, int port) throws Exception {
        HttpResponse<String> status = send(http, port, "GET", "/console/api/sync", null);
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        return JSON.readTree(status.body()).path("data");
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

    private static CrabConfig config(String url, String pem, boolean reports) {
        return new CrabConfig(url, null, pem, "127.0.0.1", 0, 30, reports, 60, List.of(), Map.of(), 60, 10, 3);
    }

    private static DataSource runtimeDataSource(CrabRuntime runtime) {
        return runtime.dataSource();
    }

    private static RollbackGuard guard(FakeReef reef, CrabRuntime runtime) {
        ReportSettings settings = new ReportSettings(
                false, reef.base(), Optional.empty(), UUID.randomUUID(), "1.0.0");
        return new RollbackGuard(
                runtime.paths(),
                reef.keys().getPublic(),
                runtime.registry(),
                runtime.dataSource(),
                new ReportQueue(settings, runtime.dataSource()));
    }

    private static ExecutionResult execution(boolean success, String version, ErrorCode code) {
        return new ExecutionResult(
                success,
                null,
                success ? null : new ExecutionError(code, "mismatch", 0, "assert", "mismatch"),
                1,
                List.of(),
                version,
                "trace-rollback");
    }

    private static List<String> pinned(CrabRuntime runtime) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT bad_version, rolled_back_to FROM pinned_rollbacks WHERE shell = ?")) {
            statement.setString(1, "legacy_oa");
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                List<String> pin = List.of(rows.getString(1), rows.getString(2));
                assertThat(rows.next()).isFalse();
                return pin;
            }
        }
    }

    private static String auditDetail(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT detail FROM config_audit WHERE kind = 'CREDENTIAL_DECLARATION'")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                String detail = rows.getString(1);
                assertThat(ApiJson.tree(detail).path("addedRequired").toString()).contains("otp");
                return detail;
            }
        }
    }
}
