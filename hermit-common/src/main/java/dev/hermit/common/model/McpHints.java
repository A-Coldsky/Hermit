package dev.hermit.common.model;

public record McpHints(boolean expose, boolean readOnly, boolean destructive) {

    public static McpHints defaults() {
        return new McpHints(true, false, false);
    }
}
