package com.campus.market.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.campus.market.order.mq.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderMqLogTest {
    static final String EVENT="00000000-0000-4000-8000-000000000001", REQUEST="a".repeat(32);
    final Logger logger=(Logger)LoggerFactory.getLogger(OrderMqLog.class);
    final List<ILoggingEvent> events=new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    final OrderMqMetrics metrics=new OrderMqMetrics(registry);
    final OrderTimeoutOutbox outbox=mock(OrderTimeoutOutbox.class);
    final OrderService orders=mock(OrderService.class);
    final OrderTimeoutOutbox.Job job=new OrderTimeoutOutbox.Job(EVENT,7,"lease-not-for-logs",2);
    AppenderBase<ILoggingEvent> appender; Level oldLevel;
    @BeforeEach void setup(){
        MDC.clear();oldLevel=logger.getLevel();logger.setLevel(Level.INFO);
        appender=new AppenderBase<>(){@Override protected void append(ILoggingEvent e){e.prepareForDeferredProcessing();events.add(e);}};
        appender.start();logger.addAppender(appender);
    }
    @AfterEach void cleanup(){
        logger.detachAppender(appender);appender.stop();logger.setLevel(oldLevel);MDC.clear();registry.close();
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    void startTransaction(){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);}
    void completed(int status){for(var callback:TransactionSynchronizationManager.getSynchronizations())callback.afterCompletion(status);}
    ILoggingEvent last(){assertFalse(events.isEmpty());return events.get(events.size()-1);}
    void eventIs(String id){assertEquals(id,last().getMDCPropertyMap().get("eventId"));}
    void contains(String text){assertTrue(last().getFormattedMessage().contains(text),last().getFormattedMessage());}
    Message message(){return MessageBuilder.withBody(("{\"schema\":1,\"eventId\":\""+EVENT+"\",\"orderId\":7}").getBytes(StandardCharsets.UTF_8)).setMessageId(EVENT).build();}
    OrderTimeoutConsumer consumer(){return new OrderTimeoutConsumer(outbox,orders,metrics);}
    @Test void eventScopeRestoresPreviousEventAndLeavesRequestContextUntouched(){
        MDC.put("eventId","outer");MDC.put("requestId",REQUEST);
        try(var scope=OrderMqLog.event(EVENT)){assertEquals(EVENT,MDC.get("eventId"));assertEquals(REQUEST,MDC.get("requestId"));}
        assertEquals("outer",MDC.get("eventId"));assertEquals(REQUEST,MDC.get("requestId"));
    }
    @Test void invalidEventCannotInjectMdcAndCloseIsIdempotent(){
        MDC.put("eventId","outer");var scope=OrderMqLog.event("secret\ninjection");assertNull(MDC.get("eventId"));
        scope.bind(EVENT);assertEquals(EVENT,MDC.get("eventId"));scope.close();scope.close();assertEquals("outer",MDC.get("eventId"));
        assertThrows(IllegalStateException.class,()->scope.bind(EVENT));
    }
    @Test void parallelEventsDoNotShareThreadContext()throws Exception{
        var pool=Executors.newFixedThreadPool(4);
        try{List<Future<String>> futures=new ArrayList<>();for(int i=0;i<16;i++)futures.add(pool.submit(()->{
            String id=UUID.randomUUID().toString();try(var scope=OrderMqLog.event(id)){assertEquals(id,MDC.get("eventId"));}
            assertNull(MDC.get("eventId"));return id;
        }));Set<String> ids=new HashSet<>();for(var f:futures)ids.add(f.get(5,TimeUnit.SECONDS));assertEquals(16,ids.size());}
        finally{pool.shutdownNow();}
    }
    @Test void noManagedTransactionDoesNotClaimACommit(){OrderMqLog.outboxInserted(EVENT,7);assertTrue(events.isEmpty());assertFalse(TransactionSynchronizationManager.isSynchronizationActive());}
    @Test void commitLogIsDeferredAndRetainsValidatedOriginEvenAfterRequestContextChanges(){
        startTransaction();MDC.put("requestId",REQUEST);OrderMqLog.outboxInserted(EVENT,7);assertTrue(events.isEmpty());
        MDC.remove("requestId");completed(TransactionSynchronization.STATUS_COMMITTED);
        contains("stage=outbox_committed originRequestId="+REQUEST+" orderId=7");eventIs(EVENT);assertNull(MDC.get("eventId"));
    }
    @Test void rollbackAndUntrustedOriginNeverProduceMisleadingIdentity(){
        startTransaction();MDC.put("requestId","secret\nforged");OrderMqLog.outboxInserted(EVENT,7);
        completed(TransactionSynchronization.STATUS_ROLLED_BACK);assertTrue(events.isEmpty());
        completed(TransactionSynchronization.STATUS_COMMITTED);contains("originRequestId=none");assertFalse(last().getFormattedMessage().contains("secret"));
    }
    @Test void publishConfirmationLogsEventAndRetainsOriginalWireContract()throws Exception{
        RabbitTemplate rabbit=mock(RabbitTemplate.class);
        doAnswer(call->{Message m=call.getArgument(2);assertEquals(EVENT,MDC.get("eventId"));
            assertEquals(3,tools.jackson.databind.json.JsonMapper.builder().build().readTree(m.getBody()).size());
            assertFalse(m.getMessageProperties().getHeaders().containsKey("requestId"));
            ((CorrelationData)call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new OrderTimeoutSender(rabbit,new OrderMqSettings("campus.order.log79",200,100),metrics).send(job);
        contains("stage=publish result=confirmed attempt=2");eventIs(EVENT);assertNull(MDC.get("eventId"));
    }
    @Test void publishFailureLogsFixedOutcomeWithoutExceptionText(){
        RabbitTemplate rabbit=mock(RabbitTemplate.class);doThrow(new IllegalStateException("password-secret")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        MDC.put("eventId","outer");assertThrows(IllegalStateException.class,()->new OrderTimeoutSender(rabbit,new OrderMqSettings("campus.order.log79",200,100),metrics).send(job));
        contains("result=failed");eventIs(EVENT);assertFalse(last().getFormattedMessage().contains("password-secret"));assertEquals("outer",MDC.get("eventId"));
    }
    @Test void confirmedSendWithLostLeaseDoesNotClaimDatabasePublished(){
        var sender=mock(OrderTimeoutSender.class);when(outbox.claim()).thenReturn(job);when(outbox.published(job)).thenReturn(false);
        assertTrue(new OrderTimeoutDispatcher(outbox,sender).dispatchOne());contains("stage=outbox_update result=lease_lost");eventIs(EVENT);
    }
    @Test void failedSendWithLostLeaseDoesNotClaimRetryWasScheduled()throws Exception{
        var sender=mock(OrderTimeoutSender.class);when(outbox.claim()).thenReturn(job);doThrow(new IllegalStateException("secret")).when(sender).send(job);when(outbox.failed(job)).thenReturn(false);
        assertTrue(new OrderTimeoutDispatcher(outbox,sender).dispatchOne());contains("result=retry_lease_lost");eventIs(EVENT);assertNull(MDC.get("eventId"));
    }
    @Test void failedPersistencePropagatesAndRestoresScope()throws Exception{
        var sender=mock(OrderTimeoutSender.class);when(outbox.claim()).thenReturn(job);doThrow(new IllegalStateException()).when(sender).send(job);when(outbox.failed(job)).thenThrow(new IllegalStateException("private-sql"));
        MDC.put("eventId","outer");assertThrows(IllegalStateException.class,()->new OrderTimeoutDispatcher(outbox,sender).dispatchOne());contains("result=state_update_failed");eventIs(EVENT);assertEquals("outer",MDC.get("eventId"));
    }
    @Test void trustedConsumeClosedUsesEventScopeAndSameMetrics(){
        when(outbox.matches(EVENT,7)).thenReturn(true);when(orders.closeExpired(7)).thenAnswer(call->{assertEquals(EVENT,MDC.get("eventId"));return true;});
        consumer().consume(message());contains("stage=consume result=closed reason=none");eventIs(EVENT);assertNull(MDC.get("eventId"));
        assertEquals(1,registry.get("campus.order.mq.consume").tag("result","closed").counter().count());
    }
    @Test void noopConsumptionIsNotMisreportedAsAnotherOrderClose(){
        when(outbox.matches(EVENT,7)).thenReturn(true);when(orders.closeExpired(7)).thenReturn(false);consumer().consume(message());contains("result=noop reason=none");eventIs(EVENT);
    }
    @Test void businessFailurePreservesTrustedEventButNotExceptionText(){
        when(outbox.matches(EVENT,7)).thenReturn(true);when(orders.closeExpired(7)).thenThrow(new IllegalStateException("secret-business-payload"));
        assertThrows(IllegalStateException.class,()->consumer().consume(message()));contains("result=failed reason=processing_failed");eventIs(EVENT);assertFalse(last().getFormattedMessage().contains("secret"));assertNull(MDC.get("eventId"));
    }
    @Test void malformedMessageDoesNotAdoptHeaderOrLeakBody(){
        MDC.put("eventId","outer");var m=MessageBuilder.withBody("secret-malformed-body".getBytes(StandardCharsets.UTF_8)).setMessageId(EVENT).setHeader("eventId",EVENT).build();
        assertThrows(AmqpRejectAndDontRequeueException.class,()->consumer().consume(m));contains("result=rejected reason=malformed");eventIs(null);assertFalse(last().getFormattedMessage().contains("secret"));assertEquals("outer",MDC.get("eventId"));verifyNoInteractions(outbox,orders);
    }
    @Test void sourceMismatchDoesNotAdoptOtherwiseValidMessageIdentity(){
        when(outbox.matches(EVENT,7)).thenReturn(false);assertThrows(AmqpRejectAndDontRequeueException.class,()->consumer().consume(message()));contains("reason=source_mismatch");eventIs(null);verifyNoInteractions(orders);
    }
    @Test void sourceDatabaseFailureDoesNotClaimIdentityWasVerified(){
        when(outbox.matches(EVENT,7)).thenThrow(new IllegalStateException("private-database-error"));assertThrows(IllegalStateException.class,()->consumer().consume(message()));contains("result=failed reason=source_check_failed");eventIs(null);verifyNoInteractions(orders);
    }
    @Test void arbitraryRequestHeaderIsNotPromotedToConsumerMdc(){
        when(outbox.matches(EVENT,7)).thenReturn(true);var m=message();m.getMessageProperties().setHeader("requestId",REQUEST);m.getMessageProperties().setHeader("Authorization","Bearer secret");
        consumer().consume(m);eventIs(EVENT);assertNull(last().getMDCPropertyMap().get("requestId"));assertFalse(last().getFormattedMessage().contains("secret"));
        for(var meter:registry.getMeters())assertTrue(meter.getId().getTags().stream().noneMatch(t->t.getKey().equals("eventId")||t.getKey().equals("requestId")));
    }
}
