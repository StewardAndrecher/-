package com.campus.market.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="market.orders.timeout-sweep-enabled",havingValue="true",matchIfMissing=false)
public class OrderTimeoutScheduler {
 private final OrderTimeoutSweep sweep;
 public OrderTimeoutScheduler(OrderTimeoutSweep sweep){this.sweep=sweep;}
 @Scheduled(fixedDelayString="${market.orders.timeout-sweep-delay-ms:30000}",initialDelayString="${market.orders.timeout-sweep-delay-ms:30000}")
 public void closeDueOrders(){sweep.runOnce();}
}
