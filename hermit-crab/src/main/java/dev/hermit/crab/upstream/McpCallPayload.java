package dev.hermit.crab.upstream;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次上游 {@code tools/call} 的结果。HTTP 入口用 {@link #httpData()}；
 * MCP 入口原样转发 {@code content} 和 {@code structuredContent}。
 */
public final class McpCallPayload {
    private final List<Object> content;
    private final Object structuredContent;
    private final boolean upstreamToolError;

    public McpCallPayload(List<Object> content, Object structuredContent, boolean upstreamToolError) {
        this.content = content == null ? List.of() : List.copyOf(content);
        this.structuredContent = structuredContent;
        this.upstreamToolError = upstreamToolError;
    }

    public List<Object> content() {
        return content;
    }

    public Object structuredContent() {
        return structuredContent;
    }

    public boolean upstreamToolError() {
        return upstreamToolError;
    }

    /** 成功时优先 structuredContent，否则 {@code {content: [...]}}。工具失败时 data 是脱敏后的 content。 */
    public Object httpData() {
        if (upstreamToolError) {
            return content;
        }
        if (structuredContent != null) {
            return structuredContent;
        }
        return Map.of("content", content);
    }

    public static Object httpDataOf(Object data) {
        if (data instanceof McpCallPayload payload) {
            return payload.httpData();
        }
        return data;
    }

    public Map<String, Object> copyStructured() {
        if (!(structuredContent instanceof Map<?, ?> map)) {
            return null;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }
}
