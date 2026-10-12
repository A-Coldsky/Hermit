package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.template.TemplateException;

final class Values {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Values() {
    }

    static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Map<?, ?> || value instanceof java.util.List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException ex) {
                throw new TemplateException(null, "值无法序列化为 JSON");
            }
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new RuleProblem(dev.hermit.common.error.ErrorCode.RULE_MISMATCH, "布尔值无效", null, null, null);
    }

    static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                throw new RuleProblem(
                        dev.hermit.common.error.ErrorCode.RULE_MISMATCH, "数字无效", null, null, null);
            }
        }
        throw new RuleProblem(dev.hermit.common.error.ErrorCode.RULE_MISMATCH, "数字无效", null, null, null);
    }

    static Integer integerOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return integer(value, 0);
    }

    static String upper(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = text(value).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return text.toUpperCase(Locale.ROOT);
    }
}
