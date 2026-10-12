package dev.hermit.reef.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> apiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
                .body(ApiResponse.fail(ex.code(), ex.getMessage(), TraceIds.current(request)));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            RequestRejectedException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalidInput(Exception ex, HttpServletRequest request) {
        // 校验失败、坏 JSON、被防火墙拒绝的头，异常消息里可能有请求内容，不能写进日志或响应。
        return fail(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_INPUT, ErrorCodes.INVALID_INPUT_MESSAGE, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        return fail(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.INVALID_INPUT, ErrorCodes.INVALID_INPUT_MESSAGE, request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> notFound(Exception ex, HttpServletRequest request) {
        return fail(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ErrorCodes.NOT_FOUND_MESSAGE, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unhandled(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure traceId={}", TraceIds.current(request), ex);
        return fail(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL, ErrorCodes.INTERNAL_MESSAGE, request);
    }

    private static ResponseEntity<ApiResponse<Void>> fail(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.fail(code, message, TraceIds.current(request)));
    }
}
