package dev.hermit.crab.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hermit.crab.secure.Redactor;

/** 只替换值。Map 的键原样保留。规则与日志用的 {@link Redactor} 相同。 */
public final class OutputMask {

    private OutputMask() {
    }

    public static Object apply(Object value, Redactor redactor) {
        return walk(value, redactor);
    }

    private static Object walk(Object value, Redactor redactor) {
        if (value instanceof String text) {
            return redactor.redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), walk(entry.getValue(), redactor));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(walk(item, redactor));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
