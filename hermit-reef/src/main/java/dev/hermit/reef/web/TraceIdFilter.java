package dev.hermit.reef.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceIds.resolve(request.getHeader(TraceIds.HEADER));
        request.setAttribute(TraceIds.ATTRIBUTE, traceId);
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                // 4xx 的异常消息可能含请求体。这里只记状态和 traceId，和响应里的是同一个值。
                logFailure(response.getStatus(), traceId);
            } finally {
                MDC.remove(TraceIds.MDC_KEY);
            }
        }
    }

    private static void logFailure(int status, String traceId) {
        if (status >= 500) {
            log.error("request failed status={} traceId={}", status, traceId);
        } else if (status >= 400) {
            log.warn("request failed status={} traceId={}", status, traceId);
        }
    }
}
