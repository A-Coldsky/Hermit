package dev.hermit.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Frozen {

    private Frozen() {
    }

    static Object tree(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), tree(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(tree(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> value) {
        return (Map<String, Object>) tree(value);
    }

    static <V> Map<String, V> ordered(Map<String, V> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    static List<Map<String, Object>> mapList(List<Map<String, Object>> list) {
        ArrayList<Map<String, Object>> copy = new ArrayList<>(list.size());
        for (Map<String, Object> item : list) {
            copy.add(map(item));
        }
        return Collections.unmodifiableList(copy);
    }
}
