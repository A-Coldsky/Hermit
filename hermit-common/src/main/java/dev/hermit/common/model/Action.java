package dev.hermit.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * {@code related == null} 表示能力没有 related 键，或该键写成了 null。
 * 空列表表示显式 {@code related: []}，即没有关联能力。
 * {@code groupDeclared} 仅用于区分「没写 group」和「显式写了 group: null」以便检查版本门槛，
 * 不进规范 JSON，也不参与文档相等性。
 */
public record Action(
        String name,
        String title,
        String description,
        HttpMethod method,
        Map<String, ParamSpec> input,
        Map<String, Object> output,
        List<Step> steps,
        boolean idempotent,
        List<Example> examples,
        String notes,
        List<String> related,
        McpHints mcp,
        McpTool tool,
        String group,
        @JsonIgnore boolean groupDeclared
) {
    public Action {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("能力名为空");
        }
        input = input == null ? Map.of() : Frozen.ordered(input);
        output = output == null ? Map.of() : Frozen.map(output);
        steps = steps == null ? List.of() : List.copyOf(steps);
        examples = examples == null ? List.of() : List.copyOf(examples);
        related = related == null ? null : List.copyOf(related);
        mcp = mcp == null ? McpHints.defaults() : mcp;
        groupDeclared = groupDeclared || group != null;
    }

    public Action(
            String name,
            String title,
            String description,
            HttpMethod method,
            Map<String, ParamSpec> input,
            Map<String, Object> output,
            List<Step> steps,
            boolean idempotent,
            List<Example> examples,
            String notes,
            List<String> related,
            McpHints mcp,
            McpTool tool,
            String group
    ) {
        this(
                name, title, description, method, input, output, steps, idempotent,
                examples, notes, related, mcp, tool, group, group != null);
    }

    @JsonIgnore
    @Override
    public boolean groupDeclared() {
        return groupDeclared;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Action action)) {
            return false;
        }
        return idempotent == action.idempotent
                && Objects.equals(name, action.name)
                && Objects.equals(title, action.title)
                && Objects.equals(description, action.description)
                && method == action.method
                && Objects.equals(input, action.input)
                && Objects.equals(output, action.output)
                && Objects.equals(steps, action.steps)
                && Objects.equals(examples, action.examples)
                && Objects.equals(notes, action.notes)
                && Objects.equals(related, action.related)
                && Objects.equals(mcp, action.mcp)
                && Objects.equals(tool, action.tool)
                && Objects.equals(group, action.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                name, title, description, method, input, output, steps, idempotent,
                examples, notes, related, mcp, tool, group);
    }

    /** 显式 method 优先；否则 readOnly 或 idempotent 为 GET，其余 POST。MCP 工具固定 POST。 */
    public HttpMethod effectiveMethod() {
        if (tool != null) {
            return HttpMethod.POST;
        }
        if (method != null) {
            return method;
        }
        if (mcp.readOnly() || idempotent) {
            return HttpMethod.GET;
        }
        return HttpMethod.POST;
    }

    /**
     * MCP 包没写 {@code mcp.destructive} 时：上游注解有布尔 {@code destructiveHint} 就用它，否则非只读即为 true。
     * 写了就用声明值。HTTP 能力没有上游工具，只用声明值（缺省 false）。
     */
    public boolean effectiveDestructive() {
        if (tool == null || mcp.destructiveSpecified()) {
            return mcp.destructive();
        }
        Object hint = tool.upstreamAnnotations().get("destructiveHint");
        if (hint instanceof Boolean value) {
            return value;
        }
        return !mcp.readOnly();
    }

    /** 没写 {@code confirm} 时等于 {@link #effectiveDestructive()}。 */
    public boolean effectiveConfirm() {
        if (tool != null && tool.confirm() != null) {
            return tool.confirm();
        }
        return effectiveDestructive();
    }

    public Map<String, Object> inputJsonSchema() {
        if (tool != null) {
            return tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        }
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        ArrayList<String> required = new ArrayList<>();
        for (Map.Entry<String, ParamSpec> entry : input.entrySet()) {
            properties.put(entry.getKey(), entry.getValue().toSchemaProperty());
            if (entry.getValue().required()) {
                required.add(entry.getKey());
            }
        }
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return Collections.unmodifiableMap(schema);
    }

    public Map<String, Object> outputTemplate() {
        return OutputShapes.template(output);
    }

    public Map<String, Object> outputJsonSchema() {
        return OutputShapes.schema(output);
    }
}
