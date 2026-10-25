package com.campus.market.notification.mq;
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
@ConditionalOnProperty(name="market.notifications.mq.enabled",havingValue="true")
public class NotificationMqConfiguration {
 @Bean(name="notificationTaskScheduler") public ThreadPoolTaskScheduler notificationTaskScheduler(){var s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("notification-mq-");return s;}
 @Bean public NotificationMqSettings notificationMqSettings(@Value("${market.notifications.mq.prefix:campus.notification.v1}")String prefix,@Value("${market.notifications.mq.confirm-timeout-ms:5000}")long timeout){return new NotificationMqSettings(prefix,timeout);}
 @Bean public Declarables notificationTopology(NotificationMqSettings s){
  var exchange=new DirectExchange(s.exchange(),true,false);var parking=new DirectExchange(s.parkingExchange(),true,false);
  var queue=new org.springframework.amqp.core.Queue(s.queue(),true,false,false,Map.of("x-queue-type","quorum","x-max-length",100000,"x-overflow","reject-publish","x-dead-letter-exchange",s.parkingExchange(),"x-dead-letter-routing-key","parking","x-dead-letter-strategy","at-least-once"));
  var pq=new org.springframework.amqp.core.Queue(s.parkingQueue(),true,false,false,Map.of("x-queue-type","quorum","x-max-length",100000,"x-overflow","reject-publish"));
  return new Declarables(exchange,parking,queue,pq,BindingBuilder.bind(queue).to(exchange).with("notice"),BindingBuilder.bind(pq).to(parking).with("parking"));
 }
 @Bean public SimpleRabbitListenerContainerFactory notificationContainerFactory(ConnectionFactory connection,@Value("${market.notifications.mq.listener-auto-startup:true}")boolean startup){
  var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(connection);factory.setAcknowledgeMode(AcknowledgeMode.AUTO);factory.setDefaultRequeueRejected(false);factory.setMissingQueuesFatal(false);factory.setRecoveryInterval(5000L);factory.setPrefetchCount(10);factory.setConcurrentConsumers(1);factory.setAutoStartup(startup);
  factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxRetries(2).backOffOptions(200,2,1000).recoverer(new RejectAndDontRequeueRecoverer()).build());return factory;
 }
}
