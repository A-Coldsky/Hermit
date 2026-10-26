package dev.hermit.crab.store;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON for API-key scope and audit detail. Failures do not attach the Jackson cause: its
 * message can quote the document, and audit documents must not be echoed if a caller ever
 * puts a secret in a field name.
 */
final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("json encoding failed");
        }
    }

    static <T> T read(String document, Class<T> type) {
        try {
            return MAPPER.readValue(document, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("json decoding failed");
        }
    }

    /**
     * Object document, or null when the text is missing, not an object, or not JSON.
     * The failure is not rethrown: a Jackson message can quote the document.
     */
    static JsonNode object(String document) {
        if (document == null || document.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(document);
            if (node == null || !node.isObject()) {
                return null;
            }
            return node;
        } catch (JacksonException ex) {
            return null;
        }
    }
}
