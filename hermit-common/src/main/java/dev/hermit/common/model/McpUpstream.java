package dev.hermit.common.model;

import java.util.List;
import java.util.Map;

/**
 * {@code driver: mcp} 的上游。省略的 {@code allowed_hosts} 由解析器填成 {@code url} 的主机。
 * 请求头的值可以是模板，主机不能。
 */
public record McpUpstream(
        String url,
        String transport,
        McpAuth auth,
        Map<String, String> headers,
        Integer timeoutMs,
        List<String> allowedHosts
) {
    public McpUpstream {
        headers = headers == null ? Map.of() : Frozen.ordered(headers);
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }
}
