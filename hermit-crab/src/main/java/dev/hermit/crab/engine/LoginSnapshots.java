package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Step;

/**
 * 登录成功后要整表替换的快照。只收登录步骤产出的字符串。
 * 数字、布尔、列表和对象不写入，也不让这次登录失败；调用方把它们记成开发追踪警告。
 */
final class LoginSnapshots {
    static final int MAX_BYTES = 32 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoginSnapshots() {
    }

    static Map<String, String> capture(List<Step> steps, Map<String, Object> vars) {
        return capture(steps, vars, null);
    }

    /**
     * {@code skipped} 收到最终值不是字符串的变量。同一个名字只留最后一次，字符串或 null 会把它去掉。
     * 不写入值。
     */
    static Map<String, String> capture(List<Step> steps, Map<String, Object> vars, List<Skipped> skipped) {
        LinkedHashMap<String, String> snapshot = new LinkedHashMap<>();
        if (steps == null || vars == null) {
            return snapshot;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if ("extract".equals(step.type()) || "set".equals(step.type())) {
                for (String name : step.def().keySet()) {
                    take(snapshot, name, vars.get(name), i, step.type(), skipped);
                }
                continue;
            }
            if ("script".equals(step.type()) && step.def().get("into") instanceof String name) {
                take(snapshot, name, vars.get(name), i, step.type(), skipped);
            }
        }
        return snapshot;
    }

    /** 开发追踪里的那一句。只含变量名。 */
    static String skippedMessage(String name) {
        return "登录变量 " + name + " 不是字符串，没有写入登录快照，恢复会话后取不到";
    }

    static void checkSize(Map<String, String> snapshot, int stepIndex) {
        checkSize(snapshot, stepIndex, JSON::writeValueAsBytes);
    }

    /** {@code writer} 抛出时对外只说快照无法保存，下标是当前登录步骤。 */
    static void checkSize(Map<String, String> snapshot, int stepIndex, SnapshotWriter writer) {
        byte[] bytes;
        try {
            bytes = writer.write(snapshot);
        } catch (JacksonException ex) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, "登录快照无法保存", stepIndex, "request", List.of());
        }
        if (bytes.length > MAX_BYTES) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, "登录变量超过 32KB", -1, "request", List.of());
        }
    }

    private static void take(
            Map<String, String> snapshot,
            String name,
            Object value,
            int index,
            String type,
            List<Skipped> skipped
    ) {
        if (name == null || ConchRegistry.RESERVED.contains(name)) {
            return;
        }
        if (value instanceof String text) {
            snapshot.put(name, text);
            clearSkip(skipped, name);
            return;
        }
        if (value == null) {
            snapshot.remove(name);
            clearSkip(skipped, name);
            return;
        }
        snapshot.remove(name);
        rememberSkip(skipped, index, type, name);
    }

    private static void clearSkip(List<Skipped> skipped, String name) {
        if (skipped == null) {
            return;
        }
        skipped.removeIf(item -> name.equals(item.name()));
    }

    private static void rememberSkip(List<Skipped> skipped, int index, String type, String name) {
        if (skipped == null) {
            return;
        }
        clearSkip(skipped, name);
        skipped.add(new Skipped(index, type, name));
    }

    /** 最终值不是字符串的登录变量。{@code index} 是产出它的登录步骤。 */
    record Skipped(int index, String type, String name) {
    }

    @FunctionalInterface
    interface SnapshotWriter {
        byte[] write(Map<String, String> snapshot) throws JacksonException;
    }
}
