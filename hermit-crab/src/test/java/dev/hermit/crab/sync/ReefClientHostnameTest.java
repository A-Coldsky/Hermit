package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;
import dev.hermit.crab.tls.OutboundTls;
import dev.hermit.crab.tls.TransportFailure;

class ReefClientHostnameTest {

    @Test
    void reefClientRejectsATrustedCertificateWithTheWrongHostname(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir.resolve("lab"));
        Path caDir = dir.resolve("ca");
        Files.createDirectories(caDir);
        LabCertificates.writePem(caDir.resolve("root.pem"), certs.root());
        OutboundTls tls = OutboundTls.open(caDir, false);
        try (LocalHttps https = LocalHttps.start(new X509Certificate[] {certs.wrongName()}, certs.wrongKey())) {
            HttpClient http = tls.httpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            ReefClient reef = new ReefClient(new AtomicReference<>(new SyncSettings(
                    URI.create(https.url()),
                    Optional.empty(),
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),
                    Duration.ofMinutes(5),
                    UUID.randomUUID())), http);
            try {
                reef.manifest(null);
                throw new AssertionError("expected a hostname failure");
            } catch (SyncException ex) {
                TransportFailure.View view = TransportFailure.classify(ex.getCause(), "127.0.0.1", https.port());
                assertThat(view.kind()).isEqualTo(TransportFailure.Kind.TLS_HOSTNAME);
                assertThat(view.message()).contains("127.0.0.1");
                assertThat(ex.clientError().orElseThrow().message()).contains("主机名");
            }
        }
    }
}
