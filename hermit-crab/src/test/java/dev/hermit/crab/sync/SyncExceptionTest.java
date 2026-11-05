package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;

class SyncExceptionTest {

    @Test
    void unreachableReefBecomesUpstreamErrorWithANextStep() {
        SyncError refused = new SyncException("reef request failed", new ConnectException("Connection refused"))
                .clientError()
                .orElseThrow();
        assertThat(refused.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(refused.message()).contains("无法连接 Reef").contains("再次同步");
        assertThat(refused.message()).doesNotContain("Connection refused").doesNotContain("reef request failed");

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
