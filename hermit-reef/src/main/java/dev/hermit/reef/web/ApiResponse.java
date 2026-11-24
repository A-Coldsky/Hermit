package dev.hermit.reef.web;

public record ApiResponse<T>(boolean success, T data, ApiError error, String traceId) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, data, null, traceId);
    }

    public static <T> ApiResponse<T> fail(ReefErrorCode code, String message, String traceId) {
        return new ApiResponse<>(false, null, new ApiError(code, message), traceId);
    }

    public static <T> ApiResponse<T> fail(ReefErrorCode code, String message, T data, String traceId) {
        return new ApiResponse<>(false, data, new ApiError(code, message), traceId);
    }
}
