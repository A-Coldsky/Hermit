package dev.hermit.crab.sync;

import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON object {@code {conch: reason}} of repair audits already written.
 * A document that is not that object is treated as empty so the next sync can record the audit again.
 */
final class RepairNotices {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RepairNotices() {
    }

    static String encode(Map<String, String> notices) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(notices));
        } catch (JacksonException ex) {
            throw new IllegalStateException("repair notice encoding failed");
        }
    }

    static Map<String, String> decode(String raw) {
        Map<String, String> notices = new TreeMap<>();
        if (raw == null || raw.isBlank()) {
            return notices;
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (JacksonException ex) {
            return notices;
        }
        if (node == null || !node.isObject()) {
            return notices;
        }
        node.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (value != null && value.isTextual() && field.getKey() != null && !field.getKey().isBlank()) {
                notices.put(field.getKey(), value.textValue());
            }
        });
        return notices;
    }
}
