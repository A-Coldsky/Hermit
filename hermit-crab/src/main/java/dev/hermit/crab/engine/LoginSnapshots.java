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
        for (Step step : steps) {
            if ("extract".equals(step.type()) || "set".equals(step.type())) {
                for (String name : step.def().keySet()) {
                    take(snapshot, name, vars.get(name));
                }
                continue;
            }
            if ("script".equals(step.type()) && step.def().get("into") instanceof String name) {
                take(snapshot, name, vars.get(name));
            }
        }
        return snapshot;
    }

    static void checkSize(Map<String, String> snapshot) {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(snapshot);
        } catch (JacksonException ex) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, "登录失败", -1, "request", List.of());
        }
        if (bytes.length > MAX_BYTES) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, "登录变量超过 32KB", -1, "request", List.of());
        }
    }

    private static void take(Map<String, String> snapshot, String name, Object value) {
        if (name == null || ConchRegistry.RESERVED.contains(name)) {
            return;
        }
        if (value instanceof String text) {
            snapshot.put(name, text);
        } else {
            snapshot.remove(name);
        }
    }
}
