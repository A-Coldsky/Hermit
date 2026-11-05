package dev.hermit.crab.upstream;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 凭证库字段 {@code oauth_token} 里的访问令牌。明文只在调用和换票时出现。
 * 刷新令牌轮换后只保留新的；上游没发新的刷新令牌时沿用原来的。
 */
public record McpOAuthToken(String accessToken, String refreshToken, Instant expiresAt, String tokenType) {

    /** 不出现在规则包的 {@code credentials} 里，凭证表单也不能填。 */
    public static final String FIELD = "oauth_token";
    /** 源站要求客户端机密时，用这个已声明的凭证字段，值在库里，不进 YAML。 */
    public static final String CLIENT_SECRET = "client_secret";
    static final Duration REFRESH_SKEW = Duration.ofSeconds(60);
    private static final long MAX_EXPIRES_SECONDS = 366L * 24 * 3600 * 10;
    private static final int MAX_TOKEN_CHARS = 8192;
    private static final ObjectMapper JSON = new ObjectMapper();

    public boolean expiring(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now.plus(REFRESH_SKEW));
    }

    public String authorizationHeader() {
        return "Bearer " + accessToken;
    }

    public String json() {
        ObjectNode node = JSON.createObjectNode();
        node.put("access_token", accessToken);
        node.put("token_type", tokenType == null || tokenType.isBlank() ? "Bearer" : tokenType);
        if (refreshToken != null && !refreshToken.isBlank()) {
            node.put("refresh_token", refreshToken);
        }
        if (expiresAt != null) {
            node.put("expires_at", DateTimeFormatter.ISO_INSTANT.format(expiresAt));
        }
        return node.toString();
    }

    public static McpOAuthToken parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (JsonProcessingException ex) {
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        String access = text(node.get("access_token"));
        if (!usable(access)) {
            return null;
        }
        String refresh = text(node.get("refresh_token"));
        if (refresh != null && !usable(refresh)) {
            refresh = null;
        }
        Instant expires = null;
        JsonNode expiry = node.get("expires_at");
        if (expiry != null && !expiry.isNull()) {
            try {
                expires = Instant.parse(expiry.asText(""));
            } catch (DateTimeException ex) {
                expires = Instant.EPOCH;
            }
        }
        return new McpOAuthToken(access, refresh, expires, "Bearer");
    }

    /** 令牌端点的 JSON。没有新的 refresh_token 时保留 {@code previousRefresh}。 */
    static McpOAuthToken fromResponse(JsonNode node, Instant now, String previousRefresh) {
        if (node == null || !node.isObject() || node.hasNonNull("error")) {
            return null;
        }
        String access = text(node.get("access_token"));
        if (!usable(access)) {
            return null;
        }
        String type = text(node.get("token_type"));
        if (type != null && !"bearer".equalsIgnoreCase(type)) {
            return null;
        }
        String refresh = text(node.get("refresh_token"));
        if (refresh == null || refresh.isBlank()) {
            refresh = previousRefresh;
        } else if (!usable(refresh)) {
            return null;
        }
        Instant expires = null;
        JsonNode expiresIn = node.get("expires_in");
        if (expiresIn != null && expiresIn.isNumber()) {
            long seconds = expiresIn.asLong();
            if (seconds < 0) {
                seconds = 0;
            }
            if (seconds > MAX_EXPIRES_SECONDS) {
                seconds = MAX_EXPIRES_SECONDS;
            }
            expires = now.plusSeconds(seconds);
        }
        return new McpOAuthToken(access, refresh, expires, "Bearer");
    }

    private static boolean usable(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARS) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}
