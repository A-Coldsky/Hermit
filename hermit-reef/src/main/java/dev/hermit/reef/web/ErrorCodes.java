package dev.hermit.reef.web;

public final class ErrorCodes {

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String INTERNAL = "INTERNAL";

    public static final String UNAUTHORIZED_MESSAGE = "unauthorized";
    public static final String FORBIDDEN_MESSAGE = "forbidden";
    public static final String INVALID_INPUT_MESSAGE = "invalid input";
    public static final String NOT_FOUND_MESSAGE = "not found";
    public static final String INTERNAL_MESSAGE = "internal error";

    private ErrorCodes() {
    }
}
