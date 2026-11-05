package dev.hermit.common.model;

import java.util.Map;

/**
 * 一个 action 对应的上游工具。{@code inputSchema} 为 null 表示文档没写 {@code input_schema}。
 * {@code outputSchema} 为 null 表示没写 {@code output_schema}。{@code confirm} 为 null 表示沿用破坏性。
 */
public record McpTool(
        String name,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> upstreamAnnotations,
        String schemaSha256,
        Boolean confirm
) {
    public McpTool {
        inputSchema = inputSchema == null ? null : Frozen.map(inputSchema);
        outputSchema = outputSchema == null ? null : Frozen.map(outputSchema);
        upstreamAnnotations = upstreamAnnotations == null ? Map.of() : Frozen.map(upstreamAnnotations);
    }
}
