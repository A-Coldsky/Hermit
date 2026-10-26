package dev.hermit.crab.gateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Db;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleAuditTest {
    private static final String PASSWORD = "HermitAuditSecret/91c0+e77b2";
    private static final String MARKER = "HermitAuditCiphertext-marker-c91e";
    private static final String CONFIG_VALUE = "http://oa.example/only-this-config-value";

    @TempDir
    Path temp;

    @Test
    void listsAuditWithoutSecretsAndPagesNewestFirst() throws Exception {
        Path root = temp.resolve("console");
        poison(root);
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        String listed;
        String baseUrl;
        try (TestServer server = TestServer.open(root, PASSWORD, config)) {
            TestServer.Reply denied = server.console("GET", "/console/api/audit", null, false);
            assertThat(denied.status()).isEqualTo(403);

            server.configureAccount("alice");
            TestServer.Reply configured = server.console(
                    "PUT",
                    "/console/api/conches/legacy_oa/config",
                    "{\"base_url\":\"" + CONFIG_VALUE + "\"}",
                    true);
            assertThat(configured.status()).isEqualTo(200);
            server.runtime.admin().createAccount(server.conch.conch(), "bob", Actors.CONSOLE);
            server.runtime.admin().createAccount(server.conch.conch(), "carol", Actors.CONSOLE);
            baseUrl = server.fixture.baseUrl();

            TestServer.Reply all = server.console("GET", "/console/api/audit?limit=200", null, true);
            assertThat(all.status()).isEqualTo(200);
            listed = all.body();
            SecretChecks.absent(listed, PASSWORD);
            SecretChecks.absent(listed, URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));
            SecretChecks.absent(listed, MARKER);
            SecretChecks.absent(listed, server.apiKey.plaintext());
            SecretChecks.absent(listed, baseUrl);
            SecretChecks.absent(listed, CONFIG_VALUE);
            assertThat(listed).doesNotContain("\"detail\"").doesNotContain("\"ciphertext\"").doesNotContain("\"value\"");
            assertThat(listed).contains("CREDENTIALS_WRITTEN").contains("password").contains("base_url");

            JsonNode data = all.json().path("data");
            assertThat(data.path("page").asInt()).isEqualTo(1);
            assertThat(data.path("limit").asInt()).isEqualTo(200);
            JsonNode first = data.path("entries").get(0);
            List<String> names = new ArrayList<>();
            first.fieldNames().forEachRemaining(names::add);
            assertThat(names).containsExactlyInAnyOrder(
                    "id", "at", "actor", "action", "origin", "conch", "account", "fields");
            assertThat(first.path("action").asText()).isEqualTo("ACCOUNT_CREATED");
            assertThat(first.path("account").asText()).isEqualTo("carol");
            assertThat(first.path("at").asText()).contains("T");

            boolean poisonedConfig = false;
            boolean realCredentials = false;
            for (JsonNode entry : data.path("entries")) {
                if ("CONFIG_PUT".equals(entry.path("action").asText()) && entry.path("fields").isEmpty()) {
                    poisonedConfig = true;
                    assertThat(entry.path("account").isNull()).isTrue();
                    assertThat(entry.path("conch").asText()).isEqualTo("legacy_oa");
                }
                if ("CREDENTIALS_WRITTEN".equals(entry.path("action").asText())
                        && "alice".equals(entry.path("account").asText())
                        && texts(entry.path("fields")).contains("username")) {
                    realCredentials = true;
                    assertThat(texts(entry.path("fields"))).containsExactly("password", "username");
                    assertThat(entry.path("origin").asText()).isEqualTo("OFFICIAL");
                    assertThat(entry.path("actor").asText()).isEqualTo("cli");
                }
            }
            assertThat(poisonedConfig).isTrue();
            assertThat(realCredentials).isTrue();

            TestServer.Reply alice = server.console(
                    "GET", "/console/api/audit?conch=legacy_oa&account=alice&limit=50", null, true);
            assertThat(alice.status()).isEqualTo(200);
            SecretChecks.absent(alice.body(), PASSWORD);
            SecretChecks.absent(alice.body(), CONFIG_VALUE);
            for (JsonNode entry : alice.json().path("data").path("entries")) {
                assertThat(entry.path("account").asText()).isEqualTo("alice");
                assertThat(entry.path("conch").asText()).isEqualTo("legacy_oa");
                assertThat(entry.path("action").asText()).isNotEqualTo("CONFIG_PUT");
                assertThat(entry.path("action").asText()).isNotEqualTo("API_KEY_CREATED");
            }
            assertThat(alice.json().path("data").path("total").asLong()).isGreaterThanOrEqualTo(2);

            TestServer.Reply page1 = server.console(
                    "GET", "/console/api/audit?conch=legacy_oa&limit=2&page=1", null, true);
            TestServer.Reply page2 = server.console(
                    "GET", "/console/api/audit?conch=legacy_oa&limit=2&page=2", null, true);
            assertThat(page1.status()).isEqualTo(200);
            assertThat(page2.status()).isEqualTo(200);
            JsonNode newest = page1.json().path("data").path("entries");
            JsonNode older = page2.json().path("data").path("entries");
            assertThat(newest).hasSize(2);
            assertThat(older).hasSize(2);
            assertThat(page1.json().path("data").path("total").asLong())
                    .isEqualTo(page2.json().path("data").path("total").asLong())
                    .isGreaterThanOrEqualTo(4);
            assertThat(newest.get(0).path("id").asLong()).isGreaterThan(newest.get(1).path("id").asLong());
            assertThat(newest.get(1).path("id").asLong()).isGreaterThan(older.get(0).path("id").asLong());
            assertThat(newest.get(0).path("account").asText()).isEqualTo("carol");
            assertThat(ids(newest)).doesNotContainAnyElementsOf(ids(older));

            TestServer.Reply empty = server.console(
                    "GET", "/console/api/audit?conch=other_conch&limit=10&page=1", null, true);
            assertThat(empty.json().path("data").path("entries")).isEmpty();
            assertThat(empty.json().path("data").path("total").asLong()).isZero();

            assertThat(server.console("GET", "/console/api/audit?limit=0", null, true).status()).isEqualTo(400);
            assertThat(server.console("GET", "/console/api/audit?limit=201", null, true).status()).isEqualTo(400);
            assertThat(server.console("GET", "/console/api/audit?page=0", null, true).status()).isEqualTo(400);
            TestServer.Reply badAccount = server.console("GET", "/console/api/audit?account=bad%20name", null, true);
            assertThat(badAccount.status()).isEqualTo(400);
            assertThat(badAccount.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            SecretChecks.absent(badAccount.body(), PASSWORD);
        }
        for (byte[] blob : ciphertexts(root)) {
            SecretChecks.absent(listed, Base64.getEncoder().encodeToString(blob));
            SecretChecks.absent(listed, HexFormat.of().formatHex(blob));
        }
    }

    private static void poison(Path root) {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root))) {
            Db.transaction(database.dataSource(), connection -> {
                insert(connection, Instant.parse("2020-01-01T00:00:00Z"), "CREDENTIALS_WRITTEN",
                        "OFFICIAL/legacy_oa/alice",
                        "{\"fields\":[\"password\"],\"value\":\"" + PASSWORD + "\",\"ciphertext\":\"" + MARKER + "\"}");
                insert(connection, Instant.parse("2020-01-01T00:00:01Z"), "CONFIG_PUT", "OFFICIAL/legacy_oa", PASSWORD);
                return null;
            });
        }
    }

    private static void insert(Connection connection, Instant at, String kind, String target, String detail)
            throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO config_audit (at, actor, kind, target, detail)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            Db.setInstant(statement, 1, at);
            statement.setString(2, "console");
            statement.setString(3, kind);
            statement.setString(4, target);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    private static List<byte[]> ciphertexts(Path root) throws Exception {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(root));
             Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT ciphertext FROM credentials");
             ResultSet resultSet = statement.executeQuery()) {
            List<byte[]> blobs = new ArrayList<>();
            while (resultSet.next()) {
                byte[] bytes = resultSet.getBytes(1);
                assertThat(bytes).isNotEmpty();
                blobs.add(bytes);
            }
            assertThat(blobs).isNotEmpty();
            return blobs;
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static List<Long> ids(JsonNode entries) {
        List<Long> values = new ArrayList<>();
        entries.forEach(entry -> values.add(entry.path("id").asLong()));
        return values;
    }
}
