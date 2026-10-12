package dev.hermit.crab.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.Example;
import dev.hermit.common.model.ParamSpec;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.store.ApiKeyPrincipal;
import io.modelcontextprotocol.spec.McpSchema;

final class McpCatalog {
    static final int DIRECT_LIMIT = 40;
    static final String ACCOUNT_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    static final String LIST_SYSTEMS = "list_systems";
    static final String SEARCH = "search_capabilities";
    static final String DESCRIBE = "describe_capability";
    static final String CALL = "call_capability";
    private static final String GUIDE_PREFIX = "hermit://shells/";
    private static final String GUIDE_SUFFIX = "/guide";

    private final ShellRegistry registry;
    private final BiPredicate<String, String> disabled;

    McpCatalog(ShellRegistry registry, BiPredicate<String, String> disabled) {
        this.registry = registry;
        this.disabled = disabled;
    }

    boolean directory(ApiKeyPrincipal principal) {
        return visible(principal).size() > DIRECT_LIMIT;
    }

    List<McpSchema.Tool> tools(ApiKeyPrincipal principal) {
        if (directory(principal)) {
            return directoryTools();
        }
        ArrayList<McpSchema.Tool> tools = new ArrayList<>();
        for (CapabilityRef ref : visible(principal)) {
            tools.add(directTool(ref));
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
        for (ShellDoc shell : shells(principal)) {
            resources.add(McpSchema.Resource.builder(guideUri(shell.shell()), shell.shell())
                    .title(shell.title())
                    .description("由元数据生成的使用指南")
                    .mimeType("text/markdown")
                    .build());
        }
        return List.copyOf(resources);
    }

    Optional<String> guide(ApiKeyPrincipal principal, String uri) {
        String shellName = shellFromGuide(uri);
        if (shellName == null) {
            return Optional.empty();
        }
        ShellDoc shell = null;
        ArrayList<CapabilityRef> actions = new ArrayList<>();
        for (CapabilityRef ref : visible(principal)) {
            if (ref.shell().shell().equals(shellName)) {
                shell = ref.shell();
                actions.add(ref);
            }
        }
        if (shell == null) {
            return Optional.empty();
        }
        return Optional.of(markdown(shell, actions));
    }

    Map<String, Object> listSystems(ApiKeyPrincipal principal) {
        ArrayList<Map<String, Object>> systems = new ArrayList<>();
        for (ShellDoc shell : shells(principal)) {
            int count = 0;
            for (CapabilityRef ref : visible(principal)) {
                if (ref.shell().shell().equals(shell.shell())) {
                    count++;
                }
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("shell", shell.shell());
            row.put("title", shell.title());
            row.put("description", shell.description());
            row.put("capabilities", count);
            systems.add(row);
        }
        return Map.of("systems", systems);
    }

    Map<String, Object> search(ApiKeyPrincipal principal, String query, String shell) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        ArrayList<Map<String, Object>> matches = new ArrayList<>();
        if (!needle.isBlank()) {
            for (CapabilityRef ref : visible(principal)) {
                if (shell != null && !shell.isBlank() && !ref.shell().shell().equals(shell)) {
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
        body.put("description", action.description());
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

    private List<ShellDoc> shells(ApiKeyPrincipal principal) {
        LinkedHashMap<String, ShellDoc> shells = new LinkedHashMap<>();
        for (CapabilityRef ref : visible(principal)) {
            shells.putIfAbsent(ref.shell().shell(), ref.shell());
        }
        return List.copyOf(shells.values());
    }

    private List<CapabilityRef> visible(ApiKeyPrincipal principal) {
        ArrayList<CapabilityRef> found = new ArrayList<>();
        for (ShellDoc shell : registry.snapshot().values()) {
            for (Action action : shell.actions().values()) {
                if (!action.mcp().expose() || disabled.test(shell.shell(), action.name())) {
                    continue;
                }
                if (!principal.permits(shell.shell(), action.name(), CapabilityHttp.readOnly(action))) {
                    continue;
                }
                found.add(new CapabilityRef(shell, action));
            }
        }
        return found;
    }

    private static List<McpSchema.Tool> directoryTools() {
        return List.of(
                tool(LIST_SYSTEMS, "列出当前可以调用的 Shell 及其能力数量。", objectSchema(Map.of()), true, true),
                tool(SEARCH, "在标题、说明、备注和参数说明里搜索能力，最多返回 20 条。", searchSchema(), true, true),
                tool(DESCRIBE, "查看一个能力的说明、参数、返回字段和示例。name 形如 shell__action。", nameSchema(), true, true),
                tool(CALL, "调用一个能力。非只读能力需要用户确认。", callSchema(), false, false));
    }

    private static McpSchema.Tool directTool(CapabilityRef ref) {
        Action action = ref.action();
        boolean readOnly = CapabilityHttp.readOnly(action);
        return McpSchema.Tool.builder(ref.toolName(), withAccount(action))
                .title(action.title())
                .description(toolDescription(action))
                .annotations(hints(readOnly, action.idempotent()))
                .build();
    }

    private static McpSchema.Tool tool(String name, String description, Map<String, Object> schema, boolean readOnly, boolean idempotent) {
        return McpSchema.Tool.builder(name, schema)
                .description(description)
                .annotations(hints(readOnly, idempotent))
                .build();
    }

    private static McpSchema.ToolAnnotations hints(boolean readOnly, boolean idempotent) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(!readOnly && !idempotent)
                .idempotentHint(idempotent)
                .build();
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
                "可选。选择该 Shell 的命名账号，覆盖请求头 X-Hermit-Account。"));
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        Object required = action.inputJsonSchema().get("required");
        if (required instanceof List<?> names && !names.isEmpty()) {
            schema.put("required", List.copyOf(names));
        }
        return schema;
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
        properties.put("shell", Map.of("type", "string", "description", "可选。只搜索这个 Shell"));
        LinkedHashMap<String, Object> schema = objectSchema(properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    private static Map<String, Object> nameSchema() {
        LinkedHashMap<String, Object> schema = objectSchema(Map.of(
                "name", Map.of("type", "string", "description", "能力名，形如 shell__action")));
        schema.put("required", List.of("name"));
        return schema;
    }

    private static Map<String, Object> callSchema() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "能力名，形如 shell__action"));
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

    private static String markdown(ShellDoc shell, List<CapabilityRef> actions) {
        StringBuilder text = new StringBuilder();
        text.append("# ").append(blank(shell.title()) ? shell.shell() : shell.title()).append("\n\n");
        if (!blank(shell.description())) {
            text.append(shell.description()).append("\n\n");
        }
        text.append("Shell: `").append(shell.shell()).append("`\n\n");
        text.append("## 账号\n\n");
        text.append("用工具参数 `account`（若能力参数已占用该名字，则用 `_account`）或请求头 `X-Hermit-Account` 选择命名账号。");
        text.append("参数优先于请求头；都不传则使用该 Shell 的默认账号。账号名匹配 `")
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

    private static String shellFromGuide(String uri) {
        if (uri == null || !uri.startsWith(GUIDE_PREFIX) || !uri.endsWith(GUIDE_SUFFIX)) {
            return null;
        }
        String name = uri.substring(GUIDE_PREFIX.length(), uri.length() - GUIDE_SUFFIX.length());
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            return null;
        }
        return name;
    }

    static String guideUri(String shell) {
        return GUIDE_PREFIX + shell + GUIDE_SUFFIX;
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

    record CapabilityRef(ShellDoc shell, Action action) {
        String toolName() {
            return shell.shell() + "__" + action.name();
        }
    }
}
