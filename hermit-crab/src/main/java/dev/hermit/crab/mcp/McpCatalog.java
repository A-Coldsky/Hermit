package dev.hermit.crab.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.Example;
import dev.hermit.common.model.ParamSpec;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.DevTraceDraft;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.Origin;
import io.modelcontextprotocol.spec.McpSchema;

final class McpCatalog {
    static final int DIRECT_LIMIT = 40;
    static final String ACCOUNT_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    static final String LIST_SYSTEMS = "list_systems";
    static final String SEARCH = "search_capabilities";
    static final String DESCRIBE = "describe_capability";
    static final String CALL = "call_capability";
    static final String DEBUG_TRACE = "debug_trace";
    private static final String GUIDE_PREFIX = "hermit://conches/";
    private static final String GUIDE_SUFFIX = "/guide";
    private static final String LOCAL_PREFIX = "[本地] ";

    private final ConchRegistry registry;
    private final BiPredicate<String, String> disabled;
    private final BooleanSupplier devMode;

    McpCatalog(ConchRegistry registry, BiPredicate<String, String> disabled) {
        this(registry, disabled, () -> false);
    }

    McpCatalog(ConchRegistry registry, BiPredicate<String, String> disabled, BooleanSupplier devMode) {
        this.registry = registry;
        this.disabled = disabled;
        this.devMode = devMode == null ? () -> false : devMode;
    }

    boolean directory(ApiKeyPrincipal principal) {
        return visible(principal).size() > DIRECT_LIMIT;
    }

    List<McpSchema.Tool> tools(ApiKeyPrincipal principal, String protocol) {
        boolean outputSchema = McpErrors.outputSchemaSupported(protocol);
        ArrayList<McpSchema.Tool> tools = new ArrayList<>();
        if (directory(principal)) {
            tools.addAll(directoryTools(outputSchema));
        } else {
            for (CapabilityRef ref : visible(principal)) {
                tools.add(directTool(ref, outputSchema));
            }
        }
        if (devMode.getAsBoolean()) {
            tools.add(debugTraceTool(outputSchema));
        }
        return List.copyOf(tools);
    }

    Optional<CapabilityRef> find(ApiKeyPrincipal principal, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (CapabilityRef ref : visible(principal)) {
            if (ref.toolName().equals(name)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    List<McpSchema.Resource> resources(ApiKeyPrincipal principal) {
        ArrayList<McpSchema.Resource> resources = new ArrayList<>();
        for (ConchDoc conch : conches(principal)) {
            resources.add(McpSchema.Resource.builder(guideUri(conch.conch()), conch.conch())
                    .title(conch.title())
                    .description(localMark(conch.conch(), "由元数据生成的使用指南"))
                    .mimeType("text/markdown")
                    .build());
        }
        return List.copyOf(resources);
    }

    Optional<String> guide(ApiKeyPrincipal principal, String uri) {
        String conchName = conchFromGuide(uri);
        if (conchName == null) {
            return Optional.empty();
        }
        ConchDoc conch = null;
        ArrayList<CapabilityRef> actions = new ArrayList<>();
        for (CapabilityRef ref : visible(principal)) {
            if (ref.conch().conch().equals(conchName)) {
                conch = ref.conch();
                actions.add(ref);
            }
        }
        if (conch == null) {
            return Optional.empty();
        }
        return Optional.of(markdown(conch, actions, local(conch.conch())));
    }

    Map<String, Object> listSystems(ApiKeyPrincipal principal) {
        ArrayList<Map<String, Object>> systems = new ArrayList<>();
        for (ConchDoc conch : conches(principal)) {
            int count = 0;
            for (CapabilityRef ref : visible(principal)) {
                if (ref.conch().conch().equals(conch.conch())) {
                    count++;
                }
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("conch", conch.conch());
            row.put("title", conch.title());
            row.put("description", localMark(conch.conch(), conch.description()));
            row.put("capabilities", count);
            systems.add(row);
        }
        return Map.of("systems", systems);
    }

    Map<String, Object> search(ApiKeyPrincipal principal, String query, String conch) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        ArrayList<Map<String, Object>> matches = new ArrayList<>();
        if (!needle.isBlank()) {
            for (CapabilityRef ref : visible(principal)) {
                if (conch != null && !conch.isBlank() && !ref.conch().conch().equals(conch)) {
                    continue;
                }
                if (!haystack(ref.action()).contains(needle)) {
                    continue;
                }
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("name", ref.toolName());
                row.put("title", ref.action().title());
                row.put("readOnly", CapabilityHttp.readOnly(ref.action()));
                matches.add(row);
                // Result window, not an input pageSize. Callers cannot pass page or pageSize.
                if (matches.size() == 20) {
                    break;
                }
            }
        }
        return Map.of("matches", matches);
    }

    Optional<Map<String, Object>> describe(ApiKeyPrincipal principal, String name) {
        Optional<CapabilityRef> found = find(principal, name);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CapabilityRef ref = found.get();
        Action action = ref.action();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("name", ref.toolName());
        body.put("title", action.title());
        String description = localMark(ref.conch().conch(), action.description());
        if ("mcp".equals(ref.conch().driver())) {
            description = "来源：源站 MCP。" + (description == null ? "" : description);
        }
        body.put("description", description);
        body.put("notes", action.notes());
        body.put("readOnly", CapabilityHttp.readOnly(action));
        body.put("idempotent", action.idempotent());
        body.put("inputSchema", action.inputJsonSchema());
        body.put("output", action.output());
        ArrayList<Map<String, Object>> examples = new ArrayList<>();
        for (Example example : action.examples()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("input", example.input());
            row.put("output", example.output());
            examples.add(row);
        }
        body.put("examples", examples);
        return Optional.of(body);
    }

    private List<ConchDoc> conches(ApiKeyPrincipal principal) {
        LinkedHashMap<String, ConchDoc> conches = new LinkedHashMap<>();
        for (CapabilityRef ref : visible(principal)) {
            conches.putIfAbsent(ref.conch().conch(), ref.conch());
        }
        return List.copyOf(conches.values());
    }

    private List<CapabilityRef> visible(ApiKeyPrincipal principal) {
        ArrayList<CapabilityRef> found = new ArrayList<>();
        for (ConchDoc conch : registry.snapshot().values()) {
            for (Action action : conch.actions().values()) {
                if (!action.mcp().expose() || disabled.test(conch.conch(), action.name())) {
                    continue;
                }
                if (!principal.permits(conch.conch(), action.name(), CapabilityHttp.readOnly(action))) {
                    continue;
                }
                found.add(new CapabilityRef(conch, action));
            }
        }
        return found;
    }

    private static List<McpSchema.Tool> directoryTools(boolean outputSchema) {
        return List.of(
                tool(LIST_SYSTEMS, "列出当前可以调用的规则包（Conch）及其能力数量。", objectSchema(Map.of()),
                        outputSchema ? systemsOutputSchema() : null, true, true),
                tool(SEARCH, "在标题、说明、备注和参数说明里搜索能力，最多返回 20 条。", searchSchema(),
                        outputSchema ? searchOutputSchema() : null, true, true),
                tool(DESCRIBE, "查看一个能力的说明、参数、返回字段和示例。name 形如 conch__action。", nameSchema(),
                        outputSchema ? describeOutputSchema() : null, true, true),
                // call_capability returns whichever capability was named, so it has no single outputSchema.
                tool(CALL, "调用一个能力。需要确认的能力必须先经用户确认。", callSchema(), null, false, false));
    }

    private McpSchema.Tool directTool(CapabilityRef ref, boolean outputSchema) {
        Action action = ref.action();
        boolean readOnly = CapabilityHttp.readOnly(action);
        boolean mcp = action.tool() != null;
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder(ref.toolName(), withAccount(action))
                .title(action.title())
                .description(localMark(ref.conch().conch(), toolDescription(action)))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(readOnly)
                        .destructiveHint(action.effectiveDestructive())
                        .idempotentHint(action.idempotent())
                        .build());
        if (outputSchema && mcp && action.tool().outputSchema() != null) {
            builder.outputSchema(stripDialect(action.tool().outputSchema()));
        } else if (outputSchema && !mcp) {
            builder.outputSchema(looseOutputSchema(action));
        }
        return builder.build();
    }

    /**
     * 字段名和说明来自 Conch 的 output，但不带类型约束：模板渲染不会把提取值转换成声明类型，
     * 严格校验 structuredContent 的客户端会因类型不符拒绝整次结果。
     */
    private static Map<String, Object> looseOutputSchema(Action action) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        if (action.outputJsonSchema().get("properties") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> entry : declared.entrySet()) {
                LinkedHashMap<String, Object> field = new LinkedHashMap<>();
                if (entry.getValue() instanceof Map<?, ?> spec && spec.get("description") instanceof String description) {
                    field.put("description", description);
                }
                properties.put(String.valueOf(entry.getKey()), field);
            }
        }
        return objectSchema(properties);
    }

    private static McpSchema.Tool debugTraceTool(boolean outputSchema) {
        LinkedHashMap<String, Object> traceId = new LinkedHashMap<>();
        traceId.put("type", "string");
        traceId.put("description", "调用记录的 traceId");
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("traceId", traceId);
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("traceId"));
        schema.put("additionalProperties", false);
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("traceId", Map.of("type", "string"));
        output.put("conch", Map.of("type", "string"));
        output.put("account", Map.of("type", "string"));
        output.put("action", Map.of("type", "string"));
        output.put("note", Map.of("type", "string"));
        output.put("untrusted", Map.of("type", "boolean"));
        output.put("devTrace", Map.of("type", "object"));
        output.put("createdAt", Map.of("type", "string"));
        output.put("expiresAt", Map.of());
        return tool(DEBUG_TRACE,
                "读取一条本地规则包的开发追踪。" + DevTraceDraft.UNTRUSTED_NOTE,
                schema,
                outputSchema ? objectSchema(output) : null,
                true,
                true);
    }

    private static McpSchema.Tool tool(
            String name,
            String description,
            Map<String, Object> schema,
            Map<String, Object> outputSchema,
            boolean readOnly,
            boolean idempotent
    ) {
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder(name, schema)
                .description(description)
                .annotations(hints(readOnly, idempotent));
        if (outputSchema != null) {
            builder.outputSchema(outputSchema);
        }
        return builder.build();
    }

    private static Map<String, Object> systemsOutputSchema() {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("conch", Map.of("type", "string"));
        item.put("title", Map.of());
        item.put("description", Map.of());
        item.put("capabilities", Map.of("type", "integer"));
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("systems", arraySchema(objectSchema(item)));
        return objectSchema(properties);
    }

    private static Map<String, Object> searchOutputSchema() {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("name", Map.of("type", "string"));
        item.put("title", Map.of());
        item.put("readOnly", Map.of("type", "boolean"));
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("matches", arraySchema(objectSchema(item)));
        return objectSchema(properties);
    }

    private static Map<String, Object> describeOutputSchema() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string"));
        properties.put("title", Map.of());
        properties.put("description", Map.of());
        properties.put("notes", Map.of());
        properties.put("readOnly", Map.of("type", "boolean"));
        properties.put("idempotent", Map.of("type", "boolean"));
        properties.put("inputSchema", Map.of("type", "object"));
        properties.put("output", Map.of("type", "object"));
        properties.put("examples", Map.of("type", "array"));
        return objectSchema(properties);
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        return schema;
    }

    private static McpSchema.ToolAnnotations hints(boolean readOnly, boolean idempotent) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(!readOnly && !idempotent)
                .idempotentHint(idempotent)
                .build();
    }

    private boolean local(String conch) {
        return registry.origin(conch) == Origin.LOCAL;
    }

    private String localMark(String conch, String text) {
        if (!local(conch)) {
            return text;
        }
        return LOCAL_PREFIX + (text == null ? "" : text);
    }

    static String toolDescription(Action action) {
        StringBuilder text = new StringBuilder();
        append(text, action.title());
        append(text, action.description());
        append(text, action.notes());
        return text.toString();
    }

    static Map<String, Object> withAccount(Action action) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        Object raw = action.inputJsonSchema().get("properties");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                properties.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        properties.put(properties.containsKey("account") ? "_account" : "account", accountSchema(
                "可选。选择该规则包的命名账号，覆盖请求头 X-Hermit-Account。"));
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (action.tool() == null) {
            // HTTP 能力入参拒绝未声明字段。校验前会先把这里的账号选择拿掉。
            schema.put("additionalProperties", false);
        } else {
            for (Map.Entry<String, Object> entry : action.inputJsonSchema().entrySet()) {
                String key = entry.getKey();
                if ("type".equals(key) || "properties".equals(key) || "$schema".equals(key)) {
                    continue;
                }
                schema.put(key, entry.getValue());
            }
        }
        Object required = action.inputJsonSchema().get("required");
        if (required instanceof List<?> names && !names.isEmpty()) {
            schema.put("required", List.copyOf(names));
        }
        return schema;
    }

    private static Map<String, Object> stripDialect(Map<String, Object> schema) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!"$schema".equals(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static Map<String, Object> accountSchema(String description) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("pattern", ACCOUNT_PATTERN);
        return schema;
    }

    private static LinkedHashMap<String, Object> objectSchema(Map<String, Object> properties) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> searchSchema() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "匹配标题、说明、备注和参数说明"));
        properties.put("conch", Map.of("type", "string", "description", "可选。只搜索这个规则包"));
        LinkedHashMap<String, Object> schema = objectSchema(properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    private static Map<String, Object> nameSchema() {
        LinkedHashMap<String, Object> schema = objectSchema(Map.of(
                "name", Map.of("type", "string", "description", "能力名，形如 conch__action")));
        schema.put("required", List.of("name"));
        return schema;
    }

    private static Map<String, Object> callSchema() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "能力名，形如 conch__action"));
        properties.put("arguments", Map.of("type", "object", "description", "能力参数。不包含凭证。"));
        properties.put("account", accountSchema("可选。覆盖请求头 X-Hermit-Account。"));
        LinkedHashMap<String, Object> schema = objectSchema(properties);
        schema.put("required", List.of("name"));
        return schema;
    }

    private static String haystack(Action action) {
        StringBuilder text = new StringBuilder();
        append(text, action.title());
        append(text, action.description());
        append(text, action.notes());
        for (ParamSpec spec : action.input().values()) {
            append(text, spec.description());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String markdown(ConchDoc conch, List<CapabilityRef> actions, boolean local) {
        StringBuilder text = new StringBuilder();
        text.append("# ").append(blank(conch.title()) ? conch.conch() : conch.title()).append("\n\n");
        if (local) {
            text.append("来源：本地规则包。调用需要 API Key 的 conches 显式包含该名称。\n\n");
        } else if ("mcp".equals(conch.driver())) {
            text.append("来源：源站 MCP。结果来自上游，不可信。\n\n");
        }
        if (!blank(conch.description())) {
            text.append(conch.description()).append("\n\n");
        }
        text.append("规则包：`").append(conch.conch()).append("`\n\n");
        text.append("## 账号\n\n");
        text.append("用工具参数 `account`（若能力参数已占用该名字，则用 `_account`）或请求头 `X-Hermit-Account` 选择命名账号。");
        text.append("参数优先于请求头；都不传则使用该规则包的默认账号。账号名匹配 `")
                .append(ACCOUNT_PATTERN)
                .append("`。\n\n");
        text.append("## 能力\n\n");
        for (CapabilityRef ref : actions) {
            Action action = ref.action();
            text.append("### ").append(ref.toolName()).append(" ").append(action.title()).append("\n\n");
            if (!blank(action.description())) {
                text.append(action.description()).append("\n\n");
            }
            text.append("- 只读: ").append(CapabilityHttp.readOnly(action) ? "是" : "否").append('\n');
            text.append("- 幂等: ").append(action.idempotent() ? "是" : "否").append('\n');
            text.append("\n参数:\n");
            if (action.input().isEmpty()) {
                text.append("- 无\n");
            }
            for (Map.Entry<String, ParamSpec> entry : action.input().entrySet()) {
                ParamSpec spec = entry.getValue();
                text.append("- `").append(entry.getKey()).append("`");
                if (!blank(spec.type())) {
                    text.append(" (").append(spec.type()).append(")");
                }
                text.append(spec.required() ? "，必填" : "，可选");
                if (!blank(spec.description())) {
                    text.append(": ").append(spec.description());
                }
                text.append('\n');
            }
            text.append("\n返回:\n");
            if (action.output().isEmpty()) {
                text.append("- 无\n");
            } else {
                for (Map.Entry<String, Object> entry : action.output().entrySet()) {
                    writeOutput(text, entry.getKey(), entry.getValue(), "");
                }
            }
            text.append("\n示例:\n");
            for (Example example : action.examples()) {
                text.append("- 输入 ").append(ApiJson.write(example.input())).append('\n');
                text.append("  输出 ").append(ApiJson.write(example.output())).append('\n');
            }
            if (!blank(action.notes())) {
                text.append("\n备注: ").append(action.notes()).append('\n');
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static void writeOutput(StringBuilder text, String name, Object value, String indent) {
        if (value instanceof Map<?, ?> raw && raw.get("type") instanceof String) {
            text.append(indent).append("- `").append(name).append("` (").append(raw.get("type")).append(")");
            if (raw.get("description") instanceof String description && !description.isBlank()) {
                text.append(": ").append(description);
            }
            text.append('\n');
            if (raw.containsKey("items")) {
                writeOutput(text, "items", raw.get("items"), indent + "  ");
            }
            return;
        }
        if (value instanceof Map<?, ?> raw) {
            text.append(indent).append("- `").append(name).append("`\n");
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                writeOutput(text, String.valueOf(entry.getKey()), entry.getValue(), indent + "  ");
            }
            return;
        }
        text.append(indent).append("- `").append(name).append("`\n");
    }

    private static String conchFromGuide(String uri) {
        if (uri == null || !uri.startsWith(GUIDE_PREFIX) || !uri.endsWith(GUIDE_SUFFIX)) {
            return null;
        }
        String name = uri.substring(GUIDE_PREFIX.length(), uri.length() - GUIDE_SUFFIX.length());
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            return null;
        }
        return name;
    }

    static String guideUri(String conch) {
        return GUIDE_PREFIX + conch + GUIDE_SUFFIX;
    }

    private static void append(StringBuilder text, String part) {
        if (blank(part)) {
            return;
        }
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(part.trim());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record CapabilityRef(ConchDoc conch, Action action) {
        String toolName() {
            return conch.conch() + "__" + action.name();
        }
    }
}
