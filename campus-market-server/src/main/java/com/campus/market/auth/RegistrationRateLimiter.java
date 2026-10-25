package com.campus.market.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RegistrationRateLimiter {
    private final LoginRateLimiter store;
    private final boolean enabled;
    private final int limit, seconds;
    private final RegistrationMetrics metrics;

    @Autowired
    public RegistrationRateLimiter(LoginRateLimiter store,
            @Value("${market.registration-protection.enabled:${market.login-protection.enabled:true}}") boolean enabled,
            @Value("${market.registration-protection.limit:60}") int limit,
            @Value("${market.registration-protection.window-seconds:60}") int seconds,
            ObjectProvider<RegistrationMetrics> metrics) {
        this(store, enabled, limit, seconds, metrics.getIfAvailable());
    }

    public RegistrationRateLimiter(LoginRateLimiter store, boolean enabled, int limit, int seconds) {
        this(store, enabled, limit, seconds, (RegistrationMetrics) null);
    }

    RegistrationRateLimiter(LoginRateLimiter store, boolean enabled, int limit, int seconds,
            RegistrationMetrics metrics) {
        if (limit < 1 || limit > 10000 || seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("注册保护配置无效");
        if (enabled && !store.protectionEnabled())
            throw new IllegalArgumentException("注册保护需要启用现有登录保护Redis连接");
        this.store = store; this.enabled = enabled; this.limit = limit;
        this.seconds = seconds; this.metrics = metrics;
    }

    public void check(String remoteAddress) {
        if (!enabled) { record(RegistrationMetrics.Outcome.DISABLED); return; }
        try {
            store.beginRegistration(remoteAddress, limit, seconds);
        } catch (RegistrationRateLimitedException e) {
            record(RegistrationMetrics.Outcome.LIMITED); throw e;
        } catch (RegistrationProtectionUnavailableException e) {
            record(RegistrationMetrics.Outcome.UNAVAILABLE); throw e;
        }
        record(RegistrationMetrics.Outcome.ALLOWED);
    }

    private void record(RegistrationMetrics.Outcome outcome) {
        if (metrics != null) metrics.record(outcome);
    }
}
