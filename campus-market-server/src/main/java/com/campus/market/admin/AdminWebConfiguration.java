package com.campus.market.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminWebConfiguration implements WebMvcConfigurer {
    private final AdminAccessInterceptor interceptor;
    public AdminWebConfiguration(AdminAccessInterceptor interceptor) {this.interceptor = interceptor;}
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/admin", "/api/admin/**");
    }
}
