package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;
import dev.hermit.crab.tls.OutboundTls;

class HttpCallsTransportTest {

    @Test
    void dnsRefusedAndTimeoutKeepTheirMessages() throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.LOCAL, "lab", "a");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                CloseableHttpClient client = sessions.client(key);
                RuleProblem dns = failure(client, Map.of("url", "http://no-such-host.invalid/"));
                assertThat(dns.summary).startsWith("无法解析上游地址");
                assertThat(dns.transport).containsEntry("kind", "DNS");

                int port;
                try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                    port = closed.getLocalPort();
                }
                RuleProblem refused = failure(client, Map.of("url", "http://127.0.0.1:" + port + "/"));
                assertThat(refused.summary).contains("上游拒绝连接（127.0.0.1:" + port + "）");
                assertThat(refused.transport).containsEntry("kind", "REFUSED");

                try (ServerSocket hanging = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                    Thread hold = new Thread(() -> {
                        try {
                            hanging.accept();
                            Thread.sleep(5_000);
                        } catch (Exception ignored) {
                            // 测试结束。
                        }
                    });
                    hold.setDaemon(true);
                    hold.start();
                    RuleProblem timeout = failure(client, Map.of(
                            "url", "http://127.0.0.1:" + hanging.getLocalPort() + "/",
                            "timeout_ms", 300));
                    assertThat(timeout.summary).isEqualTo(HttpCalls.STEP_TIMEOUT);
                    assertThat(timeout.transport).isNull();
                }
            } finally {
                sessions.unlock(key);
            }
        }
    }

    @Test
    void untrustedHttpsIsSpecificAndReloadReusesTheAccountClient(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir.resolve("lab"));
        Path caDir = dir.resolve("ca");
        Files.createDirectories(caDir);
        try (LocalHttps https = LocalHttps.start(new X509Certificate[] {certs.leaf()}, certs.leafKey())) {
            OutboundTls tls = OutboundTls.open(caDir, true);
            OutboundTls previous = OutboundTls.install(tls);
            try (SessionManager sessions = new SessionManager()) {
                SessionKey key = new SessionKey(Origin.LOCAL, "lab", "a");
                assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
                try {
                    CloseableHttpClient client = sessions.client(key);
                    RuleProblem untrusted = failure(client, Map.of("url", https.url()));
                    assertThat(untrusted.summary).contains("不受信任").contains("Hermit Lab Root");
                    assertThat(untrusted.summary).doesNotContain("上游连接失败");
                    assertThat(untrusted.transport.get("kind")).isEqualTo("TLS_UNTRUSTED");
                    assertThat(untrusted.transport.get("chain")).asList().isNotEmpty();

                    Files.copy(certs.rootPem(), caDir.resolve("root.pem"));
                    tls.reload(caDir, true);
                    assertThat(sessions.client(key)).isSameAs(client);
                    HttpResult result = HttpCalls.execute(
                            Map.of("url", https.url()), client, () -> { }, URI.create(https.url()), null, deadline());
                    assertThat(result.status()).isEqualTo(200);
                } finally {
                    sessions.unlock(key);
                }
            } finally {
                OutboundTls.restore(previous);
            }
        }
    }

    private static RuleProblem failure(CloseableHttpClient client, Map<String, Object> spec) {
        URI base = URI.create(String.valueOf(spec.get("url")));
        try {
            HttpCalls.execute(spec, client, () -> { }, base, null, deadline());
        } catch (RuleProblem problem) {
            return problem;
        }
        throw new AssertionError("expected a RuleProblem");
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }
}
