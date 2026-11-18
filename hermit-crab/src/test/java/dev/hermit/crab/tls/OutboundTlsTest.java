package dev.hermit.crab.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Set;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;

class OutboundTlsTest {

    @TempDir
    static Path root;

    private static LabCertificates.Material certs;
    private static LocalHttps https;

    @BeforeAll
    static void lab() throws Exception {
        certs = LabCertificates.generate(root.resolve("lab"));
        assertThat(certs.leaf().getExtensionValue("2.5.29.35")).isNull();
        https = LocalHttps.start(new X509Certificate[] {certs.leaf()}, certs.leafKey());
    }

    @AfterAll
    static void stop() {
        if (https != null) {
            https.close();
        }
    }

    @Test
    void emptyTrustNamesTheIssuerAndReloadTrustsWithoutANewClient(@TempDir Path caDir) throws Exception {
        OutboundTls tls = OutboundTls.open(caDir, true);
        try (CloseableHttpClient apache = apache(tls)) {
            HttpClient jdk = jdk(tls);
            TransportFailure.View apacheFailure = apacheFailure(apache);
            TransportFailure.View jdkFailure = jdkFailure(jdk);
            assertThat(apacheFailure.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
            assertThat(jdkFailure.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
            assertThat(apacheFailure.message()).contains("Hermit Lab Root");
            assertThat(jdkFailure.message()).contains("Hermit Lab Root");
            assertThat(jdkFailure.chain()).isNotEmpty();

            Files.copy(certs.rootPem(), caDir.resolve("root.pem"));
            tls.reload(caDir, true);
            assertThat(apacheStatus(apache)).isEqualTo(200);
            assertThat(jdkStatus(jdk)).isEqualTo(200);
            assertThat(tls.sources()).extracting(TrustSource::kind).containsExactly("JDK", "OS", "CA_DIR");
            TrustSource directory = source(tls, "CA_DIR");
            assertThat(directory.count()).isEqualTo(1);
            assertThat(directory.location()).isEqualTo(caDir.toAbsolutePath().normalize().toString());
            assertThat(directory.error()).isNull();
            assertThat(source(tls, "JDK").count()).isPositive();
        }
    }

    @Test
    void trustsALeafOrAnIntermediateAsAnAnchor(@TempDir Path caDir) throws Exception {
        OutboundTls tls = OutboundTls.open(caDir, false);
        try (CloseableHttpClient apache = apache(tls)) {
            LabCertificates.writePem(caDir.resolve("leaf.pem"), certs.leaf());
            tls.reload(caDir, false);
            assertThat(apacheStatus(apache)).isEqualTo(200);

            Files.delete(caDir.resolve("leaf.pem"));
            LabCertificates.writePem(caDir.resolve("inter.pem"), certs.intermediate());
            https.use(new X509Certificate[] {certs.intermediateLeaf()}, certs.intermediateLeafKey(), false);
            tls.reload(caDir, false);
            assertThat(apacheStatus(apache)).isEqualTo(200);

            Files.delete(caDir.resolve("inter.pem"));
            LabCertificates.writePem(caDir.resolve("root.pem"), certs.root());
            https.use(new X509Certificate[] {certs.intermediateLeaf(), certs.intermediate()}, certs.intermediateLeafKey(), false);
            tls.reload(caDir, false);
            assertThat(apacheStatus(apache)).isEqualTo(200);

            https.use(new X509Certificate[] {certs.intermediateLeaf()}, certs.intermediateLeafKey(), false);
            Files.delete(caDir.resolve("root.pem"));
            tls.reload(caDir, false);
            LabCertificates.writePem(caDir.resolve("root.pem"), certs.root());
            tls.reload(caDir, false);
            TransportFailure.View missing = apacheFailure(apache);
            assertThat(missing.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
            assertThat(missing.message()).contains("Hermit Lab Intermediate").doesNotContain("没有发送中间证书");
        } finally {
            https.use(new X509Certificate[] {certs.leaf()}, certs.leafKey(), false);
        }
    }

    @Test
    void hostnameAndExpiryUseTheHandshakeRecord(@TempDir Path caDir) throws Exception {
        LabCertificates.writePem(caDir.resolve("root.pem"), certs.root());
        OutboundTls tls = OutboundTls.open(caDir, false);
        try (CloseableHttpClient apache = apache(tls)) {
            https.use(new X509Certificate[] {certs.wrongName()}, certs.wrongKey(), false);
            TransportFailure.View hostname = apacheFailure(apache);
            assertThat(hostname.kind()).isEqualTo(TransportFailure.Kind.TLS_HOSTNAME);
            assertThat(hostname.message()).isEqualTo("上游证书与主机名 127.0.0.1 不符");

            https.use(new X509Certificate[] {certs.expired()}, certs.leafKey(), false);
            TransportFailure.View expired = apacheFailure(apache);
            assertThat(expired.kind()).isEqualTo(TransportFailure.Kind.TLS_EXPIRED);
            assertThat(expired.message()).contains("2020-01-02");

            https.use(new X509Certificate[] {certs.notYetValid()}, certs.leafKey(), false);
            assertThat(apacheFailure(apache).message()).contains("尚未生效").contains("2035-01-01");
        } finally {
            https.use(new X509Certificate[] {certs.leaf()}, certs.leafKey(), false);
        }
    }

    @Test
    void pinSkipsPkixAndHostnameButNotExpiry(@TempDir Path caDir) throws Exception {
        OutboundTls tls = OutboundTls.open(caDir, false);
        try (CloseableHttpClient apache = apache(tls)) {
            https.use(new X509Certificate[] {certs.wrongName()}, certs.wrongKey(), false);
            tls.setPins(pins(TrustCert.sha256(certs.wrongName())));
            assertThat(apacheStatus(apache)).isEqualTo(200);

            https.use(new X509Certificate[] {certs.expired()}, certs.leafKey(), false);
            tls.setPins(HostPins.NONE);
            tls.setPins(pins(TrustCert.sha256(certs.expired())));
            TransportFailure.View expired = apacheFailure(apache);
            assertThat(expired.kind()).isEqualTo(TransportFailure.Kind.TLS_EXPIRED);
            assertThat(expired.message()).contains("2020-01-02");
        } finally {
            https.use(new X509Certificate[] {certs.leaf()}, certs.leafKey(), false);
        }
    }

    @Test
    void removingACaClosesTheIdleApacheConnection(@TempDir Path caDir) throws Exception {
        LabCertificates.writePem(caDir.resolve("root.pem"), certs.root());
        OutboundTls tls = OutboundTls.open(caDir, false);
        https.use(new X509Certificate[] {certs.leaf()}, certs.leafKey(), true);
        try (CloseableHttpClient apache = apache(tls)) {
            assertThat(apacheStatus(apache)).isEqualTo(200);
            Files.delete(caDir.resolve("root.pem"));
            tls.reload(caDir, false);
            TransportFailure.View again = apacheFailure(apache);
            assertThat(again.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
        } finally {
            https.use(new X509Certificate[] {certs.leaf()}, certs.leafKey(), false);
        }
    }

    @Test
    void systemTrustCanBeTurnedOffAndAMissingDirectoryDoesNotStopStartup(@TempDir Path home) throws Exception {
        Path missing = home.resolve("custom-ca");
        OutboundTls off = OutboundTls.open(missing, false);
        assertThat(source(off, "OS").count()).isZero();
        assertThat(source(off, "OS").error()).isNull();
        assertThat(source(off, "CA_DIR").count()).isZero();
        assertThat(source(off, "CA_DIR").error()).contains("目录不存在");
        assertThat(off.anchorCount()).isPositive();

        OutboundTls on = OutboundTls.open(home.resolve("empty"), true);
        Files.createDirectories(home.resolve("empty"));
        on.reload(home.resolve("empty"), true);
        TrustSource os = source(on, "OS");
        assertThat(os.count() > 0 || os.error() != null).isTrue();
        if (os.error() == null) {
            assertThat(os.location()).contains("ca-certificates.crt");
        }
    }

    private static TrustSource source(OutboundTls tls, String kind) {
        return tls.sources().stream().filter(item -> item.kind().equals(kind)).findFirst().orElseThrow();
    }

    private static HostPins pins(String sha256) {
        String key = "127.0.0.1:" + https.port();
        HostPins.Pin pin = new HostPins.Pin(sha256, null);
        return new HostPins() {
            @Override
            public Pin find(String host, int port) {
                return (host + ":" + port).equals(key) ? pin : null;
            }

            @Override
            public Set<String> keys() {
                return Set.of(key);
            }
        };
    }

    private static CloseableHttpClient apache(OutboundTls tls) {
        PoolingHttpClientConnectionManager pool = tls.track(PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tls.tlsStrategy())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(5))
                        .build())
                .build());
        return HttpClients.custom()
                .setConnectionManager(pool)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
    }

    private static HttpClient jdk(OutboundTls tls) {
        return tls.httpClientBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static int apacheStatus(CloseableHttpClient client) throws IOException {
        return client.execute(new HttpGet(https.url()), response -> {
            if (response.getEntity() != null) {
                response.getEntity().getContent().readAllBytes();
            }
            return response.getCode();
        });
    }

    private static TransportFailure.View apacheFailure(CloseableHttpClient client) {
        assertThatThrownBy(() -> apacheStatus(client)).isInstanceOf(IOException.class);
        try {
            apacheStatus(client);
            throw new AssertionError("expected a TLS failure");
        } catch (IOException ex) {
            return TransportFailure.classify(ex, "127.0.0.1", https.port());
        }
    }

    private static int jdkStatus(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(https.url())).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static TransportFailure.View jdkFailure(HttpClient client) {
        try {
            jdkStatus(client);
            throw new AssertionError("expected a TLS failure");
        } catch (Exception ex) {
            return TransportFailure.classify(ex, "127.0.0.1", https.port());
        }
    }
}
