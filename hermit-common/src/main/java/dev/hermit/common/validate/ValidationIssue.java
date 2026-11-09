package dev.hermit.common.validate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One validation finding. {@code line} is the 1-based YAML line when that spot can be located.
 * Unknown lines stay null and are left out of JSON.
 */
public record ValidationIssue(
        String code,
        String path,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer line
) {
    public ValidationIssue {
        if (line != null && line < 1) {
            line = null;
        }
    }

    public ValidationIssue(String code, String path, String message) {
        this(code, path, message, null);
    }
}
