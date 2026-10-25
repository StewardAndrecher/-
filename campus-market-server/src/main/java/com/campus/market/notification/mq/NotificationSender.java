package com.campus.market.notification.mq;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.campus.market.notification.NotificationOutbox;
@Component
@ConditionalOnProperty(name="market.notifications.mq.enabled",havingValue="true")
public class NotificationSender {
 private final RabbitTemplate rabbit;private final NotificationMqSettings settings;
 public NotificationSender(RabbitTemplate rabbit,NotificationMqSettings settings){this.rabbit=rabbit;this.settings=settings;}
 public void send(NotificationOutbox.Job job)throws Exception{
  var message=MessageBuilder.withBody(("{\"schema\":1,\"eventId\":\""+job.eventId()+"\"}").getBytes(StandardCharsets.UTF_8)).setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8").setMessageId(job.eventId()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
  var confirmation=new CorrelationData(job.eventId());rabbit.send(settings.exchange(),"notice",message,confirmation);
  if(!confirmation.getFuture().get(settings.confirmTimeoutMs(),TimeUnit.MILLISECONDS).isAck()||confirmation.getReturned()!=null)throw new IllegalStateException("通知消息未得到可路由确认");
 }
}
