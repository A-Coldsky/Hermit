package dev.hermit.reef.web;

public class ApiException extends RuntimeException {

    private final int status;
    private final ReefErrorCode code;

    public ApiException(int status, ReefErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public ReefErrorCode code() {
        return code;
    }

    public static ApiException invalidInput() {
        return new ApiException(400, ReefErrorCode.INVALID_INPUT, ReefErrorCode.INVALID_INPUT.message());
    }
}
