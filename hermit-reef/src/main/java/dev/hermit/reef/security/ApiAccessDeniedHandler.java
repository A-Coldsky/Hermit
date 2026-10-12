package dev.hermit.reef.security;

import dev.hermit.reef.web.ApiResponseWriter;
import dev.hermit.reef.web.ErrorCodes;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiResponseWriter writer;

    public ApiAccessDeniedHandler(ApiResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        writer.write(response, HttpStatus.FORBIDDEN.value(), ErrorCodes.FORBIDDEN, ErrorCodes.FORBIDDEN_MESSAGE,
                TraceIds.current(request));
    }
}
