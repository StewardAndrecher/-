package com.campus.market.order.mq;

import java.util.*;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"market.monitoring.enabled","market.orders.mq.enabled"},havingValue="true")
public class OrderMqMonitor {
 private final JdbcTemplate jdbc;private final AmqpAdmin admin;private final OrderMqSettings settings;private final OrderMqMetrics metrics;
 @Value("${market.monitoring.poll-enabled:true}")private boolean enabled;
 public OrderMqMonitor(JdbcTemplate jdbc,AmqpAdmin admin,OrderMqSettings settings,OrderMqMetrics metrics){this.jdbc=jdbc;this.admin=admin;this.settings=settings;this.metrics=metrics;}
 @Scheduled(scheduler="mqMonitorTaskScheduler",initialDelayString="${market.monitoring.initial-delay-ms:1000}",fixedDelayString="${market.monitoring.poll-delay-ms:5000}")
 public void tick(){if(enabled)poll();}
 public void poll(){
  try{
   var row=jdbc.queryForMap("SELECT COUNT(*) AS pending,COALESCE(SUM(attempts>0),0) AS retrying,COALESCE(SUM(lease_until>CURRENT_TIMESTAMP(6)),0) AS leased,COALESCE(GREATEST(0,TIMESTAMPDIFF(MICROSECOND,MIN(created_at),CURRENT_TIMESTAMP(6)))/1000000.0,0) AS oldest FROM order_timeout_outbox WHERE published_at IS NULL");
   metrics.outbox(number(row,"pending"),number(row,"retrying"),number(row,"leased"),number(row,"oldest"));
  }catch(RuntimeException unavailable){metrics.outboxFailed();}
  try{
   var ready=new HashMap<String,Double>();var consumers=new HashMap<String,Double>();
   var queues=Map.of("delay",settings.delayQueue(),"timeout",settings.workQueue(),"parking",settings.parkingQueue());
   for(var queue:queues.entrySet()){
    var info=admin.getQueueInfo(queue.getValue());
    if(info==null)throw new IllegalStateException("Queue unavailable");
    ready.put(queue.getKey(),(double)info.getMessageCount());consumers.put(queue.getKey(),(double)info.getConsumerCount());
   }
   metrics.broker(ready,consumers);
  }catch(RuntimeException unavailable){metrics.brokerFailed();}
 }
 private double number(Map<String,Object> row,String name){return ((Number)row.get(name)).doubleValue();}
}
