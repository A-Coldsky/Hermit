package dev.hermit.crab.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class ApiJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private ApiJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("json encoding failed");
        }
    }

    public static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid json");
        }
    }

    public static JsonNode tree(byte[] json) {
        if (json == null || json.length == 0) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node == null || node.isNull() ? MAPPER.createObjectNode() : node;
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid json");
        }
    }

    /** Blank input is an empty object. {@code null} and arrays are rejected. */
    public static Map<String, Object> objectMap(byte[] json) {
        return MAPPER.convertValue(objectNode(json), OBJECT_MAP);
    }

    public static JsonNode objectNode(byte[] json) {
        if (json == null || json.length == 0 || blank(json)) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("request body must be a json object");
            }
            return node;
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid json");
        }
    }

    private static boolean blank(byte[] json) {
        for (byte value : json) {
            if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
                return false;
            }
        }
        return true;
    }
}
