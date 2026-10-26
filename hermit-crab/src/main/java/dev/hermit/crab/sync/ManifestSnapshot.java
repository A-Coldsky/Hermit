package dev.hermit.crab.sync;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON object {@code {conch: version}} stored beside the manifest ETag.
 * A document that is not that object is treated as missing so the next sync refetches.
 */
final class ManifestSnapshot {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ManifestSnapshot() {
    }

    static String encode(Map<String, String> versions) {
        try {
            return JSON.writeValueAsString(versions);
        } catch (JacksonException ex) {
            throw new IllegalStateException("manifest snapshot encoding failed");
        }
    }

    static Optional<Map<String, String>> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (JacksonException ex) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        Map<String, String> versions = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || !value.isTextual()) {
                return Optional.empty();
            }
            versions.put(field.getKey(), value.asText());
        }
        return Optional.of(versions);
    }
}
