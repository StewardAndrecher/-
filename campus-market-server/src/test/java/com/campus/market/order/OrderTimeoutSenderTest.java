package com.campus.market.order;
import com.campus.market.order.mq.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderTimeoutSenderTest {
 final RabbitTemplate rabbit=mock(RabbitTemplate.class);
 final OrderMqSettings settings=new OrderMqSettings("campus.order.unit74",200,100);
 final OrderTimeoutSender sender=new OrderTimeoutSender(rabbit,settings);
 final OrderTimeoutOutbox.Job job=new OrderTimeoutOutbox.Job("00000000-0000-4000-8000-000000000001",7L,"lease",1);
 void reply(boolean ack,boolean returned){doAnswer(call->{CorrelationData data=call.getArgument(3);if(returned)data.setReturned(new ReturnedMessage(call.getArgument(2),312,"NO_ROUTE",settings.delayExchange(),"delay"));data.getFuture().complete(new CorrelationData.Confirm(ack,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));}
 @Test void confirmedAndRoutedIsSuccess()throws Exception{reply(true,false);sender.send(job);verify(rabbit).send(eq(settings.delayExchange()),eq("delay"),any(Message.class),any(CorrelationData.class));}
 @Test void nackIsFailure(){reply(false,false);assertThrows(IllegalStateException.class,()->sender.send(job));}
 @Test void ackWithReturnIsStillFailure(){reply(true,true);assertThrows(IllegalStateException.class,()->sender.send(job));}
 @Test void missingConfirmTimesOut(){assertThrows(TimeoutException.class,()->sender.send(job));}
 @Test void sendErrorPropagates(){doThrow(new IllegalStateException()).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));assertThrows(IllegalStateException.class,()->sender.send(job));}
 @Test void messageIsPersistentAndContainsOnlyRoutingIdentity()throws Exception{doAnswer(call->{Message msg=call.getArgument(2);assertEquals(MessageDeliveryMode.PERSISTENT,msg.getMessageProperties().getDeliveryMode());assertEquals(job.eventId(),msg.getMessageProperties().getMessageId());assertEquals("application/json",msg.getMessageProperties().getContentType());var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(msg.getBody());assertEquals(3,json.size());assertEquals(1,json.get("schema").asInt());assertEquals(7,json.get("orderId").asLong());assertEquals(job.eventId(),json.get("eventId").asText());CorrelationData data=call.getArgument(3);data.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));sender.send(job);}
}
