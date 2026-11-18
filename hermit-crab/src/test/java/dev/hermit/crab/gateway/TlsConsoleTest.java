package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
