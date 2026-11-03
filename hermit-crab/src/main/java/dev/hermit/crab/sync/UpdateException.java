package dev.hermit.crab.sync;

import java.util.List;

import dev.hermit.common.error.ErrorCode;

/**
 * A manual update was refused. {@link #status()} is the HTTP status and is not always
 * {@code StatusCodes.of(code())}: a version that is not higher is 409 with {@link ErrorCode#INVALID_INPUT}.
 */
public final class UpdateException extends RuntimeException {
    public static final String NOT_INSTALLED = "conch is not installed";
    public static final String NOT_HIGHER = "manifest version is not higher than the installed version";
    public static final String LOCAL_CONFLICT = "local conch cannot be updated from Reef";
    public static final String MISSING_PREFIX = "missing credentials for update: ";

    private final int status;
    private final ErrorCode code;
    private final List<String> missingCredentials;

    public UpdateException(int status, ErrorCode code, String message) {
        this(status, code, message, List.of());
    }

    public UpdateException(int status, ErrorCode code, String message, List<String> missingCredentials) {
        super(message);
        this.status = status;
        this.code = code;
        this.missingCredentials = missingCredentials == null ? List.of() : List.copyOf(missingCredentials);
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    /** Newly required names the default account does not have. Empty unless the update was staged. */
    public List<String> missingCredentials() {
        return missingCredentials;
    }
}
