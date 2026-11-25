package dev.hermit.crab.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON form of the last manifest stored in {@code sync_state}. A decoded {@code []} is present
 * and empty. Malformed text is absent so the next full fetch can replace it.
 */
final class CatalogCache {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CatalogCache() {
    }

    static String encode(List<ReefClient.CatalogEntry> entries) {
        ArrayNode array = JSON.createArrayNode();
        if (entries != null) {
            for (ReefClient.CatalogEntry entry : entries) {
                ObjectNode node = array.addObject();
                text(node, "conch", entry.conch());
                text(node, "version", entry.version());
                text(node, "sha256", entry.sha256());
                text(node, "minCrabVersion", entry.minCrabVersion());
                text(node, "title", entry.title());
                text(node, "description", entry.description());
                node.put("capabilityCount", entry.capabilityCount());
                node.put("driver", entry.driver());
                if (entry.authUse() != null) {
                    node.put("authUse", entry.authUse());
                }
                if (entry.requiredCredentialsKnown()) {
                    ArrayNode names = node.putArray("requiredCredentials");
                    for (String name : entry.requiredCredentials()) {
                        names.add(name);
                    }
                }
            }
        }
        return array.toString();
    }

    static Optional<List<ReefClient.CatalogEntry>> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isArray()) {
                return Optional.empty();
            }
            List<ReefClient.CatalogEntry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                if (node == null || !node.isObject()) {
                    return Optional.empty();
                }
                JsonNode credentials = node.get("requiredCredentials");
                boolean known = credentials != null && credentials.isArray();
                entries.add(new ReefClient.CatalogEntry(
                        textOrNull(node, "conch"),
                        textOrNull(node, "version"),
                        textOrNull(node, "sha256"),
                        textOrNull(node, "minCrabVersion"),
                        textOrNull(node, "title"),
                        textOrNull(node, "description"),
                        countOrZero(node),
                        known ? credentialNames(credentials) : List.of(),
                        known,
                        textOrNull(node, "driver"),
                        textOrNull(node, "authUse")));
            }
            return Optional.of(List.copyOf(entries));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static void text(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static List<String> credentialNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                names.add(item.asText());
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static int countOrZero(JsonNode node) {
        JsonNode value = node.get("capabilityCount");
        if (value == null || value.isNull() || !value.isIntegralNumber()) {
            return 0;
        }
        return value.intValue();
    }
}
