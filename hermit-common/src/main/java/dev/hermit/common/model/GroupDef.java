package dev.hermit.common.model;

import java.util.Map;

/**
 * 能力分组定义。最多两层：顶层分组可通过 {@code children} 定义二级子分组；
 * 子分组的 {@code children} 为空映射（解析器已拒绝更深层级）。
 */
public record GroupDef(
        String name,
        String title,
        String description,
        Map<String, GroupDef> children
) {
    public GroupDef {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("分组名为空");
        }
        children = children == null ? Map.of() : Frozen.ordered(children);
    }
}
