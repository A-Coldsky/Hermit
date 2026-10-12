package dev.hermit.crab.mcp;

import java.io.IOException;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class McpJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private McpJson() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("json encoding failed");
        }
    }

    static JsonNode tree(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("invalid json");
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("invalid json");
            }
            return node;
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid json");
        }
    }

    static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.convertValue(node == null || node.isNull() ? MAPPER.createObjectNode() : node, type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid params");
        }
    }
}
