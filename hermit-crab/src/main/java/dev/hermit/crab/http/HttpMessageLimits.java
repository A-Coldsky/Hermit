package dev.hermit.crab.http;

import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.core5.http.config.Http1Config;

/**
 * 三个 Apache 客户端共用的响应头上限。库默认是单行 8192 字节、100 个头。
 */
public record HttpMessageLimits(int maxLineBytes, int maxHeaderCount) {

    public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_HEADER_COUNT = 500;

    public HttpMessageLimits {
        if (maxLineBytes < 256 || maxLineBytes > 1_048_576) {
            throw new IllegalArgumentException("max header line bytes must be from 256 to 1048576");
        }
        if (maxHeaderCount < 1 || maxHeaderCount > 10_000) {
            throw new IllegalArgumentException("max header count must be from 1 to 10000");
        }
    }

    public static HttpMessageLimits defaults() {
        return new HttpMessageLimits(DEFAULT_MAX_LINE_BYTES, DEFAULT_MAX_HEADER_COUNT);
    }

    public ManagedHttpClientConnectionFactory connectionFactory() {
        return ManagedHttpClientConnectionFactory.builder()
                .http1Config(Http1Config.custom()
                        .setMaxLineLength(maxLineBytes)
                        .setMaxHeaderCount(maxHeaderCount)
                        .build())
                .build();
    }
}
