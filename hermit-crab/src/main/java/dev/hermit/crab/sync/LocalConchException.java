package dev.hermit.crab.sync;

import java.util.List;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.validate.ValidationIssue;

/**
 * A local conch was rejected before it changed the registry. {@code issues} is empty unless
 * parsing or validation failed. Messages do not include the YAML body.
 */
public final class LocalConchException extends RuntimeException {
    private final int status;
    private final ErrorCode code;
    private final List<ValidationIssue> errors;
    private final List<ValidationIssue> warnings;

    private LocalConchException(
            int status,
            ErrorCode code,
            String message,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        super(message == null ? "" : message);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static LocalConchException invalid(String message) {
        return new LocalConchException(400, ErrorCode.INVALID_INPUT, message, List.of(), List.of());
    }

    public static LocalConchException invalid(String message, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        return new LocalConchException(400, ErrorCode.INVALID_INPUT, message, errors, warnings);
    }

    /** No {@code CONFLICT} code exists. HTTP 409 with {@link ErrorCode#INVALID_INPUT}. */
    public static LocalConchException conflict(String message) {
        return new LocalConchException(409, ErrorCode.INVALID_INPUT, message, List.of(), List.of());
    }

    public static LocalConchException notFound() {
        return new LocalConchException(404, ErrorCode.NOT_FOUND, "not found", List.of(), List.of());
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public List<ValidationIssue> errors() {
        return errors;
    }

    public List<ValidationIssue> warnings() {
        return warnings;
    }

    public boolean hasIssues() {
        return !errors.isEmpty() || !warnings.isEmpty();
    }
}
