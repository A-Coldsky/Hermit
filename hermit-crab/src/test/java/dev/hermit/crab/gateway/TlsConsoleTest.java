package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.sync.LocalConchBehaviorTest;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;
import dev.hermit.crab.tls.HostPins;
import dev.hermit.crab.tls.OutboundTls;
import dev.hermit.crab.tls.TransportFailure;
import dev.hermit.crab.tls.TrustCert;

class TlsConsoleTest {
    @TempDir
    Path temp;

    @Test
    void consoleImportsACaProbesAndConfirmsAFingerprint() throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("certs"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("home"), "HermitTlsConsole-91ab", config);
             LocalHttps https = LocalHttps.start(new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey())) {
            TestServer.Reply denied = server.console("GET", "/console/api/tls/trust", null, false);
            assertThat(denied.status()).isEqualTo(403);

            TestServer.Reply trust = server.console("GET", "/console/api/tls/trust", null, true);
            assertThat(trust.status()).isEqualTo(200);
            JsonNode sources = trust.json().get("data").get("sources");
            assertThat(names(sources, "kind")).containsExactly("JDK", "OS", "CA_DIR");

            TestServer.Reply badName = server.console(
                    "POST", "/console/api/tls/ca", body("name", "root", "pem", TrustCert.pem(certs.root())), true);
            assertThat(badName.status()).isEqualTo(400);
            assertThat(badName.json().get("error").get("message").asText()).contains(".pem");

            TestServer.Reply privateKey = server.console(
                    "POST", "/console/api/tls/ca",
                    body("name", "secret.pem", "pem", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"),
                    true);
            assertThat(privateKey.status()).isEqualTo(400);
            assertThat(privateKey.json().get("error").get("message").asText()).contains("私钥");
            assertThat(Files.exists(server.paths.trustCa().resolve("secret.pem"))).isFalse();

            TestServer.Reply blocked = server.console(
                    "POST", "/console/api/tls/probe", body("host", "example.com", "port", 443), true);
            assertThat(blocked.status()).isEqualTo(400);
            assertThat(blocked.json().get("error").get("message").asText()).contains("不在已安装规则");

            String yaml = LocalConchBehaviorTest.ping("tls_lab", "1.0.0")
                    .replace("base_url: http://127.0.0.1:9/", "base_url: " + https.url());
            server.runtime.registry().put(ConchParser.parse(yaml));

            HttpClient caller = OutboundTls.current().httpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            try {
                caller.send(
                        HttpRequest.newBuilder(URI.create(https.url())).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                throw new AssertionError("untrusted leaf should fail");
            } catch (AssertionError ex) {
                throw ex;
            } catch (Exception ex) {
                TransportFailure.View view = TransportFailure.observe(ex, "127.0.0.1", https.port());
                assertThat(view.kind().name()).startsWith("TLS_");
            }
            JsonNode failures = server.console("GET", "/console/api/tls/failures", null, true).json().get("data").get("failures");
            JsonNode failure = findHost(failures, "127.0.0.1", https.port());
            assertThat(failure.get("kind").asText()).startsWith("TLS_");
            assertThat(failure.get("chain").get(0).get("pem").asText()).contains("BEGIN CERTIFICATE");

            TestServer.Reply unseen = server.console(
                    "POST", "/console/api/tls/trusted-hosts",
                    body("host", "127.0.0.1", "port", https.port(), "sha256", "ab".repeat(32)),
                    true);
            assertThat(unseen.status()).isEqualTo(400);
            assertThat(unseen.json().get("error").get("message").asText()).contains("最近一小时");

            TestServer.Reply probed = server.console(
                    "POST", "/console/api/tls/probe", body("host", "127.0.0.1", "port", https.port()), true);
            assertThat(probed.status()).isEqualTo(200);
            assertThat(probed.json().get("data").get("trusted").asBoolean()).isFalse();
            assertThat(probed.json().get("data").get("chain").get(0).get("pem").asText()).contains("BEGIN CERTIFICATE");
            String leafSha = probed.json().get("data").get("chain").get(0).get("sha256").asText();

            https.use(new X509Certificate[] {certs.expired()}, certs.leafKey(), false);
            TestServer.Reply expiredProbe = server.console(
                    "POST", "/console/api/tls/probe", body("host", "127.0.0.1", "port", https.port()), true);
            String expiredSha = expiredProbe.json().get("data").get("chain").get(0).get("sha256").asText();
            TestServer.Reply expired = server.console(
                    "POST", "/console/api/tls/trusted-hosts",
                    body("host", "127.0.0.1", "port", https.port(), "sha256", expiredSha),
                    true);
            assertThat(expired.status()).isEqualTo(400);
            assertThat(expired.json().get("error").get("message").asText()).contains("过期");

            https.use(new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey(), true);
            server.console("POST", "/console/api/tls/probe", body("host", "127.0.0.1", "port", https.port()), true);
            TestServer.Reply trusted = server.console(
                    "POST", "/console/api/tls/trusted-hosts",
                    body("host", "127.0.0.1", "port", https.port(), "sha256", leafSha),
                    true);
            assertThat(trusted.status()).isEqualTo(200);
            assertThat(trusted.json().get("data").get("sha256").asText()).isEqualTo(leafSha);
            assertThat(trusted.json().get("data").get("createdBy").asText()).isEqualTo("console");

            try (CloseableHttpClient apache = apache()) {
                assertThat(status(apache, https.url())).isEqualTo(200);
                TestServer.Reply removed = server.console(
                        "DELETE", "/console/api/tls/trusted-hosts/127.0.0.1/" + https.port(), null, true);
                assertThat(removed.json().get("data").get("deleted").asBoolean()).isTrue();
                TransportFailure.View again = failure(apache, https.url(), https.port());
                assertThat(again.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
            }

            TestServer.Reply imported = server.console(
                    "POST", "/console/api/tls/ca", body("name", "root.pem", "pem", TrustCert.pem(certs.root())), true);
            assertThat(imported.status()).isEqualTo(200);
            assertThat(names(imported.json().get("data").get("caFiles"), "name")).contains("root.pem");
            assertThat(source(imported.json().get("data").get("sources"), "CA_DIR").get("count").asInt()).isPositive();

            Files.writeString(server.paths.trustCa().resolve("broken.pem"), "not a certificate");
            TestServer.Reply reloaded = server.console("POST", "/console/api/tls/reload", "{}", true);
            JsonNode broken = findFile(reloaded.json().get("data").get("loadErrors"), "broken.pem");
            assertThat(broken.get("message").asText()).contains("不是证书");

            TestServer.Reply deleted = server.console("DELETE", "/console/api/tls/ca/root.pem", null, true);
            assertThat(deleted.json().get("data").get("deleted").asBoolean()).isTrue();
            assertThat(Files.exists(server.paths.trustCa().resolve("root.pem"))).isFalse();

            JsonNode entries = server.console("GET", "/console/api/audit", null, true).json().get("data").get("entries");
            assertThat(names(entries, "action")).contains(
                    "TLS_HOST_TRUSTED", "TLS_HOST_REMOVED", "TLS_CA_ADDED", "TLS_RELOADED", "TLS_CA_REMOVED");
            assertThat(server.console("GET", "/console/api/tls/trust", null, false).status()).isEqualTo(403);
        }
    }

    @Test
    void caAuditRecordsEachCertificateSubjectAndFingerprint() throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("certs"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("home"), "HermitTlsAudit-91ab", config)) {
            String pem = TrustCert.pem(certs.root()) + TrustCert.pem(certs.intermediate());
            TestServer.Reply imported = server.console(
                    "POST", "/console/api/tls/ca", body("name", "bundle.pem", "pem", pem), true);
            assertThat(imported.status()).as(imported.body()).isEqualTo(200);

            JsonNode added = ApiJson.tree(auditDetail(server, "TLS_CA_ADDED", "bundle.pem"));
            assertThat(added.get("file").asText()).isEqualTo("bundle.pem");
            assertThat(added.get("fields").get(0).asText()).isEqualTo("bundle.pem");
            assertThat(fingerprints(added.get("certificates"))).containsExactly(
                    TrustCert.sha256(certs.root()), TrustCert.sha256(certs.intermediate()));
            assertThat(subjects(added.get("certificates"))).containsExactly(
                    TrustCert.of(certs.root()).subject(), TrustCert.of(certs.intermediate()).subject());

            TestServer.Reply deleted = server.console("DELETE", "/console/api/tls/ca/bundle.pem", null, true);
            assertThat(deleted.json().get("data").get("deleted").asBoolean()).isTrue();
            JsonNode removed = ApiJson.tree(auditDetail(server, "TLS_CA_REMOVED", "bundle.pem"));
            assertThat(fingerprints(removed.get("certificates"))).containsExactly(
                    TrustCert.sha256(certs.root()), TrustCert.sha256(certs.intermediate()));
            assertThat(subjects(removed.get("certificates"))).containsExactly(
                    TrustCert.of(certs.root()).subject(), TrustCert.of(certs.intermediate()).subject());

            Files.writeString(server.paths.trustCa().resolve("broken.pem"), "not a certificate");
            TestServer.Reply broken = server.console("DELETE", "/console/api/tls/ca/broken.pem", null, true);
            assertThat(broken.json().get("data").get("deleted").asBoolean()).isTrue();
            JsonNode skipped = ApiJson.tree(auditDetail(server, "TLS_CA_REMOVED", "broken.pem"));
            assertThat(skipped.get("certificates")).isEmpty();
        }
    }

    @Test
    void notYetValidLeafIsRefusedAndAPinStillFailsTheHandshake() throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("certs"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("home"), "HermitTlsEarly-91ab", config);
             LocalHttps https = LocalHttps.start(
                     new X509Certificate[] {certs.notYetValid()}, certs.leafKey())) {
            register(server, "tls_early", https.url());
            TestServer.Reply probed = server.console(
                    "POST", "/console/api/tls/probe", body("host", "127.0.0.1", "port", https.port()), true);
            assertThat(probed.status()).as(probed.body()).isEqualTo(200);
            String sha = probed.json().get("data").get("chain").get(0).get("sha256").asText();
            assertThat(sha).isEqualTo(TrustCert.sha256(certs.notYetValid()));

            TestServer.Reply refused = server.console(
                    "POST", "/console/api/tls/trusted-hosts",
                    body("host", "127.0.0.1", "port", https.port(), "sha256", sha),
                    true);
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.json().get("error").get("message").asText()).contains("尚未生效");

            OutboundTls.current().setPins(pin("127.0.0.1", https.port(), sha));
            try (CloseableHttpClient apache = apache()) {
                TransportFailure.View handshake = failure(apache, https.url(), https.port());
                assertThat(handshake.kind()).isEqualTo(TransportFailure.Kind.TLS_EXPIRED);
                assertThat(handshake.message()).contains("尚未生效");
            }
        }
    }

    @Test
    void concurrentTrustAndRemoveStillDropsSessions() throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("certs"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("home"), "HermitTlsRace-91ab", config);
             LocalHttps primary = LocalHttps.start(
                     new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey());
             LocalHttps other = LocalHttps.start(
                     new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey())) {
            primary.use(new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey(), true);
            register(server, "tls_primary", primary.url());
            register(server, "tls_other", other.url());
            String primarySha = probeSha(server, primary.port());
            String otherSha = probeSha(server, other.port());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try (CloseableHttpClient apache = apache()) {
                for (int attempt = 0; attempt < 8; attempt++) {
                    server.runtime.tlsTrust().trustHost("127.0.0.1", primary.port(), primarySha);
                    assertThat(status(apache, primary.url())).isEqualTo(200);
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<Map<String, Object>> removed = pool.submit(() -> {
                        await(barrier);
                        return server.runtime.tlsTrust().removeHost("127.0.0.1", primary.port());
                    });
                    Future<Map<String, Object>> trusted = pool.submit(() -> {
                        await(barrier);
                        return server.runtime.tlsTrust().trustHost("127.0.0.1", other.port(), otherSha);
                    });
                    assertThat(removed.get(10, TimeUnit.SECONDS).get("deleted")).isEqualTo(true);
                    assertThat(trusted.get(10, TimeUnit.SECONDS).get("port")).isEqualTo(other.port());
                    TransportFailure.View again = failure(apache, primary.url(), primary.port());
                    assertThat(again.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void authOriginCanBeProbedLikeTheBaseUrl() throws Exception {
        // 登录步骤要连认证地址，它的证书不被信任时也得能在控制台检测、再决定信不信。
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("certs"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("home"), "HermitTlsAuth-91ab", config);
             LocalHttps sso = LocalHttps.start(
                     new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey());
             LocalHttps viaConfig = LocalHttps.start(
                     new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey())) {
            TestServer.Reply blocked = server.console(
                    "POST", "/console/api/tls/probe", body("host", "127.0.0.1", "port", sso.port()), true);
            assertThat(blocked.status()).isEqualTo(400);

            String yaml = LocalConchBehaviorTest.ping("tls_sso", "1.0.0")
                    .replace("min_crab_version: 1.0.0", "min_crab_version: 1.11.0")
                    .replace("credentials: {}", """
                            credentials: {}
                            config:
                              sso_url: { type: url, label: 认证中心, default: "%s" }
                            auth:
                              origins: ["%s", "{{config.sso_url}}"]
                              steps:
                                - request: { method: GET, path: / }""".formatted(viaConfig.url(), sso.url()));
            server.runtime.registry().put(ConchParser.parse(yaml));

            probeSha(server, sso.port());
            probeSha(server, viaConfig.port());
        }
    }

    /** M11：整包停用时，配置项里填写的 auth.origins 仍应允许在控制台检测证书。 */
    @Test
    void switchedOffPackageConfiguredAuthOriginCanStillBeProbed() throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("certs"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("home"), "HermitTlsOff-91ab", config);
             LocalHttps viaConfig = LocalHttps.start(
                     new X509Certificate[] {certs.leaf(), certs.root()}, certs.leafKey())) {
            String yaml = LocalConchBehaviorTest.ping("tls_sso_off", "1.0.0")
                    .replace("min_crab_version: 1.0.0", "min_crab_version: 1.11.0")
                    .replace("credentials: {}", """
                            credentials: {}
                            config:
                              sso_url: { type: url, label: 认证中心, required: false }
                            auth:
                              origins: ["{{config.sso_url}}"]
                              steps:
                                - request: { method: GET, path: / }""");
            var doc = ConchParser.parse(yaml);
            server.runtime.registry().put(doc);
            server.runtime.admin().putConfig(doc, "sso_url", viaConfig.url(), dev.hermit.crab.store.Actors.CLI);
            server.runtime.setConchDisabled("tls_sso_off", true, dev.hermit.crab.store.Actors.CLI);

            probeSha(server, viaConfig.port());
        }
    }

    private static String probeSha(TestServer server, int port) throws Exception {
        TestServer.Reply probed = server.console(
                "POST", "/console/api/tls/probe", body("host", "127.0.0.1", "port", port), true);
        assertThat(probed.status()).as(probed.body()).isEqualTo(200);
        return probed.json().get("data").get("chain").get(0).get("sha256").asText();
    }

    private static void register(TestServer server, String name, String url) {
        String yaml = LocalConchBehaviorTest.ping(name, "1.0.0")
                .replace("base_url: http://127.0.0.1:9/", "base_url: " + url);
        server.runtime.registry().put(ConchParser.parse(yaml));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static HostPins pin(String host, int port, String sha256) {
        String key = host + ":" + port;
        HostPins.Pin pinned = new HostPins.Pin(sha256, null);
        return new HostPins() {
            @Override
            public HostPins.Pin find(String candidate, int candidatePort) {
                return (candidate + ":" + candidatePort).equals(key) ? pinned : null;
            }

            @Override
            public Set<String> keys() {
                return Set.of(key);
            }
        };
    }

    private static String auditDetail(TestServer server, String kind, String target) throws Exception {
        try (Connection connection = server.runtime.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT detail FROM config_audit WHERE kind = ? AND target = ? ORDER BY id DESC
                     """)) {
            statement.setString(1, kind);
            statement.setString(2, target);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static List<String> fingerprints(JsonNode certificates) {
        return names(certificates, "sha256");
    }

    private static List<String> subjects(JsonNode certificates) {
        return names(certificates, "subject");
    }

    private static CloseableHttpClient apache() {
        var pool = OutboundTls.current().track(PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(OutboundTls.current().tlsStrategy())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(5))
                        .build())
                .build());
        return HttpClients.custom().setConnectionManager(pool).disableAutomaticRetries().build();
    }

    private static int status(CloseableHttpClient client, String url) throws Exception {
        return client.execute(new HttpGet(url), response -> {
            if (response.getEntity() != null) {
                response.getEntity().getContent().readAllBytes();
            }
            return response.getCode();
        });
    }

    private static TransportFailure.View failure(CloseableHttpClient client, String url, int port) {
        try {
            status(client, url);
        } catch (Exception ex) {
            return TransportFailure.classify(ex, "127.0.0.1", port);
        }
        throw new AssertionError("expected the pinned host to fail after the pin was removed");
    }

    private static String body(Object... pairs) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return ApiJson.write(map);
    }

    private static List<String> names(JsonNode rows, String field) {
        ArrayList<String> names = new ArrayList<>();
        rows.forEach(row -> names.add(row.get(field).asText()));
        return names;
    }

    private static JsonNode source(JsonNode sources, String kind) {
        for (JsonNode source : sources) {
            if (kind.equals(source.get("kind").asText())) {
                return source;
            }
        }
        throw new AssertionError(kind);
    }

    private static JsonNode findHost(JsonNode rows, String host, int port) {
        for (JsonNode row : rows) {
            if (host.equals(row.get("host").asText()) && row.get("port").asInt() == port) {
                return row;
            }
        }
        throw new AssertionError(host + ":" + port);
    }

    private static JsonNode findFile(JsonNode rows, String file) {
        for (JsonNode row : rows) {
            if (row.get("file") != null && file.equals(row.get("file").asText())) {
                return row;
            }
        }
        throw new AssertionError(file);
    }
}
