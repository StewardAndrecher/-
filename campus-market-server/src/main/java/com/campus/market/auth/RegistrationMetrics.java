package com.campus.market.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Admission decisions only: an allowed request can still fail validation or conflict. */
@Component
@ConditionalOnProperty(name="market.monitoring.enabled", havingValue="true")
public class RegistrationMetrics {
    enum Outcome { ALLOWED, LIMITED, UNAVAILABLE, DISABLED }
    private final EnumMap<Outcome, Counter> decisions = new EnumMap<>(Outcome.class);

    public RegistrationMetrics(MeterRegistry registry,
            @Value("${market.registration-protection.enabled:${market.login-protection.enabled:true}}") boolean enabled,
            @Value("${market.registration-protection.limit:60}") int limit,
            @Value("${market.registration-protection.window-seconds:60}") int seconds) {
        if (limit < 1 || limit > 10000 || seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("注册保护配置无效");
        Gauge.builder("campus.registration.protection.enabled", () -> enabled ? 1 : 0).register(registry);
        Gauge.builder("campus.registration.protection.limit", () -> limit).register(registry);
        Gauge.builder("campus.registration.protection.window.seconds", () -> seconds).register(registry);
        // Pre-register the four fixed outcomes. Never tag addresses, usernames or credentials.
        for (Outcome outcome : Outcome.values()) {
            decisions.put(outcome, Counter.builder("campus.registration.decisions")
                    .description("Registration admission decisions, not successful registrations")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
    }

    void record(Outcome outcome) { decisions.get(outcome).increment(); }
}
