package dev.hermit.crab.http;

import java.time.Duration;
import java.util.Objects;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import dev.hermit.crab.tls.OutboundTls;

/**
 * 规则 HTTP、MCP 转发和 MCP OAuth 共用的连接池设置。
 * 闲置超过 {@link #VALIDATE_AFTER_INACTIVITY} 的连接在借出前先确认还活着。
 * 这个间隔是固定的，不进 config.yml。
 */
public final class OutboundHttp {

    public static final Duration DEFAULT_CONNECT = Duration.ofSeconds(15);
    /** MCP、OAuth，以及没有改到的 java.net.http 客户端，连接超时仍是 10 秒。 */
    public static final Duration OTHER_CONNECT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_BODY_IDLE = Duration.ofSeconds(5);
    /** 规则引擎连接上的读超时兜底。单次请求仍用 responseTimeout 覆盖。 */
    public static final Duration ENGINE_SOCKET = Duration.ofSeconds(15);
    public static final TimeValue VALIDATE_AFTER_INACTIVITY = TimeValue.ofSeconds(2);

    /** 账号连接池里的连接闲置超过这个时长就关掉，每分钟检查一次。固定值，不进 config.yml。 */
    public static final Duration IDLE_CONNECTION_LIMIT = Duration.ofMinutes(1);

    public static final String BODY_ENDED_BY_IDLE = "body_ended_by_idle";
    public static final String BODY_ENDED_BY_IDLE_MESSAGE =
            "响应保持连接，但没有 Content-Length 或分块编码。空闲超时后已把读到的内容当作正文。";

    private OutboundHttp() {
    }

    public static PoolingHttpClientConnectionManager connectionManager(
            OutboundTls tls,
            HttpMessageLimits limits,
            Duration connectTimeout,
            Duration socketTimeout,
            DnsResolver dns,
            int maxPerRoute,
            int maxTotal
    ) {
        Objects.requireNonNull(tls, "tls");
        HttpMessageLimits resolved = limits == null ? HttpMessageLimits.defaults() : limits;
        Duration connect = connectTimeout == null ? DEFAULT_CONNECT : connectTimeout;
        PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tls.tlsStrategy())
                .setConnectionFactory(resolved.connectionFactory())
                .setDefaultConnectionConfig(connectionConfig(connect, socketTimeout));
        if (dns != null) {
            builder.setDnsResolver(dns);
        }
        if (maxPerRoute > 0) {
            builder.setMaxConnPerRoute(maxPerRoute);
        }
        if (maxTotal > 0) {
            builder.setMaxConnTotal(maxTotal);
        }
        return tls.track(builder.build());
    }

    static ConnectionConfig connectionConfig(Duration connectTimeout, Duration socketTimeout) {
        ConnectionConfig.Builder builder = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1, connectTimeout.toMillis())))
                .setValidateAfterInactivity(VALIDATE_AFTER_INACTIVITY);
        if (socketTimeout != null && !socketTimeout.isZero() && !socketTimeout.isNegative()) {
            builder.setSocketTimeout(Timeout.ofMilliseconds(Math.max(1, socketTimeout.toMillis())));
        }
        return builder.build();
    }
}
