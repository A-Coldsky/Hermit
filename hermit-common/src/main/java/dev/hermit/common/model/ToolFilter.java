package dev.hermit.common.model;

import java.util.List;

/**
 * 刷新工具列表时的过滤。不是运行时白名单；可调用的工具只有 {@code actions}。
 * 文档没有 {@code tools} 键时为 null。
 */
public record ToolFilter(List<String> include, List<String> exclude) {
    public ToolFilter {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }
}
