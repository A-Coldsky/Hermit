package dev.hermit.common.validate;

/** 已有能力的所属分组发生变化。{@code before} 或 {@code after} 为 {@code null} 表示顶层未分组。 */
public record ActionGroupChange(
        String name,
        String before,
        String after
) {
}
