package dev.hermit.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * output 的两种写法：
 * 字符串值是映射写法；含 {@code type} 的映射是说明写法，取值模板在可选键 {@code value}，缺省为 {@code {{字段名}}}。
 * 说明写法里的 {@code items} 若自身含 {@code type}，视为单个 schema；否则视为元素对象的字段表。
 */
public final class OutputShapes {

    private static final List<String> PASSTHROUGH = List.of("enum", "minimum", "maximum", "default", "format");

    private OutputShapes() {
    }

    public static Map<String, Object> template(Map<String, Object> output) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            result.put(entry.getKey(), templateValue(entry.getKey(), entry.getValue()));
        }
        return freeze(result);
    }

    static Map<String, Object> schema(Map<String, Object> output) {
        return freeze(objectSchema(output));
    }

    private static Object templateValue(String fieldName, Object value) {
        if (value instanceof String) {
            return value;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = cast(raw);
            if (isDescription(map)) {
                Object explicit = map.get("value");
                if (explicit != null) {
                    return explicit;
                }
                return "{{" + fieldName + "}}";
            }
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                nested.put(entry.getKey(), templateValue(entry.getKey(), entry.getValue()));
            }
            return nested;
        }
        return value;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> fields) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            properties.put(entry.getKey(), schemaValue(entry.getValue()));
        }
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static Object schemaValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> map = cast(raw);
        if (!isDescription(map)) {
            return objectSchema(map);
        }
        return descriptionSchema(map);
    }

    private static Map<String, Object> descriptionSchema(Map<String, Object> map) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", map.get("type"));
        if (map.get("description") instanceof String description) {
            schema.put("description", description);
        }
        for (String key : PASSTHROUGH) {
            if (map.containsKey(key)) {
                schema.put(key, map.get(key));
            }
        }
        if (map.containsKey("items")) {
            schema.put("items", itemsSchema(map.get("items")));
        }
        return schema;
    }

    private static Object itemsSchema(Object items) {
        if (!(items instanceof Map<?, ?> raw)) {
            return items;
        }
        Map<String, Object> map = cast(raw);
        if (isDescription(map)) {
            return descriptionSchema(map);
        }
        return objectSchema(map);
    }

    static boolean isDescription(Map<String, Object> map) {
        return map.get("type") instanceof String;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(Map<String, Object> map) {
        return (Map<String, Object>) Frozen.tree(map);
    }
}
