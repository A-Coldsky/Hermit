package dev.hermit.common.model;

import java.util.List;

public record ConfigField(
        String type,
        String label,
        boolean required,
        String description,
        List<String> options,
        Object defaultValue
) {
    public ConfigField {
        options = options == null ? List.of() : List.copyOf(options);
        defaultValue = Frozen.tree(defaultValue);
    }
}
