package dev.hermit.reef.security;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiResponseWriter;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AgentAccessFilter extends OncePerRequestFilter {

    public static final String INSTANCE_HEADER = "X-Hermit-Instance";
    public static final String TOKEN_HEADER = "X-Hermit-Token";

    // Crab 用 UUID.toString() 生成实例号，只接受这种 8-4-4-4-12 形式。
    private static final Pattern INSTANCE_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ReefProperties properties;
    private final ApiResponseWriter writer;

    public AgentAccessFilter(ReefProperties properties, ApiResponseWriter writer) {
        this.properties = properties;
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathWithinApplication(request).startsWith("/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!INSTANCE_ID.matcher(header(request, INSTANCE_HEADER)).matches()) {
            writer.write(response, 400, ReefErrorCode.INVALID_INPUT, "invalid instance id", TraceIds.current(request));
            return;
        }
        String configured = properties.configuredAgentToken();
        if (configured != null && !TokenEquality.constantTimeEquals(configured, request.getHeader(TOKEN_HEADER))) {
            writer.write(response, 401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message(),
                    TraceIds.current(request));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value;
    }

    static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }
}
