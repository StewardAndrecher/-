package com.campus.market.config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration
@ConditionalOnProperty(name={"market.monitoring.enabled","market.orders.mq.enabled"},havingValue="true")
public class MqMonitorConfiguration {
 @Bean(name="mqMonitorTaskScheduler") public ThreadPoolTaskScheduler mqMonitorTaskScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("mq-monitor-");return scheduler;}
}
