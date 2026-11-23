package dev.hermit.common.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;
import dev.hermit.common.version.SemVer;

/**
 * 1.12.0 自定义会话失效判定（{@code auth.expired_when}、{@code auth.alive_check.expect}）
 * 与 {@code assert} 扩展（{@code on_fail: session_expired}、变量断言键）的校验与版本门禁。
 */
public final class SessionExpiryChecks {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.12.0");
    public static final int MAX_EXPIRED_RULES = 16;
    public static final String ON_FAIL_SESSION_EXPIRED = "session_expired";

    /** {@code auth.expired_when} 单条规则支持的判断键。 */
    public static final List<String> EXPIRED_KEYS = List.of(
            "status", "contains", "not_contains", "regex",
            "header_exists", "header_contains",
            "json_equals", "json_not_equals", "json_exists");

    /** {@code auth.alive_check.expect} 支持的判断键。 */
    public static final List<String> ALIVE_EXPECT_KEYS = List.of(
            "status", "contains", "not_contains", "regex",
            "header_exists", "header_contains", "cookie_exists",
            "json_equals", "json_not_equals", "json_exists");

    /** 1.12.0 在 {@code assert} 里新增的键。 */
    public static final Set<String> ASSERT_112_KEYS = Set.of(
            "on_fail", "equals", "not_equals", "present", "not_present");

    /** {@code assert} 有效的条件键（不含修饰键 {@code on_fail}）。 */
    public static final Set<String> ASSERT_CONDITION_KEYS = Set.of(
            "status", "contains", "not_contains", "cookie_exists", "header_exists", "not_redirected_to",
            "equals", "not_equals", "present", "not_present");

    private SessionExpiryChecks() {
    }

    /** 用了 1.12.0 特性时，{@code min_crab_version} 必须不低于 1.12.0。 */
    static void version(ConchDoc doc, List<ValidationIssue> errors) {
        if (doc.minCrabVersion().compareTo(MIN_CRAB_VERSION) >= 0) {
            return;
        }
        TreeSet<String> used = new TreeSet<>();
        Auth auth = doc.auth();
        if (auth != null) {
            if (auth.expiredWhenDeclared() || !auth.expiredWhen().isEmpty()) {
                used.add("auth.expired_when");
            }
            if (auth.aliveCheck() != null
                    && (auth.aliveCheck().expectDeclared() || !auth.aliveCheck().expect().isEmpty())) {
                used.add("auth.alive_check.expect");
            }
            collectAssertFeatures(auth.steps(), used);
        }
        for (Action action : doc.actions().values()) {
            if (action != null) {
                collectAssertFeatures(action.steps(), used);
            }
        }
        if (!used.isEmpty()) {
            errors.add(ConchValidator.issue(
                    ValidationCodes.MIN_CRAB_VERSION,
                    "min_crab_version",
                    "使用 " + String.join("、", used) + " 时 min_crab_version 必须不低于 1.12.0"));
        }
    }

    private static void collectAssertFeatures(List<Step> steps, Set<String> used) {
        for (Step step : FlowSteps.flat(steps)) {
            if (!"assert".equals(step.type()) || step.def() == null) {
                continue;
            }
            if (step.def().containsKey("on_fail")) {
                used.add("assert.on_fail");
            }
            for (String key : FlowSteps.VARIABLE_CONDITIONS) {
                if (step.def().containsKey(key)) {
                    used.add("assert." + key);
                }
            }
        }
    }

    /** 检查 {@code auth.expired_when} 与 {@code auth.alive_check.expect} 的写法。 */
    static void check(ConchDoc doc, List<ValidationIssue> errors) {
        Auth auth = doc.auth();
        if (auth == null) {
            return;
        }
        List<Map<String, Object>> rules = auth.expiredWhen();
        if (rules.size() > MAX_EXPIRED_RULES) {
            errors.add(expiry("auth.expired_when", "expired_when 最多写 " + MAX_EXPIRED_RULES + " 条规则"));
        }
        for (int i = 0; i < rules.size(); i++) {
            String path = "auth.expired_when[" + i + "]";
            Map<String, Object> rule = rules.get(i);
            if (rule == null || rule.isEmpty()) {
                errors.add(expiry(path, "expired_when 的每一项至少要写一个判断键，例如 json_equals: {\"$.code\": 401}"));
                continue;
            }
            FlowChecks.unknownKeys(path, "expired_when", rule.keySet(), EXPIRED_KEYS,
                    ValidationCodes.SESSION_EXPIRY, errors);
            checkResponseRule(path, rule, true, errors);
        }
        AliveCheck alive = auth.aliveCheck();
        if (alive != null && !alive.expect().isEmpty()) {
            String path = "auth.alive_check.expect";
            FlowChecks.unknownKeys(path, "alive_check.expect", alive.expect().keySet(), ALIVE_EXPECT_KEYS,
                    ValidationCodes.SESSION_EXPIRY, errors);
            checkResponseRule(path, alive.expect(), false, errors);
        }
    }

    /** 检查 {@code assert} 的 1.12.0 扩展键（{@code on_fail} 及变量断言）。 */
    static void checkAssertExtras(
            String path,
            Map<String, Object> spec,
            boolean inAuth,
            List<ValidationIssue> errors
    ) {
        if (spec == null || errors == null) {
            return;
        }
        if (spec.containsKey("on_fail")) {
            String child = path + ".on_fail";
            if (inAuth) {
                errors.add(expiry(child, "on_fail 只能写在能力步骤的 assert 里，不能写在 auth.steps"));
            }
            Object value = spec.get("on_fail");
            if (!(value instanceof String text) || !ON_FAIL_SESSION_EXPIRED.equals(text)) {
                errors.add(expiry(child, "on_fail 目前只支持 session_expired"));
            }
            boolean hasCondition = false;
            for (String key : spec.keySet()) {
                if (ASSERT_CONDITION_KEYS.contains(key)) {
                    hasCondition = true;
                    break;
                }
            }
            if (!hasCondition) {
                errors.add(expiry(path, "assert 写了 on_fail 时至少要写一个断言条件，例如 not_contains 或 not_equals"));
            }
        }
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            String key = entry.getKey();
            if (FlowSteps.VARIABLE_CONDITIONS.contains(key)) {
                FlowChecks.checkVariableCondition(
                        path + "." + key, key, entry.getValue(), ValidationCodes.SESSION_EXPIRY, errors);
            }
        }
    }

    private static void checkResponseRule(
            String path,
            Map<String, Object> rule,
            boolean expiredRule,
            List<ValidationIssue> errors
    ) {
        boolean hasNonStatusKey = false;
        List<Integer> parsedStatuses = null;
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String key = entry.getKey();
            String child = path + "." + key;
            Object value = entry.getValue();
            switch (key) {
                case "status" -> parsedStatuses = checkStatus(child, value, errors);
                case "contains", "not_contains", "header_exists", "cookie_exists" -> {
                    hasNonStatusKey = true;
                    if (!(value instanceof String text) || text.isEmpty()) {
                        errors.add(expiry(child, key + " 必须是非空字符串"));
                    }
                }
                case "regex" -> {
                    hasNonStatusKey = true;
                    if (!(value instanceof String text) || text.isEmpty()) {
                        errors.add(expiry(child, "regex 必须是非空字符串"));
                    } else {
                        try {
                            Pattern.compile(text);
                        } catch (PatternSyntaxException ex) {
                            errors.add(expiry(child, "regex 无法解析"));
                        }
                    }
                }
                case "header_contains" -> {
                    hasNonStatusKey = true;
                    checkHeaderContains(child, value, errors);
                }
                case "json_equals", "json_not_equals" -> {
                    hasNonStatusKey = true;
                    checkJsonEquals(child, key, value, errors);
                }
                case "json_exists" -> {
                    hasNonStatusKey = true;
                    checkJsonExists(child, value, errors);
                }
                default -> {
                    // 未知键已经由 unknownKeys 报出。
                }
            }
        }
        // expired_when 只写状态码且包含 2xx 时，会把所有正常 2xx 响应都误判成会话失效。
        if (expiredRule && !hasNonStatusKey && parsedStatuses != null && !parsedStatuses.isEmpty()
                && parsedStatuses.stream().anyMatch(code -> code >= 200 && code < 300)) {
            errors.add(expiry(path, "仅凭 2xx 状态码无法判断会话失效，请同时写 contains、regex、json_equals 或 header_* 条件"));
        }
    }

    private static List<Integer> checkStatus(String path, Object value, List<ValidationIssue> errors) {
        ArrayList<Integer> codes = new ArrayList<>();
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                errors.add(expiry(path, "status 必须是非空的状态码或状态码列表（100 到 499 的整数）"));
                return codes;
            }
            for (int i = 0; i < list.size(); i++) {
                Integer code = oneStatus(path + "[" + i + "]", list.get(i), errors);
                if (code != null) {
                    codes.add(code);
                }
            }
            return codes;
        }
        Integer code = oneStatus(path, value, errors);
        if (code != null) {
            codes.add(code);
        }
        return codes;
    }

    private static Integer oneStatus(String path, Object value, List<ValidationIssue> errors) {
        if (value instanceof String) {
            errors.add(expiry(path, "status 要写数字，不要加引号（100 到 499 的整数）"));
            return null;
        }
        if (!(value instanceof Integer || value instanceof Long)) {
            errors.add(expiry(path, "status 必须是 100 到 499 的整数"));
            return null;
        }
        long number = ((Number) value).longValue();
        if (number >= 500 && number <= 599) {
            errors.add(expiry(path, "status 不能写 5xx（5xx 始终按上游错误处理）"));
            return null;
        }
        if (number < 100 || number > 499) {
            errors.add(expiry(path, "status 必须是 100 到 499 的整数"));
            return null;
        }
        return (int) number;
    }

    private static void checkHeaderContains(String path, Object value, List<ValidationIssue> errors) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            errors.add(expiry(path, "header_contains 必须是「响应头名: 子串或子串列表」的非空映射，例如 header_contains: {content-type: \"text/html\"}"));
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String header = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            String child = path + "." + header;
            if (header.isEmpty()) {
                errors.add(expiry(path, "header_contains 的响应头名不能为空"));
                continue;
            }
            Object expected = entry.getValue();
            if (expected instanceof List<?> list) {
                if (list.isEmpty()) {
                    errors.add(expiry(child, "header_contains 的匹配列表不能为空"));
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof String text) || text.isEmpty()) {
                        errors.add(expiry(child + "[" + i + "]", "header_contains 的匹配项必须是非空字符串"));
                    }
                }
            } else if (!(expected instanceof String text) || text.isEmpty()) {
                errors.add(expiry(child, "header_contains 的值必须是非空字符串或非空字符串列表"));
            }
        }
    }

    private static void checkJsonEquals(String path, String key, Object value, List<ValidationIssue> errors) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            errors.add(expiry(path, key + " 必须是「JSONPath: 期望值」的非空映射，例如 "
                    + key + ": {\"$.code\": [401, 10001]}"));
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String expr = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            String child = path + "." + expr;
            if (!validJsonPathPrefix(expr)) {
                errors.add(expiry(child, key + " 的键必须是以 $ 开头的 JSONPath，例如 $.code"));
                continue;
            }
            Object expected = entry.getValue();
            if (expected instanceof List<?> list) {
                if (list.isEmpty()) {
                    errors.add(expiry(child, key + " 的期望值列表不能为空"));
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    if (!isJsonScalar(list.get(i))) {
                        errors.add(expiry(child + "[" + i + "]", key + " 的期望值必须是字符串、数字、布尔值或 null"));
                    }
                }
            } else if (!isJsonScalar(expected)) {
                errors.add(expiry(child, key + " 的期望值必须是字符串、数字、布尔值、null，或它们的列表"));
            }
        }
    }

    private static void checkJsonExists(String path, Object value, List<ValidationIssue> errors) {
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                errors.add(expiry(path, "json_exists 必须是以 $ 开头的 JSONPath 或非空列表"));
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof String text) || !validJsonPathPrefix(text.trim())) {
                    errors.add(expiry(path + "[" + i + "]", "json_exists 的每一项必须是以 $ 开头的 JSONPath，例如 $.data.user"));
                }
            }
            return;
        }
        if (!(value instanceof String text) || !validJsonPathPrefix(text.trim())) {
            errors.add(expiry(path, "json_exists 必须是以 $ 开头的 JSONPath（或多个组成的列表），例如 $.data.user"));
        }
    }

    private static boolean validJsonPathPrefix(String expr) {
        if (expr == null || expr.length() < 2 || expr.charAt(0) != '$') {
            return false;
        }
        char second = expr.charAt(1);
        return second == '.' || second == '[';
    }

    private static boolean isJsonScalar(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return true;
        }
        if (value instanceof Double d) {
            return Double.isFinite(d);
        }
        if (value instanceof Float f) {
            return Float.isFinite(f);
        }
        return value instanceof Number;
    }

    private static ValidationIssue expiry(String path, String message) {
        return ConchValidator.issue(ValidationCodes.SESSION_EXPIRY, path, message);
    }
}
