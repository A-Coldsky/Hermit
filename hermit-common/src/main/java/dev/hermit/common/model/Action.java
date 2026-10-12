package dev.hermit.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        McpHints mcp
) {
    public Action {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("能力名为空");
        }
        input = input == null ? Map.of() : Frozen.ordered(input);
        output = output == null ? Map.of() : Frozen.map(output);
        steps = steps == null ? List.of() : List.copyOf(steps);
        examples = examples == null ? List.of() : List.copyOf(examples);
        related = related == null ? List.of() : List.copyOf(related);
        mcp = mcp == null ? McpHints.defaults() : mcp;
    }

    /** 显式 method 优先；否则 readOnly 或 idempotent 为 GET，其余 POST。 */
    public HttpMethod effectiveMethod() {
        if (method != null) {
            return method;
        }
        if (mcp.readOnly() || idempotent) {
            return HttpMethod.GET;
        }
        return HttpMethod.POST;
    }

    public Map<String, Object> inputJsonSchema() {
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
