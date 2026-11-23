package dev.hermit.crab.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.CapabilityGroups;
import dev.hermit.common.model.GroupDef;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.Origin;

/**
 * OpenAPI 3.1 for enabled capabilities. Disabled capabilities and aliases that point at them
 * are omitted. When an {@link ApiKeyPrincipal} is supplied, only capabilities allowed by that
 * key ({@code allowsLocal} and {@code permits}) and their group tags are exposed. Aliases are
 * extra paths; their operation ids end in {@code __alias}.
 */
public final class OpenApiDocument {
    private OpenApiDocument() {
    }

    public static byte[] bytes(CrabRuntime runtime, int port) {
        return bytes(runtime, port, null);
    }

    public static byte[] bytes(CrabRuntime runtime, int port, ApiKeyPrincipal principal) {
        return ApiJson.bytes(ApiJson.write(document(runtime, port, principal)));
    }

    public static Map<String, Object> document(CrabRuntime runtime, int port) {
        return document(runtime, port, null);
    }

    public static Map<String, Object> document(CrabRuntime runtime, int port, ApiKeyPrincipal principal) {
        LinkedHashMap<String, Object> paths = new LinkedHashMap<>();
        LinkedHashSet<String> operationIds = new LinkedHashSet<>();
        LinkedHashMap<String, String> listedAliases = new LinkedHashMap<>();
        LinkedHashMap<String, String> declaredTags = new LinkedHashMap<>();
        for (String name : runtime.registry().snapshot().keySet()) {
            ConchRegistry.Active active = runtime.registry().active(name).orElse(null);
            if (active == null) {
                continue;
            }
            ConchDoc doc = active.doc();
            for (Action action : doc.actions().values()) {
                if (!visible(runtime, active, action, principal)) {
                    continue;
                }
                String operationId = doc.conch() + "__" + action.name();
                operationIds.add(operationId);
                String tag = groupTag(doc, action, declaredTags);
                paths.put("/api/" + doc.conch() + "/" + action.name(), Map.of(
                        CapabilityHttp.method(action).name().toLowerCase(),
                        operation(action, operationId, false, tag)));
            }
        }
        for (Map.Entry<String, String> alias : runtime.config().aliases().entrySet()) {
            int dot = alias.getValue().indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String conch = alias.getValue().substring(0, dot);
            String actionName = alias.getValue().substring(dot + 1);
            ConchRegistry.Active active = runtime.registry().active(conch).orElse(null);
            ConchDoc doc = active == null ? null : active.doc();
            Action action = doc == null ? null : doc.actions().get(actionName);
            if (!visible(runtime, active, action, principal)) {
                continue;
            }
            String operationId = unique(conch + "__" + actionName + "__alias", operationIds);
            String tag = groupTag(doc, action, declaredTags);
            paths.put(alias.getKey(), Map.of(
                    CapabilityHttp.method(action).name().toLowerCase(),
                    operation(action, operationId, true, tag)));
            listedAliases.put(alias.getKey(), alias.getValue());
        }
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.1.0");
        root.put("info", Map.of("title", "Hermit Crab", "version", "0.1.0"));
        root.put("servers", List.of(Map.of("url", serverUrl(runtime.config().serverHost(), port))));
        if (!declaredTags.isEmpty()) {
            ArrayList<Map<String, Object>> tags = new ArrayList<>(declaredTags.size());
            for (Map.Entry<String, String> entry : declaredTags.entrySet()) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    item.put("description", entry.getValue());
                }
                tags.add(item);
            }
            root.put("tags", tags);
        }
        root.put("paths", paths);
        root.put("components", Map.of("securitySchemes", Map.of(
                "bearer", Map.of("type", "http", "scheme", "bearer"))));
        root.put("x-hermit-aliases", listedAliases);
        return root;
    }

    private static boolean visible(
            CrabRuntime runtime,
            ConchRegistry.Active active,
            Action action,
            ApiKeyPrincipal principal
    ) {
        if (active == null || action == null) {
            return false;
        }
        String conch = active.doc().conch();
        if (runtime.disabled(conch, action.name())) {
            return false;
        }
        if (principal == null) {
            return true;
        }
        if (active.origin() == Origin.LOCAL && !principal.scope().allowsLocal(conch)) {
            return false;
        }
        return principal.permits(conch, action.name(), action.group(), CapabilityHttp.readOnly(action));
    }

    private static String groupTag(ConchDoc doc, Action action, Map<String, String> declaredTags) {
        if (doc == null || action == null || action.group() == null || action.group().isBlank()) {
            return null;
        }
        String tag = CapabilityGroups.displayTitle(doc, action.group());
        if (!declaredTags.containsKey(tag)) {
            GroupDef def = CapabilityGroups.find(doc, action.group());
            declaredTags.put(tag, def == null ? null : def.description());
        }
        return tag;
    }

    private static Map<String, Object> operation(Action action, String operationId, boolean alias, String tag) {
        LinkedHashMap<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", operationId);
        operation.put("summary", action.title() == null ? "" : action.title());
        operation.put("description", description(action));
        if (tag != null && !tag.isBlank()) {
            operation.put("tags", List.of(tag));
        }
        operation.put("security", List.of(Map.of("bearer", List.of())));
        HttpMethod method = CapabilityHttp.method(action);
        operation.put("parameters", parameters(action, method));
        if (method == HttpMethod.POST) {
            operation.put("requestBody", requestBody(action.inputJsonSchema()));
        }
        Map<String, Object> dataSchema = action.tool() == null
                ? action.outputJsonSchema()
                : Map.of("type", "object");
        operation.put("responses", Map.of("200", Map.of(
                "description", "envelope",
                "content", Map.of("application/json", Map.of("schema", envelope(dataSchema))))));
        if (action.tool() != null) {
            operation.put("x-hermit-driver", "mcp");
        }
        if (alias) {
            operation.put("x-hermit-alias", true);
        }
        return operation;
    }

    private static String description(Action action) {
        String description = action.description() == null ? "" : action.description();
        if (action.notes() != null && !action.notes().isBlank()) {
            description = description + "\n\n" + action.notes();
        }
        return description;
    }

    private static List<Map<String, Object>> parameters(Action action, HttpMethod method) {
        ArrayList<Map<String, Object>> parameters = new ArrayList<>();
        parameters.add(parameter("X-Hermit-Account", "header", false, Map.of(
                "type", "string",
                "description", "可选。选择该规则包的命名账号，省略时使用默认账号。")));
        String selector = CrabHttpServer.accountQueryName(action, method);
        Map<String, Object> properties = method == HttpMethod.GET
                ? map(action.inputJsonSchema().get("properties"))
                : Map.of();
        if (!properties.containsKey(selector)) {
            parameters.add(accountQuery(selector));
        }
        if (method == HttpMethod.POST) {
            parameters.add(parameter("Idempotency-Key", "header", false, Map.of("type", "string", "maxLength", 128)));
        }
        if (action.effectiveConfirm()) {
            parameters.add(parameter("X-Hermit-Confirm", "header", true, Map.of(
                    "type", "string",
                    "enum", List.of("true"),
                    "description", "去空白后必须是 true，并与 X-Hermit-Confirm-Token 一起对应当前规则包。缺一或令牌不一致则拒绝，不会调用上游。")));
            parameters.add(parameter("X-Hermit-Confirm-Token", "header", true, Map.of(
                    "type", "string",
                    "pattern", "^[0-9a-f]{64}$",
                    "description", "当前规则包的快照令牌。未确认时由 409 响应头给出；与当前文档不一致则拒绝，不会调用上游。")));
        }
        if (method == HttpMethod.GET) {
            Set<String> required = stringSet(action.inputJsonSchema().get("required"));
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                parameters.add(parameter(property.getKey(), "query", required.contains(property.getKey()), property.getValue()));
            }
        }
        return List.copyOf(parameters);
    }

    private static Map<String, Object> accountQuery(String name) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", "可选。选择该规则包的命名账号。省略时使用默认账号。与请求头 X-Hermit-Account 相同则使用该账号，不同则拒绝。");
        schema.put("pattern", "^[A-Za-z0-9_-]{1,64}$");
        return parameter(name, "query", false, schema);
    }

    private static Map<String, Object> parameter(String name, String in, boolean required, Object schema) {
        LinkedHashMap<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("in", in);
        parameter.put("required", required);
        parameter.put("schema", schema);
        return parameter;
    }

    private static Map<String, Object> requestBody(Map<String, Object> schema) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("required", schema.containsKey("required"));
        body.put("content", Map.of("application/json", Map.of("schema", schema)));
        return body;
    }

    private static Map<String, Object> envelope(Map<String, Object> dataSchema) {
        LinkedHashMap<String, Object> error = new LinkedHashMap<>();
        error.put("type", List.of("object", "null"));
        error.put("properties", Map.of(
                "code", Map.of("type", "string"),
                "message", Map.of("type", "string")));
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("success", Map.of("type", "boolean"));
        properties.put("data", dataSchema);
        properties.put("error", error);
        properties.put("traceId", Map.of("type", "string"));
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static String unique(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static String serverUrl(String host, int port) {
        String shown = host != null && host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "http://" + shown + ":" + port;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof String text) {
                names.add(text);
            }
        }
        return names;
    }
}
