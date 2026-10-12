package dev.hermit.reef.security;

import dev.hermit.reef.web.ApiResponseWriter;
import dev.hermit.reef.web.ErrorCodes;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiResponseWriter writer;

    public ApiAuthenticationEntryPoint(ApiResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        writer.write(response, HttpStatus.UNAUTHORIZED.value(), ErrorCodes.UNAUTHORIZED,
                ErrorCodes.UNAUTHORIZED_MESSAGE, TraceIds.current(request));
    }
}
