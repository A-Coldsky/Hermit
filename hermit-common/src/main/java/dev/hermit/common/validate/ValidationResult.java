package dev.hermit.common.validate;

import java.util.List;

public record ValidationResult(List<ValidationIssue> errors, List<ValidationIssue> warnings) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
