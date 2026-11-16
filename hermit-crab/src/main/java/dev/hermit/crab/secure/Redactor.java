package dev.hermit.crab.secure;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Masks known secret values and sensitive field or header names. {@link #redact(String)}
 * also masks each secret's URL encoding in UTF-8, GBK, GB18030, and ISO-8859-1, with
 * spaces as {@code +} and as {@code %20}, and with uppercase or lowercase hex digits.
 * Callers that need both behaviors apply {@link #redact(String)} and
 * {@link #redactFields(Map)} themselves.
 */
public final class Redactor {
    static final String MASK = "***";
    private static final int MIN_SECRET_LENGTH = 4;
    private static final List<Charset> URL_CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            Charset.forName("GB18030"),
            StandardCharsets.ISO_8859_1);
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "otp",
            "apikey",
            "api_key",
            "cookie",
            "authorization",
            "set-cookie",
            "session",
            "jwt",
            "ticket");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "cookie",
            "set-cookie",
            "authorization",
            "x-api-key",
            "x-hermit-api-key");

    private final List<String> secretsLongestFirst;

    private Redactor(List<String> secretsLongestFirst) {
        this.secretsLongestFirst = secretsLongestFirst;
    }

    public static Redactor of(Set<String> secretValues) {
        Objects.requireNonNull(secretValues, "secretValues");
        if (secretValues.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("secret value must not be null");
        }
        Set<String> needles = new LinkedHashSet<>();
        for (String secret : secretValues) {
            if (secret.length() < MIN_SECRET_LENGTH) {
                continue;
            }
            needles.add(secret);
            for (Charset charset : URL_CHARSETS) {
                addUrlEncodings(needles, URLEncoder.encode(secret, charset));
            }
        }
        List<String> ordered = needles.stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        return new Redactor(ordered);
    }

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        // Longer secrets first so a shorter secret that is a substring cannot leave a suffix behind.
        String redacted = text;
        for (String secret : secretsLongestFirst) {
            redacted = redacted.replace(secret, MASK);
        }
        return redacted;
    }

    public Map<String, Object> redactFields(Map<String, Object> fields) {
        if (fields == null) {
            return null;
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (sensitiveKey(key)) {
                redacted.put(key, MASK);
            } else {
                redacted.put(key, redactValue(entry.getValue()));
            }
        }
        return redacted;
    }

    public Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name != null && SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                redacted.put(name, List.of(MASK));
            } else if (entry.getValue() == null) {
                redacted.put(name, List.of());
            } else {
                redacted.put(name, Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
        }
        return redacted;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("field name must not be null");
                }
                nested.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return redactFields(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            for (Object item : list) {
                nested.add(redactValue(item));
            }
            return Collections.unmodifiableList(nested);
        }
        return value;
    }

    private static boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if ("sid".equals(lower)) {
            return true;
        }
        for (String part : SENSITIVE_KEY_PARTS) {
            if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void addUrlEncodings(Set<String> needles, String encoded) {
        needles.add(encoded);
        needles.add(lowercasePercentHex(encoded));
        String percent20 = encoded.replace("+", "%20");
        needles.add(percent20);
        needles.add(lowercasePercentHex(percent20));
    }

    /** Lowercases hex digits in {@code %HH} sequences. Other characters keep their case. */
    private static String lowercasePercentHex(String encoded) {
        if (encoded.indexOf('%') < 0) {
            return encoded;
        }
        StringBuilder out = new StringBuilder(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()
                    && isHex(encoded.charAt(i + 1)) && isHex(encoded.charAt(i + 2))) {
                out.append('%');
                out.append(lowerHex(encoded.charAt(i + 1)));
                out.append(lowerHex(encoded.charAt(i + 2)));
                i += 3;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static char lowerHex(char c) {
        if (c >= 'A' && c <= 'F') {
            return (char) (c - 'A' + 'a');
        }
        return c;
    }
}
