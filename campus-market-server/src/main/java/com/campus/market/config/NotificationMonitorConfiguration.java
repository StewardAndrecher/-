package com.campus.market.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="market.monitoring.enabled",havingValue="true")
public class NotificationMonitorConfiguration {
    @Bean(name="notificationMonitorTaskScheduler")
    public ThreadPoolTaskScheduler notificationMonitorTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("notification-monitor-");
        return scheduler;
    }
}
