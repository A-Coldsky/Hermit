package dev.hermit.common.model;

import java.util.List;

/** {@code upstream.auth}。{@code type} 为 {@code headers} 或 {@code oauth}。 */
public record McpAuth(
        String type,
        List<String> scopes,
        String clientId,
        String authorizationEndpoint,
        String tokenEndpoint
) {
    public McpAuth {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean headers() {
        return "headers".equals(type);
    }

    public boolean oauth() {
        return "oauth".equals(type);
    }
}
