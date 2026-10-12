package dev.hermit.crab.report;

import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

final class FailureCodes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Long>> MAP = new TypeReference<>() {
    };

    private FailureCodes() {
    }

    static String write(Map<String, Long> counts) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(counts));
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("failure codes could not be encoded");
        }
    }

    static Map<String, Long> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Long> parsed = JSON.readValue(json, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            return Map.of();
        }
    }

    static long total(Map<String, Long> counts) {
        long sum = 0;
        for (Long value : counts.values()) {
            if (value != null && value > 0) {
                sum += value;
            }
        }
        return sum;
    }
}
