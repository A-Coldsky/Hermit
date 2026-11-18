package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.McpAuth;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;
import dev.hermit.crab.tls.OutboundTls;

/**
 * 证书已在 CA 目录里受信任，但主机名不对。这些客户端必须仍报 TLS_HOSTNAME。
 * 主机名校验依赖客户端把 endpointIdentificationAlgorithm 设成 HTTPS。
 */
class ProductionClientHostnameTest {

    @TempDir
    static Path root;

    private static LabCertificates.Material certs;
    private static OutboundTls tls;
    private static OutboundTls previous;

    @BeforeAll
    static void trustTheLabRoot() throws Exception {
        certs = LabCertificates.generate(root.resolve("lab"));
        Path caDir = root.resolve("ca");
        Files.createDirectories(caDir);
        LabCertificates.writePem(caDir.resolve("root.pem"), certs.root());
        tls = OutboundTls.open(caDir, false);
        previous = OutboundTls.install(tls);
    }

    @AfterAll
    static void restore() {
        OutboundTls.restore(previous);
    }

    @Test
    void mcpUpstreamRejectsATrustedCertificateWithTheWrongHostname() throws Exception {
        try (LocalHttps https = server(); McpUpstreamClient client = new McpUpstreamClient()) {
            McpUpstreamClient.RpcResult result = client.exchange(new McpUpstreamClient.Post(
                    "POST",
                    URI.create(https.url()),
                    Map.of(),
                    null,
                    null,
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    null,
                    deadline(),
                    null,
                    true,
                    false));
            assertThat(result.kind()).isEqualTo(McpUpstreamClient.RpcKind.CONNECT);
            assertThat(result.detail()).contains("主机名").contains("127.0.0.1");
            assertThat(result.transport()).containsEntry("kind", "TLS_HOSTNAME");
        }
    }

    @Test
    void oauthDiscoveryAndTokenRejectATrustedCertificateWithTheWrongHostname() throws Exception {
        try (LocalHttps https = server(); McpOAuthClient client = new McpOAuthClient()) {
            McpOAuthClient.Target target = new McpOAuthClient.Target(https.url() + "mcp", List.of("127.0.0.1"));
            McpOAuthClient.Resolved discovered = client.resolve(
                    target,
                    new McpAuth("oauth", List.of(), "lab-client", null, null),
                    deadline());
            assertThat(discovered.ok()).isFalse();
            assertThat(discovered.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(discovered.message()).contains("主机名").contains("127.0.0.1");
            assertThat(discovered.message()).isNotEqualTo(McpOAuth.DISCOVERY_FAILED);

            McpOAuthClient.Exchange token = client.exchangeCode(
                    target,
                    https.url() + "token",
                    "lab-client",
                    https.url(),
                    "code",
                    "https://127.0.0.1/callback",
                    "verifier",
                    null,
                    Instant.now(),
                    deadline());
            assertThat(token.kind()).isEqualTo(McpOAuthClient.ExchangeKind.CONNECT);
            assertThat(token.detail()).contains("主机名").contains("127.0.0.1");
        }
    }

    private static LocalHttps server() throws Exception {
        return LocalHttps.start(new X509Certificate[] {certs.wrongName()}, certs.wrongKey());
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    }
}
