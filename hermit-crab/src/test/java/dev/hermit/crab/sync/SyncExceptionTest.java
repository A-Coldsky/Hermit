package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;

class SyncExceptionTest {

    @Test
    void unreachableReefBecomesUpstreamErrorWithANextStep() {
        SyncError refused = new SyncException(
                "reef request failed", new ConnectException("Connection refused"), "10.1.2.3", 8443)
                .clientError()
                .orElseThrow();
        assertThat(refused.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(refused.message()).isEqualTo("上游拒绝连接（10.1.2.3:8443）");
        assertThat(refused.message()).doesNotContain("无法连接 Reef").doesNotContain("再次同步").doesNotContain("Connection refused");

        SyncError protocol = new SyncException(
                "reef request failed", new SSLHandshakeException("Received fatal alert: protocol_version"))
                .clientError()
                .orElseThrow();
        assertThat(protocol.message()).isEqualTo("对方只支持 TLS 1.0/1.1 等旧协议，Crab 默认不连。");

        SyncError unknown = new SyncException("reef request failed", new UnknownHostException("reef.internal"))
                .clientError()
                .orElseThrow();
        assertThat(unknown.message()).contains("无法解析").contains("reef.url").contains("再次同步");
        assertThat(unknown.message()).doesNotContain("reef.internal");

        SyncError timeout = new SyncException("reef request failed", new HttpConnectTimeoutException("timed out"))
                .clientError()
                .orElseThrow();
        assertThat(timeout.message()).contains("超时").contains("再次同步");
        assertThat(new SyncException("reef request failed", new HttpTimeoutException("read timed out")).clientError())
                .get()
                .extracting(SyncError::message)
                .asString()
                .contains("超时");

        assertThat(new SyncException("reef request interrupted").clientError().orElseThrow().message())
                .contains("中断")
                .contains("再次同步");

        ConnectException nestedDns = new ConnectException();
        nestedDns.initCause(new UnresolvedAddressException());
        SyncError unresolved = new SyncException("reef request failed", nestedDns).clientError().orElseThrow();
        assertThat(unresolved.message()).contains("无法解析").contains("reef.url");
        assertThat(unresolved.message()).doesNotContain("UnresolvedAddress");

        IOException nestedTimeout = new IOException("timed out");
        nestedTimeout.initCause(new HttpTimeoutException("request timed out"));
        assertThat(new SyncException("reef request failed", nestedTimeout).clientError().orElseThrow().message())
                .contains("超时");

        SyncError handshake = new SyncException(
                "reef request failed", new SSLHandshakeException("Missing Authority Key Identifier"))
                .clientError()
                .orElseThrow();
        assertThat(handshake.message()).contains("TLS 握手失败").doesNotContain("无法连接 Reef");
    }

    @Test
    void jdkHttpClientDnsFailureIsStillUnresolvable() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://no-such-host.invalid/")).build();
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            throw new AssertionError("expected a DNS failure");
        } catch (java.io.IOException ex) {
            assertThat(new SyncException("reef request failed", ex).clientError().orElseThrow().message())
                    .contains("无法解析")
                    .contains("reef.url");
        }
    }

    @Test
    void httpStatusFromReefIsNotAnUnreachableError() {
        assertThat(new SyncException(SyncReasons.http(500)).clientError()).isEmpty();
        assertThat(new SyncException(SyncReasons.http(404)).clientError()).isEmpty();
        assertThat(new SyncException("manifest rejected").clientError()).isEmpty();
    }

    @Test
    void manifestUnauthorizedIsAuthFailedAndNotUnreachable() {
        for (int status : List.of(401, 403)) {
            SyncError rejected = new SyncException(SyncReasons.http(status)).clientError().orElseThrow();
            assertThat(rejected.code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(rejected.message()).isEqualTo(SyncException.TOKEN_REJECTED);
            assertThat(rejected.message()).doesNotContain("无法连接").doesNotContain("HTTP_");
        }
    }
}
