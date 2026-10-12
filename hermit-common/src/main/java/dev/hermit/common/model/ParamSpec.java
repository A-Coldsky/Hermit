package dev.hermit.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ParamSpec(
        String type,
        String description,
        Object defaultValue,
        List<Object> enumValues,
        Number minimum,
        Number maximum,
        Object items,
        boolean required
) {
    public ParamSpec {
        ArrayList<Object> enums = new ArrayList<>();
        if (enumValues != null) {
            for (Object value : enumValues) {
                enums.add(Frozen.tree(value));
            }
        }
        enumValues = Collections.unmodifiableList(enums);
        defaultValue = Frozen.tree(defaultValue);
        items = Frozen.tree(items);
    }

    Map<String, Object> toSchemaProperty() {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        if (type != null) {
            schema.put("type", type);
        }
        if (description != null) {
            schema.put("description", description);
        }
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        if (!enumValues.isEmpty()) {
            schema.put("enum", enumValues);
        }
        if (minimum != null) {
            schema.put("minimum", minimum);
        }
        if (maximum != null) {
            schema.put("maximum", maximum);
        }
        if (items != null) {
            schema.put("items", items);
        }
        return Collections.unmodifiableMap(schema);
    }
}
