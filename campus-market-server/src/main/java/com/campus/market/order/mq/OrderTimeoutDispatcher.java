package com.campus.market.order.mq;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.orders.mq.enabled",havingValue="true")
public class OrderTimeoutDispatcher {
 private static final Logger log=LoggerFactory.getLogger(OrderTimeoutDispatcher.class);
 private final OrderTimeoutOutbox outbox;private final OrderTimeoutSender sender;
 @Value("${market.orders.mq.dispatch-enabled:true}")private boolean enabled;
 private long lastWarning;
 public OrderTimeoutDispatcher(OrderTimeoutOutbox outbox,OrderTimeoutSender sender){this.outbox=outbox;this.sender=sender;}
 @Scheduled(scheduler="orderMqTaskScheduler",fixedDelayString="${market.orders.mq.dispatch-delay-ms:1000}")
 public void tick(){if(!enabled)return;try{for(int n=0;n<10&&!Thread.currentThread().isInterrupted();n++)if(!dispatchOne())break;}catch(RuntimeException e){warn();}}
 public boolean dispatchOne(){
  var job=outbox.claim();if(job==null)return false;
  try{sender.send(job);outbox.published(job);}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();outbox.failed(job);warn();}
  return true;
 }
 private synchronized void warn(){long now=System.nanoTime();if(now-lastWarning>5_000_000_000L){lastWarning=now;log.warn("订单MQ投递暂未确认，任务已保留或等待租约恢复；数据库超时补偿仍启用");}}
}
