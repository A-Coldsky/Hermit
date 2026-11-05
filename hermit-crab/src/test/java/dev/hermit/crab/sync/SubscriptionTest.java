package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.FakeReef;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubscriptionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID INSTANCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String PASSWORD = "pw-subscribe-test";

    @TempDir
    Path temp;

    @Test
    void parseManifestDefaultsMissingMetadata() {
        List<ReefClient.CatalogEntry> missing = ReefClient.parseManifest("""
                {"success":true,"data":[{"conch":"legacy_oa","version":"1.0.0","sha256":"aa","minCrabVersion":"1.0.0"}]}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(missing).singleElement().satisfies(entry -> {
            assertThat(entry.title()).isEqualTo("legacy_oa");
            assertThat(entry.description()).isNull();
            assertThat(entry.capabilityCount()).isZero();
        });

        List<ReefClient.CatalogEntry> explicit = ReefClient.parseManifest("""
                {"success":true,"data":[{
                  "conch":"legacy_oa","version":"1.0.0","sha256":"aa","minCrabVersion":"1.0.0",
                  "title":"标题","description":"说明","capabilityCount":4
                }]}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(explicit.get(0).title()).isEqualTo("标题");
        assertThat(explicit.get(0).description()).isEqualTo("说明");
        assertThat(explicit.get(0).capabilityCount()).isEqualTo(4);

        List<ReefClient.CatalogEntry> blank = ReefClient.parseManifest("""
                {"success":true,"data":[{
                  "conch":"legacy_oa","version":"1.0.0","title":"  ","description":"","capabilityCount":-3
                },{
                  "conch":"other_sys","version":"1.0.0","description":null,"capabilityCount":"2"
                }]}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(blank.get(0).title()).isEqualTo("legacy_oa");
        assertThat(blank.get(0).description()).isEmpty();
        assertThat(blank.get(0).capabilityCount()).isZero();
        assertThat(blank.get(1).description()).isNull();
        assertThat(blank.get(1).capabilityCount()).isZero();
    }

    @Test
    void unsubscribedSyncDoesNotDownloadOrInstall() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            ConchSync sync = sync(reef, crab, registry);
            SyncReport first = sync.syncOnce();
            assertThat(outcome(first, "legacy_oa").kind()).isEqualTo(OutcomeKind.NOT_SUBSCRIBED);
            assertThat(reef.downloads()).isZero();
            assertThat(registry.snapshot()).isEmpty();

            SyncReport again = sync.syncOnce();
            assertThat(again.notModified()).isTrue();
            assertThat(reef.downloads()).isZero();
            assertThat(registry.snapshot()).isEmpty();
        }
    }

    @Test
    void subscribeInstallsAndCatalogReusesTheCache() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            try (CrabRuntime runtime = open(reef, "install")) {
                runtime.loadInstalledConches();
                ConchOutcome installed = runtime.subscribe("legacy_oa", Actors.CONSOLE);
                assertThat(installed.kind()).isEqualTo(OutcomeKind.INSTALLED);
                assertThat(reef.downloads()).isEqualTo(1);
                assertThat(runtime.registry().find("legacy_oa")).isPresent();

                ConchCatalog catalog = runtime.catalog();
                assertThat(catalog.configured()).isTrue();
                assertThat(catalog.syncedAt()).isNotNull();
                assertThat(catalog.conches()).singleElement().satisfies(entry -> {
                    assertThat(entry.conch()).isEqualTo("legacy_oa");
                    assertThat(entry.version()).isEqualTo("1.0.0");
                    assertThat(entry.title()).isEqualTo("标题");
                    assertThat(entry.description()).isNull();
                    assertThat(entry.capabilityCount()).isZero();
                    assertThat(entry.subscribed()).isTrue();
                    assertThat(entry.installed()).isTrue();
                    assertThat(entry.installedVersion()).isEqualTo("1.0.0");
                    assertThat(entry.disabled()).isFalse();
                    assertThat(entry.localConflict()).isFalse();
                });

                SyncReport again = runtime.syncOnce(Actors.CONSOLE).orElseThrow();
                assertThat(again.notModified()).isTrue();
                assertThat(runtime.catalog().conches()).singleElement()
                        .extracting(ConchCatalog.Entry::title, ConchCatalog.Entry::subscribed)
                        .containsExactly("标题", true);
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isEqualTo(1);
                assertThat(auditDetails(runtime, "CONCH_SUBSCRIBED")).containsExactly("legacy_oa");
            }
        }
    }

    @Test
    void unsubscribeKeepsTheAccountAndResubscribeCanInvoke() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            try (CrabRuntime runtime = open(reef, "account")) {
                runtime.loadInstalledConches();
                runtime.subscribe("legacy_oa", Actors.CLI);
                var doc = runtime.registry().find("legacy_oa").orElseThrow();
                runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
                runtime.admin().writeCredentials(
                        doc, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CLI);
                CreatedApiKey key = runtime.admin().createApiKey(
                        "caller", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
                assertThat(invoke(runtime, key, "trace-on").status()).isEqualTo(200);

                runtime.unsubscribe("legacy_oa", Actors.CLI);
                var missing = invoke(runtime, key, "trace-off");
                assertThat(missing.status()).isEqualTo(404);
                assertThat(new String(missing.body(), StandardCharsets.UTF_8)).contains("NOT_FOUND")
                        .doesNotContain("CONCH_DISABLED");
                SecretChecks.absent(new String(missing.body(), StandardCharsets.UTF_8), PASSWORD);
                assertThat(runtime.admin().listAccounts("legacy_oa")).extracting(Account::account)
                        .containsExactly("alice");
                assertThat(runtime.admin().credentialNames("legacy_oa", "alice"))
                        .contains("username", "password");
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(Files.exists(runtime.paths().conches().resolve("legacy_oa/current"))).isFalse();
                assertThat(auditDetails(runtime, "CONCH_UNSUBSCRIBED")).containsExactly("legacy_oa");
                assertThat(String.join("\n", auditDetails(runtime, "CONCH_UNSUBSCRIBED"))).doesNotContain(PASSWORD);

                ConchOutcome again = runtime.subscribe("legacy_oa", Actors.CLI);
                assertThat(again.kind()).isIn(OutcomeKind.INSTALLED, OutcomeKind.SKIPPED_SAME);
                assertThat(invoke(runtime, key, "trace-back").status()).isEqualTo(200);
            }
        }
    }

    @Test
    void unsubscribeClearsADisableTombstone() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            try (CrabRuntime runtime = open(reef, "disabled")) {
                runtime.loadInstalledConches();
                runtime.subscribe("legacy_oa", Actors.CONSOLE);
                CreatedApiKey key = runtime.admin().createApiKey(
                        "caller", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CONSOLE);
                reef.remove("legacy_oa");
                runtime.syncOnce(Actors.CONSOLE);
                var kept = invoke(runtime, key, "trace-disabled");
                assertThat(kept.status()).isEqualTo(409);
                assertThat(new String(kept.body(), StandardCharsets.UTF_8)).contains("NOT_CONFIGURED")
                        .doesNotContain("CONCH_DISABLED");
                assertThat(runtime.registry().find("legacy_oa")).isPresent();
                assertThat(Files.exists(runtime.paths().conches().resolve("legacy_oa/current"))).isTrue();
                assertThat(runtime.conchSync().disabledConches().contains("legacy_oa")).isTrue();
                assertThat(auditCount(runtime, "REEF_CONCH_DISABLED")).isEqualTo(1);

                runtime.unsubscribe("legacy_oa", Actors.CONSOLE);
                var missing = invoke(runtime, key, "trace-cleared");
                assertThat(missing.status()).isEqualTo(404);
                assertThat(new String(missing.body(), StandardCharsets.UTF_8)).contains("NOT_FOUND")
                        .doesNotContain("CONCH_DISABLED");
                assertThat(runtime.conchSync().disabledConches().contains("legacy_oa")).isFalse();
                assertThat(runtime.subscribed("legacy_oa")).isFalse();
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(Files.exists(runtime.paths().conches().resolve("legacy_oa/current"))).isFalse();
                assertThat(auditCount(runtime, "REEF_CONCH_ENABLED")).isZero();
                assertThat(auditCount(runtime, "REEF_CONCH_DISABLED")).isEqualTo(1);
                assertThat(auditDetails(runtime, "CONCH_UNSUBSCRIBED")).containsExactly("legacy_oa");
            }
        }
    }

    @Test
    void upgradedDatabaseKeepsAnInstalledConchSubscribed() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("upgrade"));
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = config(reef);
            try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
                runtime.loadInstalledConches();
                assertThat(runtime.subscribe("legacy_oa", Actors.CONSOLE).kind()).isEqualTo(OutcomeKind.INSTALLED);
            }
            try (CrabDatabase database = new CrabDatabase(paths);
                 Connection connection = database.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE subscriptions");
                statement.execute("DELETE FROM schema_version WHERE version = 8");
            }
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
            try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
                runtime.loadInstalledConches();
                assertThat(runtime.subscribed("legacy_oa")).isTrue();
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
                SyncReport report = runtime.syncOnce(Actors.CONSOLE).orElseThrow();
                assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.UPDATE_AVAILABLE);
                assertThat(outcome(report, "legacy_oa").version()).isEqualTo("1.0.0");
                assertThat(runtime.registry().find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");
            }
        }
    }

    @Test
    void localNameRejectsSubscribeAndSyncDoesNotReplaceIt() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("local"))) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(ConchTexts.standard("legacy_oa", "1.0.0")), Origin.LOCAL);
            ConchSync sync = sync(reef, crab, registry);
            assertThatThrownBy(() -> sync.subscribe("legacy_oa", Actors.CONSOLE, null))
                    .isInstanceOf(SubscriptionException.class)
                    .satisfies(error -> {
                        SubscriptionException ex = (SubscriptionException) error;
                        assertThat(ex.status()).isEqualTo(409);
                        assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                        assertThat(ex.getMessage()).isEqualTo(SubscriptionException.LOCAL_CONFLICT);
                    });
            assertThat(new Subscriptions(crab.dataSource).contains("legacy_oa")).isFalse();
            assertThat(reef.downloads()).isZero();
            assertThat(registry.origin("legacy_oa")).isEqualTo(Origin.LOCAL);

            new Subscriptions(crab.dataSource).record("legacy_oa", "test", Instant.EPOCH);
            int downloads = reef.downloads();
            SyncReport report = sync.syncOnce();
            assertThat(outcome(report, "legacy_oa").kind()).isEqualTo(OutcomeKind.SKIPPED_LOCAL);
            assertThat(outcome(report, "legacy_oa").reason()).isEqualTo(SyncReasons.LOCAL_KEPT);
            assertThat(reef.downloads()).isEqualTo(downloads);
            assertThat(registry.origin("legacy_oa")).isEqualTo(Origin.LOCAL);
            assertThat(Files.exists(crab.paths.conches().resolve("legacy_oa/current"))).isFalse();
        }
    }

    @Test
    void unsubscribedRegistryEntryIsNotWithdrawn() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp.resolve("keep"))) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(ConchTexts.standard("legacy_oa", "1.0.0")));
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            SyncReport report = sync(reef, crab, registry).syncOnce();
            assertThat(outcome(report, "other_sys").kind()).isEqualTo(OutcomeKind.NOT_SUBSCRIBED);
            assertThat(report.conches()).noneMatch(item -> "legacy_oa".equals(item.conch()));
            assertThat(report.removed()).doesNotContain("legacy_oa");
            assertThat(registry.find("legacy_oa")).isPresent();
            assertThat(registry.origin("legacy_oa")).isEqualTo(Origin.OFFICIAL);
        }
    }

    @Test
    void repeatSubscribeAuditsOnceAndUnknownNamesAreRejected() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            try (CrabRuntime runtime = open(reef, "repeat")) {
                runtime.loadInstalledConches();
                assertThatThrownBy(() -> runtime.subscribe("Nope", Actors.CONSOLE))
                        .isInstanceOf(SubscriptionException.class)
                        .satisfies(error -> {
                            SubscriptionException ex = (SubscriptionException) error;
                            assertThat(ex.status()).isEqualTo(400);
                            assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                            assertThat(ex.getMessage()).isEqualTo(SubscriptionException.NAME_INVALID);
                        });
                assertThatThrownBy(() -> runtime.subscribe("legacy_oa", Actors.CONSOLE))
                        .isInstanceOf(SubscriptionException.class)
                        .satisfies(error -> {
                            SubscriptionException ex = (SubscriptionException) error;
                            assertThat(ex.status()).isEqualTo(404);
                            assertThat(ex.getMessage()).isEqualTo(SubscriptionException.CANNOT_SUBSCRIBE);
                        });
                assertThat(runtime.subscribed("legacy_oa")).isFalse();

                ConchOutcome first = runtime.subscribe("other_sys", Actors.CONSOLE);
                ConchOutcome second = runtime.subscribe("other_sys", Actors.CONSOLE);
                assertThat(first.kind()).isEqualTo(OutcomeKind.INSTALLED);
                assertThat(second.kind()).isIn(OutcomeKind.INSTALLED, OutcomeKind.SKIPPED_SAME);
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isEqualTo(1);
                assertThat(auditCount(runtime, "SYNC_REQUESTED")).isEqualTo(2);
            }
        }
    }

    @Test
    void catalogRefreshFailureDoesNotInsertASubscription() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            try (CrabRuntime runtime = open(reef, "fail")) {
                runtime.loadInstalledConches();
                runtime.syncOnce(Actors.CONSOLE);
                assertThat(reef.downloads()).isZero();
                reef.disconnect();
                assertThatThrownBy(() -> runtime.subscribe("legacy_oa", Actors.CONSOLE))
                        .isInstanceOf(SyncException.class);
                assertThat(runtime.subscribed("legacy_oa")).isFalse();
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(reef.downloads()).isZero();
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isZero();
            }
        }
    }

    @Test
    void rejectedDownloadAfterSubscribeLeavesTheRow() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            try (CrabRuntime runtime = open(reef, "reject-download")) {
                runtime.loadInstalledConches();
                runtime.syncOnce(Actors.CONSOLE);
                reef.downloadStatus("legacy_oa", 500);
                ConchOutcome outcome = runtime.subscribe("legacy_oa", Actors.CONSOLE);
                assertThat(outcome.kind()).isEqualTo(OutcomeKind.REJECTED);
                assertThat(runtime.subscribed("legacy_oa")).isTrue();
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(Files.exists(runtime.paths().conches().resolve("legacy_oa/current"))).isFalse();
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isEqualTo(1);
            }
        }
    }

    @Test
    void staleCatalogCannotSubscribeARemovedConch() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("stale-sub")), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> synced = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
                assertThat(reef.downloads()).isZero();

                HttpResponse<String> cached = send(http, server.port(), "GET", "/console/api/catalog", null);
                assertThat(cached.body()).contains("legacy_oa").contains("other_sys");
                reef.remove("legacy_oa");
                int downloads = reef.downloads();

                HttpResponse<String> rejected = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(404);
                JsonNode rejectedBody = JSON.readTree(rejected.body());
                assertThat(rejectedBody.path("success").asBoolean()).isFalse();
                assertThat(rejectedBody.path("data").isNull()).isTrue();
                assertThat(rejectedBody.path("error").path("code").asText()).isEqualTo("NOT_FOUND");
                assertThat(rejectedBody.path("error").path("message").asText())
                        .isEqualTo(SubscriptionException.CANNOT_SUBSCRIBE);
                assertThat(runtime.subscribed("legacy_oa")).isFalse();
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(Files.exists(runtime.paths().conches().resolve("legacy_oa/current"))).isFalse();
                assertThat(reef.downloads()).isEqualTo(downloads);
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isZero();

                HttpResponse<String> catalog = send(http, server.port(), "GET", "/console/api/catalog", null);
                assertThat(catalog.body()).doesNotContain("legacy_oa").contains("other_sys");

                HttpResponse<String> installed = send(
                        http, server.port(), "POST", "/console/api/conches/other_sys/subscription", "{}");
                assertThat(installed.statusCode()).as(installed.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(installed.body()).path("data");
                assertThat(data.path("conch").asText()).isEqualTo("other_sys");
                assertThat(data.path("subscribed").asBoolean()).isTrue();
                assertThat(data.path("outcome").path("kind").asText()).isEqualTo("INSTALLED");
                assertThat(runtime.subscribed("other_sys")).isTrue();
                assertThat(runtime.registry().find("other_sys")).isPresent();
                assertThat(runtime.subscribed("legacy_oa")).isFalse();

                HttpResponse<String> unknown = send(
                        http, server.port(), "POST", "/console/api/conches/never_pub/subscription", "{}");
                assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
                assertThat(JSON.readTree(unknown.body()).path("error").path("code").asText()).isEqualTo("NOT_FOUND");
                assertThat(JSON.readTree(unknown.body()).path("error").path("message").asText())
                        .isEqualTo(SubscriptionException.CANNOT_SUBSCRIBE);
                assertThat(runtime.subscribed("never_pub")).isFalse();
                assertThat(runtime.registry().find("other_sys")).isPresent();
            }
        }
    }

    @Test
    void repeatSubscribeOfARemovedConchKeepsTheRowAndSyncs() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("repeat-removed")), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> first = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
                assertThat(JSON.readTree(first.body()).path("data").path("subscribed").asBoolean()).isTrue();
                assertThat(JSON.readTree(first.body()).path("data").path("outcome").path("kind").asText())
                        .isEqualTo("INSTALLED");

                reef.remove("legacy_oa");
                HttpResponse<String> again = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(again.body()).path("data");
                assertThat(data.path("subscribed").asBoolean()).isTrue();
                assertThat(data.path("outcome").path("kind").asText()).isEqualTo("DISABLED");
                assertThat(data.path("outcome").path("version").asText()).isEqualTo("1.0.0");
                assertThat(runtime.subscribed("legacy_oa")).isTrue();
                assertThat(runtime.registry().find("legacy_oa")).isPresent();
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isEqualTo(1);
            }
        }
    }

    @Test
    void subscribeWithoutAnOutcomeDoesNotClaimSubscribed() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("no-outcome")), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> synced = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
                new Subscriptions(runtime.dataSource()).record("legacy_oa", "test", Instant.EPOCH);
                reef.remove("legacy_oa");

                HttpResponse<String> response = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(response.body()).path("data");
                assertThat(data.path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(data.path("subscribed").asBoolean()).isFalse();
                assertThat(data.path("outcome").isNull()).isTrue();
                assertThat(runtime.subscribed("legacy_oa")).isTrue();
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(reef.downloads()).isZero();
                assertThat(auditCount(runtime, "CONCH_SUBSCRIBED")).isZero();
            }
        }
    }

    @Test
    void catalogAndSubscribeRequireReefButUnsubscribeWorksOffline() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("offline"));
        CrabConfig config = new CrabConfig(
                null, null, null, "127.0.0.1", 0, 30, false, 60, List.of(), Map.of(), 60, 10, 3, List.of());
        try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
            ConchCatalog catalog = runtime.catalog();
            assertThat(catalog.configured()).isFalse();
            assertThat(catalog.conches()).isEmpty();
            assertThatThrownBy(() -> runtime.subscribe("legacy_oa", Actors.CLI))
                    .isInstanceOf(SubscriptionException.class)
                    .satisfies(error -> {
                        SubscriptionException ex = (SubscriptionException) error;
                        assertThat(ex.status()).isEqualTo(409);
                        assertThat(ex.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
                        assertThat(ex.getMessage()).isEqualTo(SubscriptionException.NOT_CONFIGURED);
                    });
            new Subscriptions(runtime.dataSource()).record("legacy_oa", "test", Instant.EPOCH);
            runtime.registry().put(ConchParser.parse(ConchTexts.standard("legacy_oa", "1.0.0")));
            Files.createDirectories(paths.conches().resolve("legacy_oa"));
            Files.writeString(paths.conches().resolve("legacy_oa/current"), "1.0.0\n");
            runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
            runtime.unsubscribe("legacy_oa", Actors.CLI);
            assertThat(runtime.subscribed("legacy_oa")).isFalse();
            assertThat(runtime.registry().find("legacy_oa")).isEmpty();
            assertThat(Files.exists(paths.conches().resolve("legacy_oa/current"))).isFalse();
            assertThat(runtime.admin().listAccounts("legacy_oa")).extracting(Account::account)
                    .containsExactly("alice");
        }
    }

    @Test
    void consoleCatalogSubscribeAndUnsubscribe() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            Path home = temp.resolve("console");
            Files.createDirectories(home.resolve("local-conches"));
            Files.writeString(home.resolve("local-conches/other_sys.yaml"), ConchTexts.standard("other_sys", "1.0.0"));
            CrabRuntime runtime = CrabRuntime.open(new CrabPaths(home), config, name -> null);
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> clash = send(
                        http, server.port(), "POST", "/console/api/conches/other_sys/subscription", "{}");
                assertThat(clash.statusCode()).isEqualTo(409);
                assertThat(JSON.readTree(clash.body()).path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
                assertThat(JSON.readTree(clash.body()).path("error").path("message").asText())
                        .isEqualTo(SubscriptionException.LOCAL_CONFLICT);
                assertThat(reef.downloads()).isZero();

                HttpResponse<String> subscribed = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(subscribed.statusCode()).as(subscribed.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(subscribed.body()).path("data");
                assertThat(data.path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(data.path("subscribed").asBoolean()).isTrue();
                assertThat(data.path("outcome").path("kind").asText()).isEqualTo("INSTALLED");

                HttpResponse<String> catalog = send(http, server.port(), "GET", "/console/api/catalog", null);
                JsonNode entry = JSON.readTree(catalog.body()).path("data").path("conches").get(0);
                assertThat(entry.path("subscribed").asBoolean()).isTrue();
                assertThat(entry.path("installed").asBoolean()).isTrue();
                assertThat(entry.path("title").asText()).isEqualTo("标题");
                assertThat(entry.path("description").isNull()).isTrue();
                assertThat(entry.path("capabilityCount").asInt()).isZero();

                HttpResponse<String> conches = send(http, server.port(), "GET", "/console/api/conches", null);
                assertThat(JSON.readTree(conches.body()).path("data").path("conches").toString())
                        .contains("\"subscribed\":true");

                HttpResponse<String> removed = send(
                        http, server.port(), "DELETE", "/console/api/conches/legacy_oa/subscription", null);
                assertThat(removed.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(removed.body()).path("data").path("subscribed").asBoolean()).isFalse();
            }
        }
    }

    @Test
    void consoleMarksAReefDisabledConchWithoutUnloadingIt() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("disabled-console")), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> subscribed = send(
                        http, server.port(), "POST", "/console/api/conches/legacy_oa/subscription", "{}");
                assertThat(subscribed.statusCode()).as(subscribed.body()).isEqualTo(200);
                int downloads = reef.downloads();

                HttpResponse<String> before = send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null);
                JsonNode open = JSON.readTree(before.body()).path("data");
                assertThat(open.path("reefDisabled").asBoolean()).isFalse();
                assertThat(open.path("reefDisabledAt").isNull()).isTrue();

                reef.remove("legacy_oa");
                HttpResponse<String> synced = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
                JsonNode report = JSON.readTree(synced.body()).path("data");
                assertThat(report.path("removed").toString()).doesNotContain("legacy_oa");
                boolean shownDisabled = false;
                for (JsonNode row : report.path("conches")) {
                    if ("legacy_oa".equals(row.path("conch").asText()) && "DISABLED".equals(row.path("kind").asText())) {
                        shownDisabled = true;
                        assertThat(row.path("version").asText()).isEqualTo("1.0.0");
                    }
                }
                assertThat(shownDisabled).isTrue();

                HttpResponse<String> list = send(http, server.port(), "GET", "/console/api/conches", null);
                JsonNode summary = JSON.readTree(list.body()).path("data").path("conches").get(0);
                assertThat(summary.path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(summary.path("version").asText()).isEqualTo("1.0.0");
                assertThat(summary.path("reefDisabled").asBoolean()).isTrue();
                assertThat(summary.path("reefDisabledAt").asText()).contains("T");
                String at = summary.path("reefDisabledAt").asText();

                HttpResponse<String> detail = send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null);
                assertThat(JSON.readTree(detail.body()).path("data").path("reefDisabledAt").asText()).isEqualTo(at);

                HttpResponse<String> catalog = send(http, server.port(), "GET", "/console/api/catalog", null);
                JsonNode entry = JSON.readTree(catalog.body()).path("data").path("conches").get(0);
                assertThat(entry.path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(entry.path("disabled").asBoolean()).isTrue();
                assertThat(entry.path("version").isNull()).isTrue();
                assertThat(entry.path("installedVersion").asText()).isEqualTo("1.0.0");
                assertThat(entry.path("subscribed").asBoolean()).isTrue();

                HttpResponse<String> second = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
                assertThat(JSON.readTree(second.body()).path("data").path("notModified").asBoolean()).isTrue();
                HttpResponse<String> later = send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null);
                assertThat(JSON.readTree(later.body()).path("data").path("reefDisabledAt").asText()).isEqualTo(at);
                assertThat(reef.downloads()).isEqualTo(downloads);

                HttpResponse<String> audit = send(http, server.port(), "GET", "/console/api/audit?conch=legacy_oa", null);
                int disabledAudits = 0;
                int enabledAudits = 0;
                for (JsonNode item : JSON.readTree(audit.body()).path("data").path("entries")) {
                    if ("REEF_CONCH_DISABLED".equals(item.path("action").asText())) {
                        disabledAudits++;
                        assertThat(item.path("actor").asText()).isEqualTo("sync");
                        assertThat(item.path("origin").asText()).isEqualTo("OFFICIAL");
                        assertThat(item.path("conch").asText()).isEqualTo("legacy_oa");
                        assertThat(item.path("account").isNull()).isTrue();
                        assertThat(item.path("fields")).isEmpty();
                    }
                    if ("REEF_CONCH_ENABLED".equals(item.path("action").asText())) {
                        enabledAudits++;
                    }
                }
                assertThat(disabledAudits).isEqualTo(1);
                assertThat(enabledAudits).isZero();
            }
        }
    }

    @Test
    void cliCatalogSubscribeAndUnsubscribe() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            Path home = temp.resolve("cli");
            Files.createDirectories(home);
            Path pub = home.resolve("reef.pub");
            Files.writeString(pub, Ed25519Keys.writePublicPem(reef.keys().getPublic()));
            Files.writeString(home.resolve("config.yml"), """
                    reef:
                      url: "%s"
                      public_key: "%s"
                    """.formatted(reef.base(), pub.toAbsolutePath()));
            var env = env(home);

            ByteArrayOutputStream catalog = new ByteArrayOutputStream();
            assertThat(run(env, catalog, "catalog")).isZero();
            assertThat(catalog.toString(StandardCharsets.UTF_8)).isBlank();

            ByteArrayOutputStream synced = new ByteArrayOutputStream();
            assertThat(run(env, synced, "sync")).isZero();
            assertThat(synced.toString(StandardCharsets.UTF_8)).contains("legacy_oa").contains("NOT_SUBSCRIBED");
            assertThat(reef.downloads()).isZero();

            ByteArrayOutputStream listed = new ByteArrayOutputStream();
            assertThat(run(env, listed, "catalog")).isZero();
            assertThat(listed.toString(StandardCharsets.UTF_8).trim()).isEqualTo("legacy_oa 1.0.0 标题");

            ByteArrayOutputStream subscribed = new ByteArrayOutputStream();
            assertThat(run(env, subscribed, "subscribe", "legacy_oa")).isZero();
            assertThat(subscribed.toString(StandardCharsets.UTF_8).trim()).isEqualTo("legacy_oa 1.0.0 INSTALLED");

            ByteArrayOutputStream removed = new ByteArrayOutputStream();
            assertThat(run(env, removed, "unsubscribe", "legacy_oa")).isZero();
            assertThat(removed.toString(StandardCharsets.UTF_8).trim()).isEqualTo("legacy_oa");

            try (CrabDatabase database = new CrabDatabase(new CrabPaths(home))) {
                assertThat(auditDetails(database.dataSource(), "CONCH_SUBSCRIBED")).containsExactly("legacy_oa");
            }
        }

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[] {"catalog"},
                env(temp.resolve("nosync")),
                InputStream.nullInputStream(),
                stdout(new ByteArrayOutputStream()),
                stderr(err));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("reef.url is not configured");
    }

    private static ConchSync sync(FakeReef reef, OpenCrab crab, ConchRegistry registry) {
        return new ConchSync(
                new SyncSettings(reef.base(), Optional.empty(), reef.keys().getPublic(), Duration.ofMinutes(5), INSTANCE),
                crab.paths,
                registry,
                crab.vault,
                crab.dataSource,
                Clock.systemUTC());
    }

    private CrabRuntime open(FakeReef reef, String name) {
        return CrabRuntime.open(new CrabPaths(temp.resolve(name)), config(reef), ignored -> null);
    }

    private static CrabConfig config(FakeReef reef) {
        return new CrabConfig(
                reef.base().toString(),
                null,
                Ed25519Keys.writePublicPem(reef.keys().getPublic()),
                "127.0.0.1",
                0,
                30,
                false,
                60,
                List.of(),
                Map.of(),
                60,
                10,
                3,
                List.of());
    }

    private static dev.hermit.crab.runtime.InvokeOutcome invoke(CrabRuntime runtime, CreatedApiKey key, String trace) {
        return runtime.invoker().invoke(
                runtime.apiKeys().authenticate(key.plaintext()).orElseThrow(),
                "legacy_oa",
                "list_todo",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                trace);
    }

    private static ConchOutcome outcome(SyncReport report, String conch) {
        return report.conches().stream().filter(item -> conch.equals(item.conch())).findFirst().orElseThrow();
    }

    private static int auditCount(CrabRuntime runtime, String kind) throws Exception {
        return auditDetails(runtime.dataSource(), kind).size();
    }

    private static List<String> auditDetails(CrabRuntime runtime, String kind) throws Exception {
        return auditDetails(runtime.dataSource(), kind);
    }

    private static List<String> auditDetails(javax.sql.DataSource dataSource, String kind) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT detail FROM config_audit WHERE kind = '" + kind + "' ORDER BY id")) {
            List<String> details = new ArrayList<>();
            while (resultSet.next()) {
                details.add(resultSet.getString(1));
            }
            return details;
        }
    }

    private static java.util.function.Function<String, String> env(Path root) {
        return name -> "HERMIT_HOME".equals(name) ? root.toString() : null;
    }

    private static int run(java.util.function.Function<String, String> env, ByteArrayOutputStream out, String... args) {
        return new CrabCommand().run(args, env, InputStream.nullInputStream(), stdout(out), stderr(new ByteArrayOutputStream()));
    }

    private static PrintStream stdout(ByteArrayOutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private static PrintStream stderr(ByteArrayOutputStream err) {
        return new PrintStream(err, true, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(HttpClient http, int port, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
