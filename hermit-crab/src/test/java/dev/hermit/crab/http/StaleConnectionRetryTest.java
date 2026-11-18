package dev.hermit.crab.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaleConnectionRetryTest {

    @Test
    void retriesOnlyTheFirstStaleIdempotentAttempt() {
        StaleConnectionRetry retry = StaleConnectionRetry.INSTANCE;
        BasicHttpRequest get = new BasicHttpRequest("GET", "/t");
        NoHttpResponseException stale = new NoHttpResponseException("server dropped");

        assertThat(retry.retryRequest(get, stale, 1, null)).isTrue();
        assertThat(retry.retryRequest(get, stale, 2, null)).isFalse();
        assertThat(retry.retryRequest(new BasicHttpRequest("HEAD", "/t"), stale, 1, null)).isTrue();
        assertThat(retry.retryRequest(new BasicHttpRequest("OPTIONS", "/t"), stale, 1, null)).isTrue();
        assertThat(retry.retryRequest(new BasicHttpRequest("get", "/t"), stale, 1, null)).isTrue();

        assertThat(retry.retryRequest(new BasicHttpRequest("POST", "/t"), stale, 1, null)).isFalse();
        assertThat(retry.retryRequest(new BasicHttpRequest("PUT", "/t"), stale, 1, null)).isFalse();
        assertThat(retry.retryRequest(new BasicHttpRequest("DELETE", "/t"), stale, 1, null)).isFalse();

        assertThat(retry.retryRequest(get, new SocketException("Connection reset by peer"), 1, null)).isTrue();
        assertThat(retry.retryRequest(get, new IOException("connection reset"), 1, null)).isTrue();
        assertThat(retry.retryRequest(get, new ConnectException("Connection refused"), 1, null)).isFalse();
        assertThat(retry.retryRequest(get, new SocketTimeoutException("Read timed out"), 1, null)).isFalse();
        assertThat(retry.retryRequest(get, new IOException("Broken pipe"), 1, null)).isFalse();

        assertThat(retry.retryRequest(new BasicClassicHttpResponse(503), 1, null)).isFalse();
        assertThat(retry.retryRequest(new BasicClassicHttpResponse(429), 1, null)).isFalse();
        assertThat(retry.retryRequest(new BasicClassicHttpResponse(200), 1, null)).isFalse();
    }
}
