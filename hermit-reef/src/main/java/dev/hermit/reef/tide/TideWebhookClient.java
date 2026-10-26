package dev.hermit.reef.tide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class TideWebhookClient {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    // Reef 没有管理端对外基址（Crab 的 reef.url 不会进入本进程）。负载里只放相对路径。
    static final String ADMIN_ALERTS_PATH = "/alerts";

    private static final Logger log = LoggerFactory.getLogger(TideWebhookClient.class);

    private final TideProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TideWebhookClient(TideProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void send(WebhookNotice notice) {
        String url = properties.webhookUrlOrNull();
        if (url == null) {
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(notice);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) {
                log.warn("tide webhook rejected kind={} conch={} status={}", notice.kind(), notice.conch(), status);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("tide webhook failed kind={} conch={} errorType={}", notice.kind(), notice.conch(),
                    ex.getClass().getName());
        } catch (Exception ex) {
            log.warn("tide webhook failed kind={} conch={} errorType={}", notice.kind(), notice.conch(),
                    ex.getClass().getName());
        }
    }

    public record WebhookNotice(
            long id,
            String kind,
            String conch,
            String version,
            JsonNode detail,
            Instant createdAt,
            String adminUrl) {
    }
}
