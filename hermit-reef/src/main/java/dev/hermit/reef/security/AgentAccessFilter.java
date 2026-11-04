package dev.hermit.reef.security;

import dev.hermit.reef.admin.AgentTokenService;
import dev.hermit.reef.web.ApiResponseWriter;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 只挂在 {@code /agent/**} 那条安全链上，和 {@code securityMatcher} 用同一套路径匹配。
 * 不注册成全局 Servlet 过滤器，避免在防火墙规范化路径之前用原始 URI 做判断。
 */
public class AgentAccessFilter extends OncePerRequestFilter {

    public static final String INSTANCE_HEADER = "X-Hermit-Instance";
    public static final String TOKEN_HEADER = "X-Hermit-Token";
    public static final String PRINCIPAL = AgentPrincipal.class.getName();

    // Crab 用 UUID.toString() 生成实例号，只接受这种 8-4-4-4-12 形式。
    private static final Pattern INSTANCE_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ApiResponseWriter writer;
    private final AgentTokenService tokens;
    private final AgentPresence presence;

    public AgentAccessFilter(ApiResponseWriter writer, AgentTokenService tokens, AgentPresence presence) {
        this.writer = writer;
        this.tokens = tokens;
        this.presence = presence;
    }

    public static AgentPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL);
        return value instanceof AgentPrincipal principal ? principal : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String instanceId = request.getHeader(INSTANCE_HEADER);
        if (!INSTANCE_ID.matcher(instanceId == null ? "" : instanceId).matches()) {
            writer.write(response, 400, ReefErrorCode.INVALID_INPUT, "invalid instance id", TraceIds.current(request));
            return;
        }
        Optional<AgentPrincipal> principal = tokens.authenticate(request.getHeader(TOKEN_HEADER));
        if (principal.isEmpty()) {
            writer.write(response, 401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message(),
                    TraceIds.current(request));
            return;
        }
        request.setAttribute(PRINCIPAL, principal.get());
        presence.seen(principal.get(), instanceId);
        filterChain.doFilter(request, response);
    }
}
