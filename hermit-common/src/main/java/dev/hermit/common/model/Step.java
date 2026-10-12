package dev.hermit.common.model;

import java.util.Map;

public record Step(String type, Map<String, Object> def) {
    public Step {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("步骤类型为空");
        }
        def = def == null ? Map.of() : Frozen.map(def);
    }
}
