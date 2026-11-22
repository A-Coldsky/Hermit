package dev.hermit.common.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 带子步骤的两种步骤：{@code if}（then / else）和 {@code repeat}（steps）。
 * 子步骤在 {@link Step#def()} 里仍是 YAML 原样的列表，这里负责读成 {@link Step}。
 */
public final class FlowSteps {

    public static final String IF = "if";
    public static final String REPEAT = "repeat";

    /** 判断键。前五个看最近一次响应，和 assert 同名同义；后四个看变量。 */
    public static final Set<String> RESPONSE_CONDITIONS = Set.of(
            "status", "contains", "not_contains", "cookie_exists", "header_exists");
    public static final Set<String> VARIABLE_CONDITIONS = Set.of(
            "equals", "not_equals", "present", "not_present");
    public static final List<String> CONDITION_KEYS = List.of(
            "status", "contains", "not_contains", "cookie_exists", "header_exists",
            "equals", "not_equals", "present", "not_present");
    public static final List<String> IF_KEYS = List.of(
            "status", "contains", "not_contains", "cookie_exists", "header_exists",
            "equals", "not_equals", "present", "not_present", "then", "else");
    public static final List<String> REPEAT_KEYS = List.of("max", "steps", "collect", "until");
    public static final List<String> RETRY_KEYS = List.of("times", "delay_ms", "on");

    public static final int MAX_DEPTH = 3;
    public static final int REPEAT_MAX = 100;
    public static final int RETRY_TIMES_MAX = 3;
    public static final int RETRY_DELAY_MAX = 5000;
    /** repeat 每一轮里的轮次变量：{@code loop.index} 从 1 开始。 */
    public static final String LOOP = "loop";

    private FlowSteps() {
    }

    public static boolean isFlow(Step step) {
        return step != null && (IF.equals(step.type()) || REPEAT.equals(step.type()));
    }

    /** 子步骤所在的键，按书写顺序：if 是 then、else，repeat 是 steps。 */
    public static List<String> branchKeys(Step step) {
        if (IF.equals(step.type())) {
            return List.of("then", "else");
        }
        if (REPEAT.equals(step.type())) {
            return List.of("steps");
        }
        return List.of();
    }

    /** 一个分支里的子步骤。没写、不是列表或条目写错的，按能读出来的部分返回。 */
    public static List<Step> branch(Step step, String key) {
        return read(step.def().get(key));
    }

    /** if 的判断键，不含 then / else。 */
    public static Map<String, Object> condition(Step step) {
        LinkedHashMap<String, Object> condition = new LinkedHashMap<>(step.def());
        condition.remove("then");
        condition.remove("else");
        return condition;
    }

    /** 这一步自己的内容，不含子步骤。普通步骤原样返回。 */
    public static Map<String, Object> own(Step step) {
        if (!isFlow(step)) {
            return step.def();
        }
        LinkedHashMap<String, Object> own = new LinkedHashMap<>(step.def());
        for (String key : branchKeys(step)) {
            own.remove(key);
        }
        return own;
    }

    /** 把子步骤按书写顺序摊平。if / repeat 自己留在原位，只带自己的内容。 */
    public static List<Step> flat(List<Step> steps) {
        ArrayList<Step> flat = new ArrayList<>();
        flatten(steps, flat, 0);
        return flat;
    }

    private static void flatten(List<Step> steps, List<Step> out, int depth) {
        if (steps == null) {
            return;
        }
        for (Step step : steps) {
            if (step == null) {
                continue;
            }
            if (!isFlow(step)) {
                out.add(step);
                continue;
            }
            out.add(new Step(step.type(), own(step)));
            // 写得再深也有限：超过层数的由校验报错，这里只防止无限展开。
            if (depth >= MAX_DEPTH + 2) {
                continue;
            }
            for (String key : branchKeys(step)) {
                flatten(branch(step, key), out, depth + 1);
            }
        }
    }

    /** 一个条目是不是「只有一个键的映射」；是的话读成步骤，否则 null。 */
    public static Step one(Object item) {
        if (!(item instanceof Map<?, ?> map) || map.size() != 1) {
            return null;
        }
        Map.Entry<?, ?> entry = map.entrySet().iterator().next();
        if (!(entry.getKey() instanceof String type) || type.isEmpty()) {
            return null;
        }
        Object value = entry.getValue();
        if (value == null || "".equals(value)) {
            return new Step(type, Map.of());
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        LinkedHashMap<String, Object> def = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : raw.entrySet()) {
            def.put(String.valueOf(field.getKey()), field.getValue());
        }
        return new Step(type, def);
    }

    private static List<Step> read(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<Step> steps = new ArrayList<>(list.size());
        for (Object item : list) {
            Step step = one(item);
            if (step != null) {
                steps.add(step);
            }
        }
        return steps;
    }
}
