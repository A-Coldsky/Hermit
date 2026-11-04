package dev.hermit.reef.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

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
        // 异常消息里可能有请求内容，不能写进日志或响应。只按类型给固定短句。
        return fail(HttpStatus.BAD_REQUEST, ReefErrorCode.INVALID_INPUT, invalidInputMessage(ex), request);
    }

    // 类型转换失败和缺参的异常文本里带有原始值，响应只写字段名，不能记成 500。
    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MethodArgumentConversionNotSupportedException.class,
            ServletRequestBindingException.class,
            BindException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalidParameter(Exception ex, HttpServletRequest request) {
        return fail(HttpStatus.BAD_REQUEST, ReefErrorCode.INVALID_INPUT, invalidParameterMessage(ex), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        return fail(HttpStatus.METHOD_NOT_ALLOWED, ReefErrorCode.INVALID_INPUT, ReefErrorCode.METHOD_NOT_ALLOWED,
                request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> notFound(Exception ex, HttpServletRequest request) {
        return fail(HttpStatus.NOT_FOUND, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unhandled(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure traceId={}", TraceIds.current(request), ex);
        return fail(HttpStatus.INTERNAL_SERVER_ERROR, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message(), request);
    }

    static String invalidInputMessage(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException) {
            return ReefErrorCode.VALIDATION_FAILED;
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return ReefErrorCode.UNREADABLE_BODY;
        }
        return ReefErrorCode.INVALID_INPUT.message();
    }

    static String invalidParameterMessage(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException) {
            return ReefErrorCode.VALIDATION_FAILED;
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return named(mismatch.getName());
        }
        if (ex instanceof MethodArgumentConversionNotSupportedException conversion) {
            return named(conversion.getName());
        }
        if (ex instanceof MissingServletRequestParameterException missing) {
            return named(missing.getParameterName());
        }
        if (ex instanceof MissingRequestHeaderException missing) {
            return named(missing.getHeaderName());
        }
        if (ex instanceof MissingPathVariableException missing) {
            return named(missing.getVariableName());
        }
        if (ex instanceof MissingMatrixVariableException missing) {
            return named(missing.getVariableName());
        }
        if (ex instanceof MissingRequestCookieException missing) {
            return named(missing.getCookieName());
        }
        if (ex instanceof BindException bind) {
            ArrayList<String> fields = new ArrayList<>();
            for (FieldError error : bind.getFieldErrors()) {
                fields.add(error.getField());
            }
            return namedAll(fields);
        }
        if (ex instanceof ConstraintViolationException violation) {
            ArrayList<String> fields = new ArrayList<>();
            for (ConstraintViolation<?> item : violation.getConstraintViolations()) {
                fields.add(item.getPropertyPath() == null ? null : item.getPropertyPath().toString());
            }
            return namedAll(fields);
        }
        if (ex instanceof HandlerMethodValidationException validation) {
            ArrayList<String> fields = new ArrayList<>();
            for (ParameterValidationResult result : validation.getParameterValidationResults()) {
                fields.add(parameterName(result.getMethodParameter()));
            }
            return namedAll(fields);
        }
        return ReefErrorCode.INVALID_INPUT.message();
    }

    private static String parameterName(MethodParameter parameter) {
        if (parameter == null) {
            return null;
        }
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        String explicit = explicit(requestParam == null ? null : requestParam.name(),
                requestParam == null ? null : requestParam.value());
        if (explicit != null) {
            return explicit;
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        explicit = explicit(pathVariable == null ? null : pathVariable.name(),
                pathVariable == null ? null : pathVariable.value());
        if (explicit != null) {
            return explicit;
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        explicit = explicit(header == null ? null : header.name(), header == null ? null : header.value());
        if (explicit != null) {
            return explicit;
        }
        return parameter.getParameterName();
    }

    private static String explicit(String name, String value) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (value != null && !value.isBlank()) {
            return value;
        }
        return null;
    }

    private static String named(String field) {
        String safe = safeField(field);
        if (safe == null) {
            return ReefErrorCode.INVALID_INPUT.message();
        }
        return fieldMessage(safe);
    }

    private static String namedAll(List<String> fields) {
        ArrayList<String> safe = new ArrayList<>();
        for (String field : fields) {
            String cleaned = safeField(field);
            if (cleaned != null && !safe.contains(cleaned)) {
                safe.add(cleaned);
            }
        }
        if (safe.isEmpty()) {
            return ReefErrorCode.INVALID_INPUT.message();
        }
        safe.sort(String::compareTo);
        StringBuilder joined = new StringBuilder();
        for (String field : safe) {
            if (joined.length() > 0) {
                if (joined.length() + 2 + field.length() > 120) {
                    break;
                }
                joined.append(", ");
            }
            joined.append(field);
        }
        return fieldMessage(joined.toString());
    }

    private static String fieldMessage(String fields) {
        return "字段 " + fields + " 无效";
    }

    private static String safeField(String field) {
        if (field == null) {
            return null;
        }
        int dot = field.lastIndexOf('.');
        String leaf = dot >= 0 ? field.substring(dot + 1) : field;
        int bracket = leaf.indexOf('[');
        if (bracket >= 0) {
            leaf = leaf.substring(0, bracket);
        }
        if (!FIELD.matcher(leaf).matches()) {
            return null;
        }
        return leaf;
    }

    private static ResponseEntity<ApiResponse<Void>> fail(HttpStatus status, ReefErrorCode code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.fail(code, message, TraceIds.current(request)));
    }
}
