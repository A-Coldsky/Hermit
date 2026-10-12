package dev.hermit.reef.shell;

import dev.hermit.reef.web.ReefErrorCode;

public class ShellRequestException extends RuntimeException {

    private final int status;
    private final ReefErrorCode code;
    private final Object data;

    public ShellRequestException(int status, ReefErrorCode code, String message, Object data) {
        super(message);
        this.status = status;
        this.code = code;
        this.data = data;
    }

    public int status() {
        return status;
    }

    public ReefErrorCode code() {
        return code;
    }

    public Object data() {
        return data;
    }

    public static ShellRequestException invalid(Object data) {
        return new ShellRequestException(422, ReefErrorCode.INVALID_INPUT, ReefErrorCode.INVALID_INPUT.message(), data);
    }
}
