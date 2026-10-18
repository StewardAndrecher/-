package com.campus.market.order.mq;

import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="market.orders.mq.enabled",havingValue="true")
public class OrderMqConfiguration {
 @Bean(name="taskScheduler") public ThreadPoolTaskScheduler taskScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("market-scan-");return scheduler;}
 @Bean(name="orderMqTaskScheduler") public ThreadPoolTaskScheduler orderMqTaskScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("order-mq-");return scheduler;}
 @Bean public OrderMqSettings orderMqSettings(@Value("${market.orders.mq.prefix:campus.order.v1}")String prefix,@Value("${market.orders.mq.delay-ms:900000}")int delay,@Value("${market.orders.mq.confirm-timeout-ms:5000}")long timeout){return new OrderMqSettings(prefix,delay,timeout);}
 private org.springframework.amqp.core.Queue queue(String name,Map<String,Object> extra){
  var args=new HashMap<String,Object>();args.put("x-queue-type","quorum");args.put("x-max-length",100000);args.put("x-overflow","reject-publish");args.putAll(extra);
  return new org.springframework.amqp.core.Queue(name,true,false,false,args);
 }
 @Bean public Declarables orderMqTopology(OrderMqSettings s) {
  var delay=new DirectExchange(s.delayExchange(),true,false);var work=new DirectExchange(s.workExchange(),true,false);var parking=new DirectExchange(s.parkingExchange(),true,false);
  var dq=queue(s.delayQueue(),Map.of("x-message-ttl",s.delayMs(),"x-dead-letter-exchange",s.workExchange(),"x-dead-letter-routing-key","timeout","x-dead-letter-strategy","at-least-once"));
  var wq=queue(s.workQueue(),Map.of("x-dead-letter-exchange",s.parkingExchange(),"x-dead-letter-routing-key","parking","x-dead-letter-strategy","at-least-once"));
  var pq=queue(s.parkingQueue(),Map.of());
  return new Declarables(delay,work,parking,dq,wq,pq,BindingBuilder.bind(dq).to(delay).with("delay"),BindingBuilder.bind(wq).to(work).with("timeout"),BindingBuilder.bind(pq).to(parking).with("parking"));
 }
 @Bean public SimpleRabbitListenerContainerFactory orderMqContainerFactory(ConnectionFactory connectionFactory,@Value("${market.orders.mq.listener-auto-startup:true}")boolean startup){
  var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(connectionFactory);
  factory.setAcknowledgeMode(AcknowledgeMode.AUTO);factory.setDefaultRequeueRejected(false);factory.setMissingQueuesFatal(false);factory.setRecoveryInterval(5000L);factory.setPrefetchCount(10);factory.setConcurrentConsumers(1);factory.setAutoStartup(startup);
  // Spring AMQP4.1的maxRetries(2)表示首次执行之外最多再重试两次。
  factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxRetries(2).backOffOptions(200,2,1000).recoverer(new RejectAndDontRequeueRecoverer()).build());
  return factory;
 }
}
