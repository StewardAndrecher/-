package com.campus.market.docs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** 开发文档独立开关；不改变业务接口认证或允许来源。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiDocsAccessFilter extends OncePerRequestFilter {
    private final boolean enabled;
    public ApiDocsAccessFilter(@Value("${springdoc.api-docs.enabled:false}") boolean enabled) { this.enabled=enabled; }
    public static boolean docsPath(String path) {
        return path.equals("/docs.html") || path.equals("/api-docs") || path.equals("/api-docs.yaml") ||
                path.startsWith("/api-docs/") || path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/") ||
                path.startsWith("/v3/api-docs") || path.startsWith("/webjars/swagger-ui/");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !docsPath(request.getRequestURI().substring(request.getContextPath().length()));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        if(!enabled){response.setStatus(404);return;}
        if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()) ||
                !Set.of("localhost","127.0.0.1","[::1]","::1").contains(request.getServerName())) {
            response.setStatus(403);return;
        }
        String origin=request.getHeader("Origin");
        if(origin!=null && !origin.equals(request.getScheme()+"://"+request.getHeader("Host"))){response.setStatus(403);return;}
        if(!Set.of("GET","HEAD").contains(request.getMethod())){response.setStatus(405);response.setHeader("Allow","GET, HEAD");return;}
        chain.doFilter(request,response);
    }
}
