package dev.hermit.common.validate;

public record ActionChange(
        String name,
        boolean input,
        boolean output,
        boolean steps,
        boolean method,
        boolean mcp
) {
}
