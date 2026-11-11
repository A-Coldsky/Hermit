package dev.hermit.common.template;

/**
 * One template path. {@code handledByDefault} is true when a {@code default} filter covers that path,
 * so a missing variable is not an undeclared-variable error. Filter arguments are never covered.
 */
public record TemplateRef(String path, boolean handledByDefault) {
}
