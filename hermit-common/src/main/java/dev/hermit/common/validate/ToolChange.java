package dev.hermit.common.validate;

/**
 * 两个已上传版本之间的 MCP 工具差异。{@code kind} 为 {@code added}、{@code removed} 或 {@code changed}。
 * 摘要是 {@code ToolSnapshots.schemaSha256}，不看 YAML 里声明的 {@code schema_sha256} 原文。
 * 是否敏感用 action 的有效值：没写 {@code destructive} 时看上游 {@code destructiveHint}，再没有则非只读为 true。
 */
public record ToolChange(
        String name,
        String kind,
        String previousUpstreamName,
        String upstreamName,
        String previousSchemaSha256,
        String schemaSha256,
        String previousDescription,
        String description,
        Boolean previousReadOnly,
        Boolean readOnly,
        Boolean previousDestructive,
        Boolean destructive
) {
    public static final String ADDED = "added";
    public static final String REMOVED = "removed";
    public static final String CHANGED = "changed";
}
