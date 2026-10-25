package com.campus.market.notification.mq;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.campus.market.notification.NotificationOutbox;
@Component
@ConditionalOnProperty(name="market.notifications.mq.enabled",havingValue="true")
public class NotificationConsumer {
 private final NotificationOutbox outbox;private final JsonMapper json=JsonMapper.builder().build();
 public NotificationConsumer(NotificationOutbox outbox){this.outbox=outbox;}
 @RabbitListener(id="notificationListener",queues="#{@notificationMqSettings.queue()}",containerFactory="notificationContainerFactory")
 public void consume(Message message){
  String event;
  try{if(message.getBody().length>128)throw new IllegalArgumentException();var body=json.readTree(message.getBody());
   if(body==null||!body.isObject()||body.size()!=2||!body.path("schema").isInt()||body.path("schema").asInt()!=1||!body.path("eventId").isString())throw new IllegalArgumentException();
   event=body.path("eventId").asString();if(!event.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!event.equals(message.getMessageProperties().getMessageId()))throw new IllegalArgumentException();
  }catch(Exception e){throw new AmqpRejectAndDontRequeueException("通知消息格式无效");}
  try{outbox.deliver(event);}catch(NotificationOutbox.UnknownNotificationEvent e){throw new AmqpRejectAndDontRequeueException("通知消息来源记录不匹配");}
 }
}
