package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ParamSpec;

public final class InputPreparer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private final JsonSchemaFactory draft2020 = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public Map<String, Object> prepare(Action action, Map<String, Object> rawInput) {
        if (action.tool() != null) {
            return prepareMcp(action, rawInput);
        }
        return prepareHttp(action, rawInput);
    }

    /** HTTP 入参。按 ParamSpec 转换字符串，schema 里已经带 {@code additionalProperties: false}。 */
    private Map<String, Object> prepareHttp(Action action, Map<String, Object> rawInput) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (rawInput != null) {
            values.putAll(rawInput);
        }
        for (Map.Entry<String, ParamSpec> entry : action.input().entrySet()) {
            if (!values.containsKey(entry.getKey())) {
                continue;
            }
            Object current = values.get(entry.getKey());
            if (current instanceof String text) {
                values.put(entry.getKey(), coerce(entry.getKey(), entry.getValue().type(), text));
            }
        }
        for (Map.Entry<String, ParamSpec> entry : action.input().entrySet()) {
            if (!values.containsKey(entry.getKey()) && entry.getValue().defaultValue() != null) {
                values.put(entry.getKey(), entry.getValue().defaultValue());
            }
        }
        JsonNode schemaNode = JSON.valueToTree(action.inputJsonSchema());
        JsonSchema schema = schemas.getSchema(schemaNode);
        JsonNode inputNode = JSON.valueToTree(values);
        Set<ValidationMessage> messages = schema.validate(inputNode);
        if (!messages.isEmpty()) {
            throw new InvalidInput("输入无效: " + paths(messages));
        }
        return values;
    }

    /**
     * 按包内原文校验。不补 {@code additionalProperties}，也不去加载 {@code $schema} 或远程 {@code $ref}。
     */
    private Map<String, Object> prepareMcp(Action action, Map<String, Object> rawInput) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (rawInput != null) {
            values.putAll(rawInput);
        }
        Map<String, Object> schema = action.inputJsonSchema();
        if (schema.isEmpty()) {
            return values;
        }
        JsonNode schemaNode = JSON.valueToTree(schema);
        rejectRemoteRefs(schemaNode);
        boolean draft202012 = false;
        if (schemaNode instanceof ObjectNode object) {
            JsonNode dialect = object.remove("$schema");
            draft202012 = dialect != null && dialect.asText("").contains("2020-12");
        }
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setPreloadJsonSchema(false);
        JsonSchema compiled = (draft202012 ? draft2020 : schemas).getSchema(schemaNode, config);
        Set<ValidationMessage> messages = compiled.validate(JSON.valueToTree(values));
        if (!messages.isEmpty()) {
            throw new InvalidInput("输入无效: " + paths(messages));
        }
        return values;
    }

    private static void rejectRemoteRefs(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String text = ref.asText("");
                if (!"#".equals(text) && !text.startsWith("#/")) {
                    throw new InvalidInput("远程 schema 引用被拒绝");
                }
            }
            node.properties().forEach(entry -> rejectRemoteRefs(entry.getValue()));
            return;
        }
        if (node.isArray()) {
            node.forEach(InputPreparer::rejectRemoteRefs);
        }
    }

    private static Object coerce(String field, String type, String text) {
        if (type == null) {
            return text;
        }
        String trimmed = text.trim();
        try {
            return switch (type) {
                case "integer" -> integer(trimmed);
                case "number" -> number(trimmed);
                case "boolean" -> bool(trimmed);
                default -> text;
            };
        } catch (IllegalArgumentException ex) {
            throw new InvalidInput("字段 " + field + " 无法转换为 " + type);
        }
    }

    private static Object integer(String text) {
        if (!text.matches("[+-]?\\d+")) {
            throw new IllegalArgumentException("integer");
        }
        long value = Long.parseLong(text);
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return (int) value;
        }
        return value;
    }

    private static Object number(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("number");
        }
        double value = Double.parseDouble(text);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("number");
        }
        return value;
    }

    private static Object bool(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("boolean");
    }

    private static String paths(Set<ValidationMessage> messages) {
        StringBuilder joined = new StringBuilder();
        for (ValidationMessage message : messages) {
            String path = fieldPath(message);
            if (path.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(path);
        }
        return joined.length() == 0 ? "输入" : joined.toString();
    }

    private static String fieldPath(ValidationMessage message) {
        String raw = String.valueOf(message.getInstanceLocation());
        if (raw.startsWith("$.")) {
            raw = raw.substring(2);
        } else if ("$".equals(raw) || raw.isBlank()) {
            raw = "";
        }
        raw = raw.replace("['", ".").replace("']", "");
        if (raw.isEmpty()) {
            String property = message.getProperty();
            if (property != null && !property.isBlank()) {
                return property;
            }
        }
        return raw;
    }
}
