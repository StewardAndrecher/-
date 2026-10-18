package com.campus.market.order.mq;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.orders.mq.enabled",havingValue="true")
public class OrderTimeoutSender {
 private final RabbitTemplate rabbit;private final OrderMqSettings settings;
 public OrderTimeoutSender(RabbitTemplate rabbit,OrderMqSettings settings){this.rabbit=rabbit;this.settings=settings;}
 public void send(OrderTimeoutOutbox.Job job)throws Exception {
  byte[] body=("{\"schema\":1,\"eventId\":\""+job.eventId()+"\",\"orderId\":"+job.orderId()+"}").getBytes(StandardCharsets.UTF_8);
  var message=MessageBuilder.withBody(body).setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8").setMessageId(job.eventId()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
  var correlation=new CorrelationData(job.eventId());rabbit.send(settings.delayExchange(),"delay",message,correlation);
  var confirmation=correlation.getFuture().get(settings.confirmTimeoutMs(),TimeUnit.MILLISECONDS);
  if(!confirmation.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("订单消息未得到可路由确认");
 }
}
