package dev.hermit.reef.shell;

import dev.hermit.reef.web.ApiError;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShellExceptionHandler {

    @ExceptionHandler(ShellRequestException.class)
    public ResponseEntity<ApiResponse<Object>> rejected(ShellRequestException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
                .body(new ApiResponse<>(false, ex.data(), new ApiError(ex.code(), ex.getMessage()), TraceIds.current(request)));
    }

    @ExceptionHandler({
            MaxUploadSizeExceededException.class,
            MissingServletRequestPartException.class,
            MultipartException.class
    })
    public ResponseEntity<ApiResponse<Void>> badUpload(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(422)
                .body(ApiResponse.fail(ReefErrorCode.INVALID_INPUT, ReefErrorCode.INVALID_INPUT.message(),
                        TraceIds.current(request)));
    }
}
