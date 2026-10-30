package dev.hermit.reef.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTest {

    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    void parameterFailuresAreMappedToNamedInvalidInput() {
        assertThat(handler(MethodArgumentTypeMismatchException.class)).isEqualTo("invalidParameter");
        assertThat(handler(MethodArgumentConversionNotSupportedException.class)).isEqualTo("invalidParameter");
        assertThat(handler(MissingServletRequestParameterException.class)).isEqualTo("invalidParameter");
        assertThat(handler(MissingPathVariableException.class)).isEqualTo("invalidParameter");
        assertThat(handler(ServletRequestBindingException.class)).isEqualTo("invalidParameter");
        assertThat(handler(BindException.class)).isEqualTo("invalidParameter");
        assertThat(handler(ConstraintViolationException.class)).isEqualTo("invalidParameter");
        assertThat(handler(HandlerMethodValidationException.class)).isEqualTo("invalidParameter");
        assertThat(handler(MethodArgumentNotValidException.class)).isEqualTo("invalidInput");
    }

    @Test
    void messagesNameTheFieldAndOmitTheRejectedValue() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = new MockHttpServletRequest();
        String secret = "sid-secret-cookie-91";

        ResponseEntity<ApiResponse<Void>> missing = handler.invalidParameter(
                new MissingServletRequestParameterException("widget", "int"), request);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().error().code()).isEqualTo(ReefErrorCode.INVALID_INPUT);
        assertThat(missing.getBody().error().message()).isEqualTo("invalid input: widget");

        BindException bind = new BindException(new Object(), "query");
        bind.addError(new FieldError("query", "size", secret, false, null, null, "rejected " + secret));
        ResponseEntity<ApiResponse<Void>> bound = handler.invalidParameter(bind, request);
        assertThat(bound.getBody().error().message()).isEqualTo("invalid input: size");
        assertThat(String.valueOf(bound.getBody())).doesNotContain(secret);

        UnsatisfiedServletRequestParameterException unsatisfied = new UnsatisfiedServletRequestParameterException(
                new String[] {"size"}, Map.of("token", new String[] {secret}));
        ResponseEntity<ApiResponse<Void>> loose = handler.invalidParameter(unsatisfied, request);
        assertThat(loose.getStatusCode().value()).isEqualTo(400);
        assertThat(loose.getBody().error().message()).isEqualTo("invalid input");
        assertThat(String.valueOf(loose.getBody())).doesNotContain(secret);
    }

    private String handler(Class<? extends Throwable> type) {
        return resolver.resolveMethodByExceptionType(type).getName();
    }
}
