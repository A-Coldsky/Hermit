package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Step;

/** 登录成功后要整表替换的快照。只收登录步骤产出的字符串。 */
final class LoginSnapshots {
    static final int MAX_BYTES = 32 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoginSnapshots() {
    }

    static Map<String, String> capture(List<Step> steps, Map<String, Object> vars) {
        LinkedHashMap<String, String> snapshot = new LinkedHashMap<>();
        if (steps == null || vars == null) {
            return snapshot;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if ("extract".equals(step.type()) || "set".equals(step.type())) {
                for (String name : step.def().keySet()) {
                    take(snapshot, name, vars.get(name), i, step.type());
                }
                continue;
            }
            if ("script".equals(step.type()) && step.def().get("into") instanceof String name) {
                take(snapshot, name, vars.get(name), i, step.type());
            }
        }
        return snapshot;
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

    private static void take(Map<String, String> snapshot, String name, Object value, int index, String type) {
        if (name == null || ConchRegistry.RESERVED.contains(name)) {
            return;
        }
        if (value instanceof String text) {
            snapshot.put(name, text);
            return;
        }
        if (value == null) {
            snapshot.remove(name);
            return;
        }
        throw new StepFailure(
                ErrorCode.AUTH_FAILED,
                "登录变量 " + name + " 不是字符串，没有写入登录快照",
                index,
                type,
                List.of());
    }

    @FunctionalInterface
    interface SnapshotWriter {
        byte[] write(Map<String, String> snapshot) throws JacksonException;
    }
}
