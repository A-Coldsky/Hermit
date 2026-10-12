package dev.hermit.crab.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;

/**
 * OpenAPI 3.1 for enabled capabilities. Disabled capabilities and aliases that point at them
 * are omitted. Aliases are extra paths; their operation ids end in {@code __alias}.
 */
public final class OpenApiDocument {
    private OpenApiDocument() {
    }

    public static byte[] bytes(CrabRuntime runtime, int port) {
        return ApiJson.bytes(ApiJson.write(document(runtime, port)));
    }

    public static Map<String, Object> document(CrabRuntime runtime, int port) {
        LinkedHashMap<String, Object> paths = new LinkedHashMap<>();
        LinkedHashSet<String> operationIds = new LinkedHashSet<>();
        LinkedHashMap<String, String> listedAliases = new LinkedHashMap<>();
        for (ShellDoc doc : runtime.registry().snapshot().values()) {
            for (Action action : doc.actions().values()) {
                if (runtime.disabled(doc.shell(), action.name())) {
                    continue;
                }
                String operationId = doc.shell() + "__" + action.name();
                operationIds.add(operationId);
                paths.put("/api/" + doc.shell() + "/" + action.name(), Map.of(
                        CapabilityHttp.method(action).name().toLowerCase(),
                        operation(action, operationId, false)));
            }
        }
        for (Map.Entry<String, String> alias : runtime.config().aliases().entrySet()) {
            int dot = alias.getValue().indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String shell = alias.getValue().substring(0, dot);
            String actionName = alias.getValue().substring(dot + 1);
            ShellDoc doc = runtime.registry().find(shell).orElse(null);
            Action action = doc == null ? null : doc.actions().get(actionName);
            if (action == null || runtime.disabled(shell, actionName)) {
                continue;
            }
            String operationId = unique(shell + "__" + actionName + "__alias", operationIds);
            paths.put(alias.getKey(), Map.of(
                    CapabilityHttp.method(action).name().toLowerCase(),
                    operation(action, operationId, true)));
            listedAliases.put(alias.getKey(), alias.getValue());
        }
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.1.0");
        root.put("info", Map.of("title", "Hermit Crab", "version", "0.1.0"));
        root.put("servers", List.of(Map.of("url", serverUrl(runtime.config().serverHost(), port))));
        root.put("paths", paths);
        root.put("components", Map.of("securitySchemes", Map.of(
                "bearer", Map.of("type", "http", "scheme", "bearer"))));
        root.put("x-hermit-aliases", listedAliases);
        return root;
    }

    private static Map<String, Object> operation(Action action, String operationId, boolean alias) {
        LinkedHashMap<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", operationId);
        operation.put("summary", action.title() == null ? "" : action.title());
        operation.put("description", description(action));
        operation.put("security", List.of(Map.of("bearer", List.of())));
        HttpMethod method = CapabilityHttp.method(action);
        operation.put("parameters", parameters(action, method));
        if (method == HttpMethod.POST) {
            operation.put("requestBody", requestBody(action.inputJsonSchema()));
        }
        operation.put("responses", Map.of("200", Map.of(
                "description", "envelope",
                "content", Map.of("application/json", Map.of("schema", envelope(action.outputJsonSchema()))))));
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
        parameters.add(parameter("X-Hermit-Account", "header", false, Map.of("type", "string")));
        if (method == HttpMethod.POST) {
            parameters.add(parameter("Idempotency-Key", "header", false, Map.of("type", "string", "maxLength", 128)));
        }
        if (method == HttpMethod.GET) {
            Map<String, Object> schema = action.inputJsonSchema();
            Map<String, Object> properties = map(schema.get("properties"));
            Set<String> required = stringSet(schema.get("required"));
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                parameters.add(parameter(property.getKey(), "query", required.contains(property.getKey()), property.getValue()));
            }
        }
        return List.copyOf(parameters);
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
