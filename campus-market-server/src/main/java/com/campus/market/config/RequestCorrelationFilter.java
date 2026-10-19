package com.campus.market.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/** 当前同步HTTP请求的关联编号，不是跨线程/跨服务分布式追踪。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".id";
    private static final String EXPOSE = "Access-Control-Expose-Headers";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 编号由服务端生成；不采用调用方传入值，不用于认证、幂等或订单标识。
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String previous = MDC.get("requestId");
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        HttpServletResponseWrapper wrapped = new HttpServletResponseWrapper(response) {
            // 保留Spring CORS原本暴露的Retry-After等响应头，不改变允许来源策略。
            private String exposed(String value, boolean append) {
                var names = new LinkedHashMap<String, String>();
                String existing = append ? getHeader(EXPOSE) : null;
                for (String list : new String[]{existing, value, HEADER}) {
                    if (list != null) for (String name : list.split(",")) {
                        String trimmed = name.trim();
                        if (!trimmed.isEmpty()) names.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                    }
                }
                return String.join(", ", names.values());
            }
            @Override public void setHeader(String name, String value) {
                super.setHeader(name, EXPOSE.equalsIgnoreCase(name) ? exposed(value, false) : value);
            }
            @Override public void addHeader(String name, String value) {
                if (EXPOSE.equalsIgnoreCase(name)) super.setHeader(name, exposed(value, true));
                else super.addHeader(name, value);
            }
        };
        wrapped.setHeader(EXPOSE, HEADER);
        long started = System.nanoTime();
        int thrownStatus = 0;
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, wrapped);
        } catch (IOException | ServletException | RuntimeException exception) {
            thrownStatus = 500;
            throw exception;
        } finally {
            try {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (!path.equals("/api/ops/metrics") && !path.equals("/api/health") && !path.startsWith("/api/health/")) {
                    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    String route = pattern == null ? "unmatched" : pattern.toString();
                    if (!route.matches("[a-zA-Z0-9/{}_.-]{1,200}")) route = "unmatched";
                    String method = request.getMethod();
                    if (!method.matches("GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE")) method = "OTHER";
                    int status = thrownStatus == 0 ? response.getStatus() : thrownStatus;
                    long durationMs = (System.nanoTime() - started) / 1_000_000;
                    // 只记路由模板、状态与耗时，不记原始URL、参数、请求体或认证头。
                    if (status >= 400) log.warn("http_request method={} route={} status={} durationMs={}", method, route, status, durationMs);
                    else log.info("http_request method={} route={} status={} durationMs={}", method, route, status, durationMs);
                }
            } finally {
                if (previous == null) MDC.remove("requestId");
                else MDC.put("requestId", previous);
            }
        }
    }
}
