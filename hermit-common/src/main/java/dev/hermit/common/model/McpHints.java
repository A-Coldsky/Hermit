package dev.hermit.common.model;

import java.util.Objects;

/**
 * {@code destructiveSpecified} 区分「没写 destructive」和「写了 false」。
 * 相等性只看三个布尔值，所以 HTTP 包把缺省 false 写成显式 false 不会单独变成一条差异。
 * MCP 包没写时的有效破坏性在 {@link Action#effectiveDestructive()}。
 */
public record McpHints(boolean expose, boolean readOnly, boolean destructive, boolean destructiveSpecified) {

    public McpHints(boolean expose, boolean readOnly, boolean destructive) {
        this(expose, readOnly, destructive, true);
    }

    public static McpHints defaults() {
        return new McpHints(true, false, false, false);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof McpHints hints)) {
            return false;
        }
        return expose == hints.expose && readOnly == hints.readOnly && destructive == hints.destructive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(expose, readOnly, destructive);
    }
}
