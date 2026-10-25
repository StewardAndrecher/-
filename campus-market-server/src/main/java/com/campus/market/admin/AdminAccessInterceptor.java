package com.campus.market.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAccessInterceptor implements HandlerInterceptor {
    public static final String ADMIN_USER = "com.campus.market.admin.currentUser";
    private final AdminAccessService access;
    public AdminAccessInterceptor(AdminAccessService access) {this.access = access;}
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        if (CorsUtils.isPreFlightRequest(request)) return true;
        request.setAttribute(ADMIN_USER, access.requireAdmin(request.getHeader("Authorization")));
        return true;
    }
}
