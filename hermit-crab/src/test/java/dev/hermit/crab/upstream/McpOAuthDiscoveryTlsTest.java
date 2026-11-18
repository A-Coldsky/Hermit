package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.McpAuth;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;

class McpOAuthDiscoveryTlsTest {

    @Test
    void discoveryConnectFailureIsUpstreamErrorNotMissingEndpoints(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir.resolve("lab"));
        try (LocalHttps https = LocalHttps.start(new X509Certificate[] {certs.leaf()}, certs.leafKey());
                McpOAuthClient client = new McpOAuthClient()) {
            McpOAuthClient.Target target = new McpOAuthClient.Target(
                    "https://127.0.0.1:" + https.port() + "/mcp", List.of("127.0.0.1"));
            McpOAuthClient.Resolved resolved = client.resolve(
                    target,
                    new McpAuth("oauth", List.of(), "lab-client", null, null),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            assertThat(resolved.ok()).isFalse();
            assertThat(resolved.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(resolved.message()).contains("不受信任").contains("Hermit Lab Root");
            assertThat(resolved.message()).isNotEqualTo(McpOAuth.DISCOVERY_FAILED);
        }
    }
}
