package com.campus.market.order.mq;

import com.campus.market.order.OrderService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name="market.orders.mq.enabled",havingValue="true")
public class OrderTimeoutConsumer {
 private final OrderTimeoutOutbox outbox;private final OrderService orders;private final OrderMqMetrics metrics;
 private final JsonMapper json=JsonMapper.builder().build();
 public OrderTimeoutConsumer(OrderTimeoutOutbox outbox,OrderService orders,OrderMqMetrics metrics){this.outbox=outbox;this.orders=orders;this.metrics=metrics;}
 @RabbitListener(id="orderTimeoutListener",queues="#{@orderMqSettings.workQueue()}",containerFactory="orderMqContainerFactory")
 public void consume(Message message) {
  long started=System.nanoTime();var outcome=OrderMqMetrics.Consume.FAILED;
  var reason=OrderMqLog.Reason.MALFORMED;
  // 未核验消息身份前清除本作用域eventId，不采用来访MDC/消息头作为可信身份。
  try(var context=OrderMqLog.event(null)) {
   try {
    String eventId;long orderId;
    try{
     if(message.getBody().length>256)throw new IllegalArgumentException();
     var body=json.readTree(message.getBody());
     if(body==null||!body.isObject()||body.size()!=3||!body.path("schema").isInt()||body.path("schema").asInt()!=1||!body.path("eventId").isString()||!body.path("orderId").isIntegralNumber()||!body.path("orderId").canConvertToLong())throw new IllegalArgumentException();
     eventId=body.path("eventId").asText();orderId=body.path("orderId").asLong();
     if(!eventId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!eventId.equals(message.getMessageProperties().getMessageId())||orderId<1||orderId>9007199254740991L)throw new IllegalArgumentException();
    }catch(Exception malformed){throw new AmqpRejectAndDontRequeueException("订单超时消息格式无效");}
    reason=OrderMqLog.Reason.SOURCE_CHECK_FAILED;
    if(!outbox.matches(eventId,orderId)){
     reason=OrderMqLog.Reason.SOURCE_MISMATCH;
     throw new AmqpRejectAndDontRequeueException("订单超时消息来源记录不匹配");
    }
    context.bind(eventId);
    reason=OrderMqLog.Reason.PROCESSING_FAILED;
    // 事务代理返回后才由容器ACK；已付款/已关闭、重复或提前消息均由数据库状态/时间校验决定。
    outcome=orders.closeExpired(orderId)?OrderMqMetrics.Consume.CLOSED:OrderMqMetrics.Consume.NOOP;
    reason=OrderMqLog.Reason.NONE;
   }catch(AmqpRejectAndDontRequeueException rejected){outcome=OrderMqMetrics.Consume.REJECTED;throw rejected;}
   finally{
    long elapsed=System.nanoTime()-started;
    metrics.consumed(outcome,elapsed);
    OrderMqLog.consumed(outcome,reason,elapsed);
   }
  }
 }
}
