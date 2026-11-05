package dev.hermit.common.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具列表和单个 schema 的摘要。对象键按字典序，去掉每一层的 {@code _meta}，数组保持原顺序。
 * {@code tools/list} 只取 {@code tools}，按 {@code name} 排序后再摘要，忽略 {@code nextCursor}。
 * 摘要是紧凑 JSON 的 UTF-8 SHA-256，小写十六进制。
 */
public final class ToolSnapshots {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolSnapshots() {
    }

    public static String toolsSha256(Object toolsList) {
        return sha256(canonicalJson(canonicalizeTools(toolsList)));
    }

    /**
     * {@code outputSchema} 为 null 时不写入。空映射会写入，和省略不是同一个摘要。
     */
    public static String schemaSha256(Object inputSchema, Object outputSchema) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("inputSchema", sortKeys(inputSchema == null ? Map.of() : inputSchema));
        if (outputSchema != null) {
            body.put("outputSchema", sortKeys(outputSchema));
        }
        return sha256(canonicalJson(body));
    }

    private static Object canonicalizeTools(Object toolsList) {
        ArrayList<Object> tools = new ArrayList<>();
        for (Object tool : toolArray(toolsList)) {
            tools.add(sortKeys(tool));
        }
        tools.sort(Comparator.comparing(ToolSnapshots::toolName));
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("tools", tools);
        return root;
    }

    private static List<?> toolArray(Object toolsList) {
        Object value = normalize(toolsList);
        if (value instanceof Map<?, ?> map && map.get("tools") instanceof List<?> tools) {
            return tools;
        }
        if (value instanceof List<?> tools) {
            return tools;
        }
        return List.of();
    }

    private static String toolName(Object tool) {
        if (tool instanceof Map<?, ?> map && map.get("name") instanceof String name) {
            return name;
        }
        return "";
    }

    private static Object sortKeys(Object value) {
        Object normalized = normalize(value);
        if (normalized instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("_meta".equals(key)) {
                    continue;
                }
                sorted.put(key, sortKeys(entry.getValue()));
            }
            return sorted;
        }
        if (normalized instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(sortKeys(item));
            }
            return copy;
        }
        return normalized;
    }

    private static Object normalize(Object value) {
        if (value instanceof JsonNode node) {
            return JSON.convertValue(node, Object.class);
        }
        return value;
    }

    private static byte[] canonicalJson(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("工具快照无法规范化", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
