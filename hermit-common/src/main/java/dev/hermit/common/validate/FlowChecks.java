package dev.hermit.common.validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;
import dev.hermit.common.version.SemVer;

/**
 * {@code if}、{@code repeat} 和 request 的 {@code retry} 的写法检查。
 * 写规则的多半是模型：能在校验时发现的都在这里报错，报错里写出可用的键和最像的那个。
 */
final class FlowChecks {

    static final SemVer MIN_CRAB = SemVer.parse("1.9.0");

    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> RETRY_ON = List.of("timeout", "connect");
    /** 别的规则语言和编程语言里常见的叫法，对应到这里的键。 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("elif", "else"), Map.entry("otherwise", "else"), Map.entry("do", "then"),
            Map.entry("when", "if"), Map.entry("condition", "if"),
            Map.entry("while", "until"), Map.entry("stop_when", "until"), Map.entry("break_if", "until"),
            Map.entry("limit", "max"), Map.entry("max_pages", "max"), Map.entry("max_rounds", "max"),
            Map.entry("count", "max"), Map.entry("body", "steps"), Map.entry("each", "steps"),
            Map.entry("append", "collect"), Map.entry("accumulate", "collect"), Map.entry("into", "collect"),
            Map.entry("exists", "present"), Map.entry("defined", "present"), Map.entry("not_exists", "not_present"),
            Map.entry("empty", "not_present"), Map.entry("missing", "not_present"),
            Map.entry("eq", "equals"), Map.entry("equal", "equals"), Map.entry("is", "equals"),
            Map.entry("ne", "not_equals"), Map.entry("neq", "not_equals"), Map.entry("not_equal", "not_equals"),
            Map.entry("includes", "contains"), Map.entry("body_contains", "contains"),
            Map.entry("attempts", "times"), Map.entry("retries", "times"), Map.entry("max_attempts", "times"),
            Map.entry("delay", "delay_ms"), Map.entry("backoff", "delay_ms"), Map.entry("interval", "delay_ms"),
            Map.entry("wait_ms", "delay_ms"), Map.entry("statuses", "on"), Map.entry("status", "on"),
            Map.entry("codes", "on"));

    private FlowChecks() {
    }

    static boolean strict(ConchDoc doc) {
        return doc.minCrabVersion().compareTo(MIN_CRAB) >= 0;
    }

    /** 用了 if、repeat、retry 或 auth.concurrent_reads 的包，min_crab_version 不能低于 1.9.0。 */
    static void version(ConchDoc doc, List<ValidationIssue> errors) {
        if (strict(doc)) {
            return;
        }
        ArrayList<Step> all = new ArrayList<>();
        if (doc.auth() != null) {
            all.addAll(FlowSteps.flat(doc.auth().steps()));
        }
        for (Action action : doc.actions().values()) {
            if (action != null) {
                all.addAll(FlowSteps.flat(action.steps()));
            }
        }
        TreeSet<String> used = new TreeSet<>();
        if (doc.auth() != null && doc.auth().concurrentReads()) {
            used.add("auth.concurrent_reads");
        }
        for (Step step : all) {
            if (FlowSteps.isFlow(step)) {
                used.add(step.type());
            } else if ("request".equals(step.type()) && step.def().containsKey("retry")) {
                used.add("retry");
            }
        }
        if (!used.isEmpty()) {
            errors.add(ConchValidator.issue(
                    ValidationCodes.MIN_CRAB_VERSION,
                    "min_crab_version",
                    "使用 " + String.join("、", used) + " 时 min_crab_version 必须不低于 1.9.0"));
        }
    }

    static void shape(String path, Step step, ConchValidator.Nest nest, List<ValidationIssue> errors) {
        if (nest.depth() >= FlowSteps.MAX_DEPTH) {
            errors.add(flow(path, "if / repeat 最多嵌套 " + FlowSteps.MAX_DEPTH + " 层"));
        }
        if (FlowSteps.IF.equals(step.type())) {
            ifShape(path, step.def(), errors);
        } else {
            repeatShape(path, step.def(), nest, errors);
        }
    }

    private static void ifShape(String path, Map<String, Object> def, List<ValidationIssue> errors) {
        unknownKeys(path, "if", def.keySet(), FlowSteps.IF_KEYS, ValidationCodes.FLOW_STEP, errors);
        Map<String, Object> condition = new java.util.LinkedHashMap<>(def);
        condition.remove("then");
        condition.remove("else");
        condition.keySet().retainAll(FlowSteps.CONDITION_KEYS);
        if (condition.isEmpty()) {
            errors.add(flow(path, "if 缺少判断键。可以用的键：" + String.join("、", FlowSteps.CONDITION_KEYS)
                    + "。例如 contains: \"请再次确认\""));
        }
        condition(path, condition, errors);
        if (!def.containsKey("then")) {
            errors.add(flow(path + ".then", "if 缺少 then。then 是条件成立时执行的步骤列表，和判断键写在同一层"));
        } else {
            children(path + ".then", def.get("then"), errors);
        }
        if (def.containsKey("else")) {
            children(path + ".else", def.get("else"), errors);
        }
    }

    private static void repeatShape(
            String path, Map<String, Object> def, ConchValidator.Nest nest, List<ValidationIssue> errors) {
        if (nest.inRepeat()) {
            errors.add(flow(path, "repeat 里不能再写 repeat"));
        }
        unknownKeys(path, "repeat", def.keySet(), FlowSteps.REPEAT_KEYS, ValidationCodes.FLOW_STEP, errors);
        Object max = def.get("max");
        if (max == null) {
            errors.add(flow(path + ".max", "repeat 缺少 max。max 是最多执行的轮数，1 到 "
                    + FlowSteps.REPEAT_MAX + " 的整数"));
        } else if (!integer(max, 1, FlowSteps.REPEAT_MAX)) {
            errors.add(flow(path + ".max", "max 必须是 1 到 " + FlowSteps.REPEAT_MAX
                    + " 的整数，直接写数字，不能用模板"));
        }
        if (!def.containsKey("steps")) {
            errors.add(flow(path + ".steps", "repeat 缺少 steps。steps 是每一轮执行的步骤列表"));
        } else {
            children(path + ".steps", def.get("steps"), errors);
        }
        if (def.containsKey("collect")) {
            collect(path + ".collect", def.get("collect"), errors);
        }
        if (def.containsKey("until")) {
            Object until = def.get("until");
            if (!(until instanceof Map<?, ?> raw) || raw.isEmpty()) {
                errors.add(flow(path + ".until", "until 必须是判断键的映射，例如 until: {not_present: next}"));
            } else {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                raw.forEach((key, value) -> map.put(String.valueOf(key), value));
                unknownKeys(path + ".until", "until", map.keySet(), FlowSteps.CONDITION_KEYS,
                        ValidationCodes.FLOW_STEP, errors);
                condition(path + ".until", map, errors);
            }
        }
    }

    private static void collect(String path, Object value, List<ValidationIssue> errors) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            errors.add(flow(path, "collect 必须是「汇总后的变量名: 每一轮的列表变量名」的映射，"
                    + "例如 collect: {all_rows: rows}"));
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String target = String.valueOf(entry.getKey());
            String child = path + "." + target;
            if (!NAME.matcher(target).matches()) {
                errors.add(flow(child, "collect 的键是汇总后的变量名，只能用字母、数字和下划线"));
            } else if (ConchValidator.RESERVED_NAMES.contains(target)) {
                errors.add(ConchValidator.issue(ValidationCodes.RESERVED_VARIABLE, child, "不能写入保留变量 " + target));
            }
            if (!(entry.getValue() instanceof String source) || !VARIABLE.matcher(source).matches()) {
                errors.add(flow(child, "collect 的值是每一轮里的列表变量名，直接写名字，不要写 {{ }}"));
            }
        }
    }

    private static void condition(String path, Map<String, Object> condition, List<ValidationIssue> errors) {
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            String key = entry.getKey();
            String child = path + "." + key;
            Object value = entry.getValue();
            switch (key) {
                case "status" -> ConchValidator.checkStatusLiteral(child, value, errors);
                case "contains", "not_contains", "cookie_exists", "header_exists" -> {
                    if (!(value instanceof String text) || text.isEmpty()) {
                        errors.add(flow(child, key + " 必须是非空字符串"));
                    }
                }
                case "present", "not_present" -> variables(child, key, value, errors);
                case "equals", "not_equals" -> {
                    if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
                        errors.add(flow(child, key + " 必须是「变量名: 期望值」的映射，例如 "
                                + key + ": {state: \"done\"}"));
                        break;
                    }
                    for (Map.Entry<?, ?> pair : map.entrySet()) {
                        String name = String.valueOf(pair.getKey());
                        if (!VARIABLE.matcher(name).matches()) {
                            errors.add(flow(child + "." + name, key + " 的键是变量名，直接写名字，不要写 {{ }}"));
                        }
                        if (pair.getValue() instanceof Map<?, ?> || pair.getValue() instanceof List<?>) {
                            errors.add(flow(child + "." + name, key + " 的期望值必须是字符串、数字或布尔值"));
                        }
                    }
                }
                default -> {
                    // 未知的键已经报过。
                }
            }
        }
    }

    private static void variables(String path, String key, Object value, List<ValidationIssue> errors) {
        List<?> names = value instanceof List<?> list ? list : java.util.Collections.singletonList(value);
        if (names.isEmpty()) {
            errors.add(flow(path, key + " 要写变量名"));
        }
        for (Object name : names) {
            if (!(name instanceof String text) || !VARIABLE.matcher(text).matches()) {
                errors.add(flow(path, key + " 要直接写变量名（多个时写成列表），不要写 {{ }}，例如 "
                        + key + ": next_page"));
                return;
            }
        }
    }

    private static void children(String path, Object value, List<ValidationIssue> errors) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            errors.add(flow(path, "必须是非空的步骤列表，每一项和外层步骤写法相同，例如 - request: {method: GET, path: /x}"));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (FlowSteps.one(list.get(i)) == null) {
                errors.add(flow(path + "[" + i + "]",
                        "每个步骤必须是只有一个键的映射，例如 - request: {method: GET, path: /x}"));
            }
        }
    }

    static void retry(String path, Map<String, Object> request, boolean anyMethod, List<ValidationIssue> errors) {
        String child = path + ".retry";
        if (!(request.get("retry") instanceof Map<?, ?> raw)) {
            errors.add(retryIssue(child, "retry 必须是映射，例如 retry: {times: 2, delay_ms: 500}"));
            return;
        }
        Map<String, Object> retry = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> retry.put(String.valueOf(key), value));
        unknownKeys(child, "retry", retry.keySet(), FlowSteps.RETRY_KEYS, ValidationCodes.RETRY, errors);
        if (!integer(retry.get("times"), 1, FlowSteps.RETRY_TIMES_MAX)) {
            errors.add(retryIssue(child + ".times", "times 是失败后再试的次数，必须是 1 到 "
                    + FlowSteps.RETRY_TIMES_MAX + " 的整数"));
        }
        if (retry.containsKey("delay_ms") && !integer(retry.get("delay_ms"), 0, FlowSteps.RETRY_DELAY_MAX)) {
            errors.add(retryIssue(child + ".delay_ms", "delay_ms 必须是 0 到 " + FlowSteps.RETRY_DELAY_MAX + " 的整数"));
        }
        if (retry.containsKey("on")) {
            Object on = retry.get("on");
            if (!(on instanceof List<?> list) || list.isEmpty()) {
                errors.add(retryIssue(child + ".on", "on 必须是非空列表，例如 on: [timeout, connect, 502, 503, 504]"));
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    boolean ok = item instanceof String text
                            ? RETRY_ON.contains(text)
                            : integer(item, 500, 599);
                    if (!ok) {
                        errors.add(retryIssue(child + ".on[" + i + "]",
                                "on 里只能写 timeout、connect 或 500 到 599 的状态码（数字，不加引号）"));
                    }
                }
            }
        }
        Object method = request.get("method");
        String name = method == null ? "GET" : String.valueOf(method).trim().toUpperCase(Locale.ROOT);
        if (!anyMethod && !"GET".equals(name) && !"HEAD".equals(name)) {
            errors.add(retryIssue(child, "retry 只能用在 GET、HEAD 请求上。"
                    + "这个能力确实可以重复提交时，在能力上写 idempotent: true（登录步骤里不行）"));
        }
    }

    static void unknownKeys(
            String path,
            String what,
            Collection<String> keys,
            Collection<String> allowed,
            String code,
            List<ValidationIssue> errors
    ) {
        for (String key : keys) {
            if (!allowed.contains(key)) {
                errors.add(ConchValidator.issue(code, path + "." + key, unknownMessage(what + " 键", key, allowed)));
            }
        }
    }

    /** 「未知的 X foo。可以用的：…。是不是想写 bar？」 */
    static String unknownMessage(String what, String key, Collection<String> allowed) {
        StringBuilder text = new StringBuilder("未知的 ").append(what).append(' ').append(key)
                .append("。可以用的：").append(String.join("、", new TreeSet<>(allowed))).append('。');
        String near = nearest(key, allowed);
        if (near != null) {
            text.append("是不是想写 ").append(near).append("？");
        }
        return text.toString();
    }

    static String nearest(String key, Collection<String> allowed) {
        String lower = key.toLowerCase(Locale.ROOT).replace('-', '_');
        if (allowed.contains(lower)) {
            return lower;
        }
        String alias = ALIASES.get(lower);
        if (alias != null && allowed.contains(alias)) {
            return alias;
        }
        String best = null;
        int bestDistance = 3;
        for (String candidate : new TreeSet<>(allowed)) {
            int distance = distance(lower, candidate);
            if (distance < bestDistance && distance * 2 <= Math.max(lower.length(), candidate.length())) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    private static boolean integer(Object value, int min, int max) {
        if (!(value instanceof Integer || value instanceof Long)) {
            return false;
        }
        long number = ((Number) value).longValue();
        return number >= min && number <= max;
    }

    private static ValidationIssue flow(String path, String message) {
        return ConchValidator.issue(ValidationCodes.FLOW_STEP, path, message);
    }

    private static ValidationIssue retryIssue(String path, String message) {
        return ConchValidator.issue(ValidationCodes.RETRY, path, message);
    }
}
