package dev.hermit.crab.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OutputMask {

    private OutputMask() {
    }

    static Object exact(Object value, Map<String, String> credentials) {
        ArrayList<String> secrets = new ArrayList<>();
        if (credentials != null) {
            for (String secret : credentials.values()) {
                if (secret != null && secret.length() >= 4) {
                    secrets.add(secret);
                }
            }
        }
        return walk(value, secrets);
    }

    private static Object walk(Object value, Collection<String> secrets) {
        if (value instanceof String text) {
            for (String secret : secrets) {
                if (text.equals(secret)) {
                    return "***";
                }
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), walk(entry.getValue(), secrets));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(walk(item, secrets));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
