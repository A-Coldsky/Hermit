package dev.hermit.crab.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpConnectionMetrics;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.impl.BasicEndpointDetails;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaleConnectionRetryTest {

    @Test
    void retriesOnlyTheFirstStaleIdempotentAttemptOnAReusedConnection() {
        StaleConnectionRetry retry = StaleConnectionRetry.INSTANCE;
        BasicHttpRequest get = new BasicHttpRequest("GET", "/t");
        NoHttpResponseException stale = new NoHttpResponseException("server dropped");
        HttpContext reused = requests(2);
        HttpContext fresh = requests(1);

        assertThat(retry.retryRequest(get, stale, 1, reused)).isTrue();
        assertThat(retry.retryRequest(get, stale, 1, fresh)).isFalse();
        assertThat(retry.retryRequest(get, stale, 1, requests(0))).isFalse();
        assertThat(retry.retryRequest(get, stale, 1, HttpClientContext.create())).isFalse();
        assertThat(retry.retryRequest(get, stale, 1, null)).isFalse();
        assertThat(retry.retryRequest(get, stale, 2, reused)).isFalse();
        assertThat(retry.retryRequest(new BasicHttpRequest("HEAD", "/t"), stale, 1, reused)).isTrue();
        assertThat(retry.retryRequest(new BasicHttpRequest("OPTIONS", "/t"), stale, 1, reused)).isTrue();
        assertThat(retry.retryRequest(new BasicHttpRequest("get", "/t"), stale, 1, reused)).isTrue();

        assertThat(retry.retryRequest(new BasicHttpRequest("POST", "/t"), stale, 1, reused)).isFalse();
        assertThat(retry.retryRequest(new BasicHttpRequest("PUT", "/t"), stale, 1, reused)).isFalse();
        assertThat(retry.retryRequest(new BasicHttpRequest("DELETE", "/t"), stale, 1, reused)).isFalse();

        assertThat(retry.retryRequest(get, new SocketException("Connection reset by peer"), 1, reused)).isTrue();
        assertThat(retry.retryRequest(get, new SocketException("Connection reset by peer"), 1, fresh)).isFalse();
        assertThat(retry.retryRequest(get, new IOException("connection reset"), 1, reused)).isTrue();
        assertThat(retry.retryRequest(get, new ConnectException("Connection refused"), 1, reused)).isFalse();
        assertThat(retry.retryRequest(get, new SocketTimeoutException("Read timed out"), 1, reused)).isFalse();
        assertThat(retry.retryRequest(get, new IOException("Broken pipe"), 1, reused)).isFalse();

        assertThat(retry.retryRequest(new BasicClassicHttpResponse(503), 1, null)).isFalse();
        assertThat(retry.retryRequest(new BasicClassicHttpResponse(429), 1, null)).isFalse();
        assertThat(retry.retryRequest(new BasicClassicHttpResponse(200), 1, null)).isFalse();
    }

    private static HttpContext requests(long count) {
        HttpClientContext context = HttpClientContext.create();
        context.setEndpointDetails(new BasicEndpointDetails(null, null, new HttpConnectionMetrics() {
            @Override
            public long getRequestCount() {
                return count;
            }

            @Override
            public long getResponseCount() {
                return 0;
            }

            @Override
            public long getSentBytesCount() {
                return 0;
            }

            @Override
            public long getReceivedBytesCount() {
                return 0;
            }
        }, null));
        return context;
    }
}
