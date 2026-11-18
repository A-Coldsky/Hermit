package dev.hermit.crab.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

import dev.hermit.crab.tls.TransportFailure;

/**
 * 只在 GET、HEAD、OPTIONS 复用一条已经发送过请求的连接，并且第一次撞上「对端没回响应」或连接被重置时再试一次。
 * 新建立的连接上的失败不重试。不重试 POST 和其他方法，也不看状态码，所以 503 和 429 不会重放。
 * 超时和已取消的请求也不重试。{@code execCount} 从 1 开始，等于 1 才允许这一次重试。
 * 请求头写出后连接上的请求计数才加一，所以复用连接时 {@code requestCount > 1}。
 */
public final class StaleConnectionRetry implements HttpRequestRetryStrategy {

    private static final Set<String> ONCE = Set.of("GET", "HEAD", "OPTIONS");

    public static final StaleConnectionRetry INSTANCE = new StaleConnectionRetry();

    private StaleConnectionRetry() {
    }

    @Override
    public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
        if (execCount != 1 || request == null || exception == null) {
            return false;
        }
        if (cancelled(request, exception) || TransportFailure.isTimeout(exception)) {
            return false;
        }
        String method = request.getMethod();
        if (method == null || !ONCE.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        if (!reused(context)) {
            return false;
        }
        return stale(exception);
    }

    @Override
    public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
        return false;
    }

    @Override
    public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
        return TimeValue.ZERO_MILLISECONDS;
    }

    private static boolean cancelled(HttpRequest request, IOException exception) {
        if (request instanceof CancellableDependency dependency && dependency.isCancelled()) {
            return true;
        }
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof CancellableDependency dependency && dependency.isCancelled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这条连接在本次请求之前已经完成过至少一次请求。计数在请求头写出之后增加，
     * 新连接失败时是 0 或 1，复用后再失败才大于 1。
     */
    private static boolean reused(HttpContext context) {
        if (context == null) {
            return false;
        }
        HttpClientContext client = HttpClientContext.adapt(context);
        EndpointDetails details = client.getEndpointDetails();
        return details != null && details.getRequestCount() > 1;
    }

    private static boolean stale(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof NoHttpResponseException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("connection reset")) {
                return true;
            }
        }
        return false;
    }
}
