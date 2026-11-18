package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.runtime.ReefBootstrap;
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
            assertThatThrownBy(runtime::loadInstalledConches)
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
                TestSubscriptions.grantKnown(runtime.dataSource());
                runtime.loadInstalledConches();
                reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
                SyncReport first = runtime.syncOnce(null).orElseThrow();
                assertThat(first.conches()).anyMatch(outcome -> outcome.kind() == OutcomeKind.INSTALLED);

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

                reef.publish(ConchTexts.withoutPassword("legacy_oa", "1.1.0"));
                SyncReport available = runtime.syncOnce(Actors.CONSOLE).orElseThrow();
                assertThat(available.conches()).anyMatch(row ->
                        row.kind() == OutcomeKind.UPDATE_AVAILABLE && "1.0.0".equals(row.version()));
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
                assertThatThrownBy(() -> runtime.update("legacy_oa", Actors.CONSOLE))
                        .isInstanceOf(UpdateException.class)
                        .hasMessage("missing credentials for update: otp");
                runtime.admin().writeCredentials(doc, "alice", Map.of("otp", "123456"), Actors.CLI);
                assertThat(runtime.update("legacy_oa", Actors.CONSOLE).kind()).isEqualTo(OutcomeKind.INSTALLED);
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
            TestSubscriptions.grantKnown(runtime.dataSource());
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> preview = send(http, server.port(), "GET", "/console/api/reports/preview", null);
                assertThat(preview.statusCode()).isEqualTo(409);
                assertThat(JSON.readTree(preview.body()).path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
                assertThat(JSON.readTree(preview.body()).path("error").path("message").asText())
                        .isEqualTo("上报未开启，或尚未连接 Reef。");

                HttpResponse<String> sync = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(sync.body()).path("data");
                assertThat(data.path("notModified").isBoolean()).isTrue();
                assertThat(data.path("latestCrab").path("latestVersion").asText()).isEqualTo(reef.latestVersion());
                HttpResponse<String> conches = send(http, server.port(), "GET", "/console/api/conches", null);
                assertThat(conches.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(conches.body()).path("data").path("conches").toString()).contains("legacy_oa");

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
            TestSubscriptions.grantKnown(runtime.dataSource());
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                JsonNode startup = awaitSyncReport(http, server.port());
                assertThat(startup.path("report").path("conches").size()).isZero();

                reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
                runtime.conchSync().runScheduled();

                JsonNode scheduled = syncData(http, server.port());
                JsonNode conches = scheduled.path("report").path("conches");
                assertThat(conches.size()).isEqualTo(1);
                assertThat(conches.get(0).path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(conches.get(0).path("version").asText()).isEqualTo("1.0.0");
                assertThat(conches.get(0).path("kind").asText()).isEqualTo("INSTALLED");

                reef.manifestStatus(500);
                runtime.conchSync().runScheduled();
                assertThat(syncData(http, server.port())).isEqualTo(scheduled);
            }
        }
    }

    @Test
    void consoleStatusKeepsTheInstalledVersionUntilAManualUpdate() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabPaths paths = new CrabPaths(temp.resolve("forward-status"));
            CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null);
            TestSubscriptions.grantKnown(runtime.dataSource());
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                awaitSyncReport(http, server.port());

                reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
                runtime.conchSync().runScheduled();

                reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
                runtime.conchSync().runScheduled();
                JsonNode waiting = syncData(http, server.port()).path("report").path("conches");
                assertThat(waiting.size()).isEqualTo(1);
                assertThat(waiting.get(0).path("version").asText()).isEqualTo("1.0.0");
                assertThat(waiting.get(0).path("kind").asText()).isEqualTo("UPDATE_AVAILABLE");
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");

                HttpResponse<String> update = send(http, server.port(), "POST", "/console/api/conches/legacy_oa/update", "{}");
                assertThat(update.statusCode()).as(update.body()).isEqualTo(200);
                assertThat(JSON.readTree(update.body()).path("data").path("outcome").path("kind").asText()).isEqualTo("INSTALLED");
                assertThat(JSON.readTree(update.body()).path("data").path("outcome").path("version").asText()).isEqualTo("1.0.1");
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.1");

                runtime.conchSync().runScheduled();
                JsonNode installed = syncData(http, server.port()).path("report").path("conches");
                assertThat(installed.get(0).path("version").asText()).isEqualTo("1.0.1");
                assertThat(installed.get(0).path("kind").asText()).isEqualTo("SKIPPED_SAME");
            }
        }
    }

    @Test
    void manualSyncWhenReefIsUnreachableReturnsUpstreamErrorAndRecordsTheFailure() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("reef-down")), config, name -> null);
            TestSubscriptions.grantKnown(runtime.dataSource());
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                awaitSyncReport(http, server.port());
                reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
                runtime.conchSync().runScheduled();
                JsonNode installed = syncData(http, server.port());
                assertThat(installed.path("report").path("conches").get(0).path("kind").asText()).isEqualTo("INSTALLED");

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
                assertThat(status.path("report").path("conches").size()).isZero();
                assertThat(status.path("report").toString()).doesNotContain("INSTALLED");
                assertThat(status.path("latestCrab").isNull()).isTrue();

                reef.listen();
                HttpResponse<String> again = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
                JsonNode recovered = syncData(http, server.port());
                assertThat(recovered.path("report").path("error").isNull()).isTrue();
                assertThat(recovered.path("report").path("conches").toString()).doesNotContain("UPSTREAM_ERROR");
            }
        }
    }

    @Test
    void manifestUnauthorizedIs401AndTheSyncStatusKeepsThatFailure() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabPaths paths = new CrabPaths(temp.resolve("token-rejected"));
            CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null);
            TestSubscriptions.grantKnown(runtime.dataSource());
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                awaitSyncReport(http, server.port());
                reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));

                HttpResponse<String> first = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
                JsonNode installed = JSON.readTree(first.body()).path("data");
                assertThat(installed.path("notModified").asBoolean()).isFalse();
                assertThat(installed.path("error").isNull()).isTrue();
                assertThat(installed.path("conches").get(0).path("kind").asText()).isEqualTo("INSTALLED");

                HttpResponse<String> unchanged = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(200);
                assertThat(JSON.readTree(unchanged.body()).path("data").path("notModified").asBoolean()).isTrue();
                assertThat(JSON.readTree(unchanged.body()).path("data").path("error").isNull()).isTrue();

                ObjectNode bootstrap = JSON.createObjectNode();
                bootstrap.put("kind", "hermit-crab-bootstrap");
                bootstrap.put("version", 1);
                ObjectNode reefNode = bootstrap.putObject("reef");
                reefNode.put("url", reef.base().toString());
                reefNode.put("token", "hat_office_pc_token_0123456789");
                reefNode.put("publicKey", Ed25519Keys.writePublicPem(reef.keys().getPublic()));
                bootstrap.put("owner", "alice");
                bootstrap.put("tokenName", "办公室 PC");
                bootstrap.put("createdAt", "2026-11-04T04:00:00Z");
                runtime.importBootstrap(JSON.writeValueAsBytes(bootstrap), Actors.CONSOLE);
                Path file = ReefBootstrap.file(paths);
                byte[] kept = Files.readAllBytes(file);
                HttpResponse<String> connected = send(http, server.port(), "GET", "/console/api/reef", null);
                assertThat(connected.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(connected.body()).path("data").path("configured").asBoolean()).isTrue();
                assertThat(JSON.readTree(connected.body()).path("data").path("source").asText()).isEqualTo("BOOTSTRAP");

                HttpResponse<String> still = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(still.statusCode()).as(still.body()).isEqualTo(200);
                assertThat(JSON.readTree(still.body()).path("data").path("notModified").asBoolean()).isTrue();

                reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
                HttpResponse<String> moved = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
                assertThat(JSON.readTree(moved.body()).path("data").path("notModified").asBoolean()).isFalse();
                JsonNode previous = syncData(http, server.port());
                assertThat(previous.path("report").path("error").isNull()).isTrue();
                assertThat(previous.path("report").path("conches").toString()).contains("legacy_oa");

                reef.manifestStatus(500);
                HttpResponse<String> broken = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(broken.statusCode()).as(broken.body()).isEqualTo(500);
                assertThat(JSON.readTree(broken.body()).path("error").path("code").asText()).isEqualTo("INTERNAL");
                assertThat(JSON.readTree(broken.body()).path("error").path("message").asText()).isEqualTo("内部错误");
                assertThat(broken.body()).doesNotContain("无法连接").doesNotContain(SyncException.TOKEN_REJECTED);
                assertThat(syncData(http, server.port())).isEqualTo(previous);
                assertThat(Files.readAllBytes(file)).isEqualTo(kept);

                for (int status : List.of(401, 403)) {
                    reef.manifestStatus(status);
                    HttpResponse<String> rejected = send(http, server.port(), "POST", "/console/api/sync", "{}");
                    JsonNode body = JSON.readTree(rejected.body());
                    assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(401);
                    assertThat(body.path("success").asBoolean()).isFalse();
                    assertThat(body.path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
                    assertThat(body.path("error").path("message").asText()).isEqualTo(SyncException.TOKEN_REJECTED);
                    assertThat(rejected.body()).doesNotContain("internal error");

                    JsonNode recorded = syncData(http, server.port());
                    assertThat(recorded.path("report").path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
                    assertThat(recorded.path("report").path("error").path("message").asText())
                            .isEqualTo(SyncException.TOKEN_REJECTED);
                    assertThat(recorded.path("report").path("notModified").asBoolean()).isFalse();
                    assertThat(recorded.path("report").path("conches").size()).isZero();
                    assertThat(recorded.path("report").toString()).doesNotContain("legacy_oa");
                    assertThat(recorded.path("latestCrab").isNull()).isTrue();
                    assertThat(Files.readAllBytes(file)).isEqualTo(kept);

                    HttpResponse<String> reefStatus = send(http, server.port(), "GET", "/console/api/reef", null);
                    JsonNode connection = JSON.readTree(reefStatus.body()).path("data");
                    assertThat(reefStatus.statusCode()).isEqualTo(200);
                    assertThat(connection.path("configured").asBoolean()).isTrue();
                    assertThat(connection.path("source").asText()).isEqualTo("BOOTSTRAP");
                }

                reef.manifestStatus(200);
                HttpResponse<String> recovered = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
                assertThat(JSON.readTree(recovered.body()).path("data").path("notModified").asBoolean()).isTrue();
                assertThat(syncData(http, server.port()).path("report").path("error").isNull()).isTrue();
                assertThat(Files.readAllBytes(file)).isEqualTo(kept);
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
        return new CrabConfig(url, null, pem, "127.0.0.1", 0, 30, reports, 60, List.of(), Map.of(), 60, 10, 3, List.of(), 4, null, true);
    }

    private static DataSource runtimeDataSource(CrabRuntime runtime) {
        return runtime.dataSource();
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
