package dev.hermit.crab.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.McpUpstream;
import dev.hermit.common.model.Step;
import dev.hermit.common.validate.ValidationIssue;

/**
 * Warnings for Cookie, password, and Bearer values written into a rule.
 * Templates and empty values are skipped. The message does not repeat the value.
 * These are warnings; the validator's plaintext errors stay errors.
 */
final class LiteralCredentialWarnings {
    static final String CODE = "LITERAL_CREDENTIAL";

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Set<String> ASSERT_NAME_KEYS = Set.of("cookie_exists", "header_exists");

    private LiteralCredentialWarnings() {
    }

    static List<ValidationIssue> scan(ConchDoc doc) {
        if (doc == null) {
            return List.of();
        }
        ArrayList<ValidationIssue> found = new ArrayList<>();
        Auth auth = doc.auth();
        if (auth != null) {
            scanSteps(auth.steps(), "auth.steps", found);
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            scanSteps(entry.getValue().steps(), "actions." + entry.getKey() + ".steps", found);
        }
        McpUpstream upstream = doc.upstream();
        if (upstream != null) {
            for (Map.Entry<String, String> header : upstream.headers().entrySet()) {
                consider("upstream.headers." + header.getKey(), header.getKey(), header.getValue(), false, found);
            }
        }
        return List.copyOf(found);
    }

    private static void scanSteps(List<Step> steps, String path, List<ValidationIssue> found) {
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String stepPath = path + "[" + i + "]." + step.type();
            boolean assertStep = "assert".equals(step.type());
            if ("extract".equals(step.type())) {
                for (Map.Entry<String, Object> entry : step.def().entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Map<?, ?> nested) {
                        walk(stepPath + "." + entry.getKey(), cast(nested), false, found);
                    } else if (value instanceof List<?> list) {
                        walkList(stepPath + "." + entry.getKey(), entry.getKey(), list, false, found);
                    }
                }
                continue;
            }
            walk(stepPath, step.def(), assertStep, found);
        }
    }

    private static void walk(String path, Map<String, Object> map, boolean assertStep, List<ValidationIssue> found) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String child = path + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                walk(child, cast(nested), false, found);
            } else if (value instanceof List<?> list) {
                walkList(child, entry.getKey(), list, assertStep, found);
            } else {
                consider(child, entry.getKey(), value, assertStep, found);
            }
        }
    }

    private static void walkList(
            String path,
            String parentKey,
            List<?> list,
            boolean assertStep,
            List<ValidationIssue> found
    ) {
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            String child = path + "[" + i + "]";
            if (value instanceof Map<?, ?> nested) {
                walk(child, cast(nested), false, found);
            } else if (value instanceof List<?> nested) {
                walkList(child, parentKey, nested, assertStep, found);
            } else {
                consider(child, parentKey, value, assertStep, found);
            }
        }
    }

    private static void consider(
            String path,
            String key,
            Object value,
            boolean assertStep,
            List<ValidationIssue> found
    ) {
        if (value == null || value instanceof Boolean || key == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || text.contains("{{")) {
            return;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (assertStep && ASSERT_NAME_KEYS.contains(lower)) {
            return;
        }
        String message = null;
        if (contains(lower, "password") || contains(lower, "passwd") || contains(lower, "pwd")) {
            message = "疑似把口令写进了 YAML。只声明凭证字段，到控制台填写真实值。";
        } else if (contains(lower, "cookie")) {
            message = "疑似把 Cookie 写进了 YAML。只声明凭证字段，到控制台填写真实值。";
        } else if (contains(lower, "authorization") || BEARER.matcher(text).find()) {
            message = "疑似把 Bearer 令牌写进了 YAML。只声明凭证字段，到控制台填写真实值。";
        }
        if (message != null) {
            found.add(new ValidationIssue(CODE, path, message));
        }
    }

    private static boolean contains(String lower, String needle) {
        return lower.contains(needle);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
