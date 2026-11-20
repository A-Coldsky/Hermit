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
    public static final String FILE_TYPE = "file";
    /** 追加在文件入参的说明后面，Agent 从工具参数说明里读到。 */
    public static final String FILE_HINT =
            "文件参数：填 Crab 暂存区的 fileId（先把文件用 POST 传到 Crab 的 /api/-/files 取得，"
                    + "或用其它能力返回的 fileId）。不超过 256 KiB 的小文件也可以直接填 "
                    + "data:<类型>;name=<文件名>;base64,<内容>。";

    public boolean file() {
        return FILE_TYPE.equals(type);
    }

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
        // 文件入参在 JSON Schema 里是字符串：fileId 或 data URI。各家 MCP 客户端都认得字符串。
        boolean file = FILE_TYPE.equals(type);
        if (type != null) {
            schema.put("type", file ? "string" : type);
        }
        if (file) {
            schema.put("description", description == null || description.isBlank()
                    ? FILE_HINT
                    : description + "（" + FILE_HINT + "）");
        } else if (description != null) {
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
