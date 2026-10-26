package dev.hermit.crab.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.ConchDoc;

public final class DeclaredSettings {
    private DeclaredSettings() {
    }

    public static List<String> missingConfig(ConchDoc doc, Map<String, String> stored) {
        ArrayList<String> missing = new ArrayList<>();
        for (Map.Entry<String, ConfigField> entry : doc.config().entrySet()) {
            if (entry.getValue().required() && choose(doc, entry.getKey(), entry.getValue(), stored) == null) {
                missing.add(entry.getKey());
            }
        }
        Collections.sort(missing);
        return List.copyOf(missing);
    }

    public static List<String> missingCredentials(ConchDoc doc, Set<String> present) {
        if (doc.credentials() == null) {
            return List.of();
        }
        ArrayList<String> missing = new ArrayList<>();
        for (Map.Entry<String, CredentialField> entry : doc.credentials().entrySet()) {
            if (entry.getValue().required() && (present == null || !present.contains(entry.getKey()))) {
                missing.add(entry.getKey());
            }
        }
        Collections.sort(missing);
        return List.copyOf(missing);
    }

    public static Map<String, String> mergedConfig(ConchDoc doc, Map<String, String> stored) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigField> entry : doc.config().entrySet()) {
            String chosen = choose(doc, entry.getKey(), entry.getValue(), stored);
            if (chosen != null) {
                values.put(entry.getKey(), chosen);
            }
        }
        return Map.copyOf(values);
    }

    public static void checkCredentialFields(ConchDoc doc, Map<String, String> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("credential fields are required");
        }
        if (doc.credentials() == null) {
            throw new IllegalArgumentException("conch declares no credentials");
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            CredentialField declared = doc.credentials().get(entry.getKey());
            if (declared == null) {
                throw new IllegalArgumentException("unknown credential field");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("credential value is required");
            }
            if (!entry.getValue().isEmpty()) {
                checkValue(declared.type(), declared.options(), entry.getKey());
                checkShape(declared.type(), declared.options(), entry.getValue(), entry.getKey());
            }
        }
    }

    public static void checkConfigValue(ConfigField field, String value) {
        if (field == null) {
            throw new IllegalArgumentException("unknown config field");
        }
        if (value == null) {
            throw new IllegalArgumentException("config value is required");
        }
        if (!value.isEmpty()) {
            checkShape(field.type(), field.options(), value, "config");
        }
    }

    private static void checkValue(String type, List<String> options, String field) {
        if ("select".equals(type) && (options == null || options.isEmpty())) {
            throw new IllegalArgumentException("invalid value for field " + field);
        }
    }

    private static void checkShape(String type, List<String> options, String value, String field) {
        if ("select".equals(type)) {
            if (options == null || !options.contains(value)) {
                throw new IllegalArgumentException("invalid value for field " + field);
            }
            return;
        }
        if ("url".equals(type) && !httpUrl(value)) {
            throw new IllegalArgumentException("invalid url for field " + field);
        }
    }

    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
                return false;
            }
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String choose(ConchDoc doc, String key, ConfigField field, Map<String, String> stored) {
        String user = stored == null ? null : stored.get(key);
        if (user != null && !user.isBlank()) {
            return user;
        }
        String fallback = scalar(field.defaultValue());
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        if ("base_url".equals(key) && doc.baseUrl() != null && !doc.baseUrl().isBlank()) {
            return doc.baseUrl();
        }
        return null;
    }

    private static String scalar(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return null;
    }
}
