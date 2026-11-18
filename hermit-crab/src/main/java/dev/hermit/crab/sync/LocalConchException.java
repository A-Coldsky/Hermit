package dev.hermit.crab.sync;

import java.util.List;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.validate.ValidationIssue;

/**
 * A local conch was rejected before it changed the registry. {@code issues} is empty unless
 * parsing or validation failed. Messages do not include the YAML body.
 */
public final class LocalConchException extends RuntimeException {
    public static final String STALE = "规则包在等待确认期间被改过，请让 agent 重新保存。";
    public static final String PENDING_CHANGED = "待确认的内容已经变了，请刷新后再确认。";
    public static final String NO_PENDING = "没有待确认的覆盖。";
    public static final String NO_HISTORY = "没有这份历史。";
    private final int status;
    private final ErrorCode code;
    private final List<ValidationIssue> errors;
    private final List<ValidationIssue> warnings;
    /** Non-null when import must be repeated with confirmation. The name is the package that would be overwritten. */
    private final String replaceConch;
    /** Package name parsed from YAML before a later check failed. Null when parsing did not reach a safe name. */
    private final String parsedConch;

    private LocalConchException(
            int status,
            ErrorCode code,
            String message,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            String replaceConch,
            String parsedConch
    ) {
        super(message == null ? "" : message);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        this.replaceConch = replaceConch;
        this.parsedConch = parsedConch;
    }

    public static LocalConchException invalid(String message) {
        return new LocalConchException(400, ErrorCode.INVALID_INPUT, message, List.of(), List.of(), null, null);
    }

    public static LocalConchException invalid(String message, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        return invalid(message, errors, warnings, null);
    }

    public static LocalConchException invalid(
            String message,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            String parsedConch
    ) {
        return new LocalConchException(400, ErrorCode.INVALID_INPUT, message, errors, warnings, null, parsedConch);
    }

    /**
     * Official or subscription name clash. HTTP 409 with {@link ErrorCode#INVALID_INPUT}.
     * Overwrite confirmation uses {@link #replaceRequired}.
     */
    public static LocalConchException conflict(String message) {
        return new LocalConchException(409, ErrorCode.INVALID_INPUT, message, List.of(), List.of(), null, null);
    }

    /**
     * The target local package or its yaml file is already there. Nothing is written until the
     * caller passes confirmation.
     */
    public static LocalConchException replaceRequired(String conch) {
        return new LocalConchException(
                409,
                ErrorCode.CONFLICT,
                "将覆盖已有的本地规则包 " + conch + "。确认后再导入。",
                List.of(),
                List.of(),
                conch,
                conch);
    }

    public static LocalConchException notFound() {
        return new LocalConchException(404, ErrorCode.NOT_FOUND, "没有这个本地规则包。", List.of(), List.of(), null, null);
    }

    /** 确认时磁盘文件已经不是提交那一版。 */
    public static LocalConchException stale() {
        return new LocalConchException(409, ErrorCode.CONFLICT, STALE, List.of(), List.of(), null, null);
    }

    /** 控制台拿着的摘要对不上当前这条待确认覆盖。不丢掉那条新的。 */
    public static LocalConchException pendingChanged() {
        return new LocalConchException(409, ErrorCode.CONFLICT, PENDING_CHANGED, List.of(), List.of(), null, null);
    }

    public static LocalConchException pendingMissing() {
        return new LocalConchException(404, ErrorCode.NOT_FOUND, NO_PENDING, List.of(), List.of(), null, null);
    }

    public static LocalConchException historyMissing() {
        return new LocalConchException(404, ErrorCode.NOT_FOUND, NO_HISTORY, List.of(), List.of(), null, null);
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

    public String replaceConch() {
        return replaceConch;
    }

    public String parsedConch() {
        return parsedConch;
    }

    public boolean hasIssues() {
        return !errors.isEmpty() || !warnings.isEmpty();
    }
}
