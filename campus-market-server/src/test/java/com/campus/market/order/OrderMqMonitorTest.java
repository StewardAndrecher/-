package com.campus.market.order;
import com.campus.market.order.mq.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderMqMonitorTest {
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);final AmqpAdmin admin=mock(AmqpAdmin.class);final SimpleMeterRegistry registry=new SimpleMeterRegistry();final OrderMqMetrics metrics=new OrderMqMetrics(registry);final OrderMqSettings settings=new OrderMqSettings("campus.order.unit75",200,100);final OrderMqMonitor monitor=new OrderMqMonitor(jdbc,admin,settings,metrics);
 @BeforeEach void good(){when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending",3L,"retrying",2L,"leased",1L,"oldest",20d));when(admin.getQueueInfo(anyString())).thenAnswer(call->new QueueInformation(call.getArgument(0),4,1));}
 double value(String name){return registry.get(name).gauge().value();}
 @Test void pollReportsDatabaseAndPassiveQueueSnapshot(){monitor.poll();assertEquals(3,value("campus.order.outbox.pending"));assertEquals(2,value("campus.order.outbox.retrying"));assertEquals(1,value("campus.order.outbox.leased"));assertEquals(1,value("campus.order.mq.broker.up"));assertEquals(4,registry.get("campus.order.mq.queue.ready").tag("queue","delay").gauge().value());}
 @Test void databaseFailureDoesNotHideBrokerSnapshot(){when(jdbc.queryForMap(anyString())).thenThrow(new IllegalStateException());monitor.poll();assertEquals(0,value("campus.order.outbox.poll.up"));assertEquals(1,value("campus.order.mq.broker.up"));assertTrue(Double.isNaN(value("campus.order.outbox.pending")));}
 @Test void brokerFailureDoesNotHideDatabaseSnapshot(){when(admin.getQueueInfo(anyString())).thenThrow(new IllegalStateException());monitor.poll();assertEquals(1,value("campus.order.outbox.poll.up"));assertEquals(0,value("campus.order.mq.broker.up"));assertEquals(3,value("campus.order.outbox.pending"));}
 @Test void missingQueueIsUnknownInsteadOfEmpty(){when(admin.getQueueInfo(settings.parkingQueue())).thenReturn(null);monitor.poll();assertEquals(0,value("campus.order.mq.broker.up"));assertTrue(Double.isNaN(registry.get("campus.order.mq.queue.ready").tag("queue","parking").gauge().value()));}
 @Test void pollDoesNotPublishPurgeOrMutate(){monitor.poll();verify(jdbc).queryForMap(anyString());verifyNoMoreInteractions(jdbc);verify(admin).getQueueInfo(settings.delayQueue());verify(admin).getQueueInfo(settings.workQueue());verify(admin).getQueueInfo(settings.parkingQueue());verifyNoMoreInteractions(admin);}
 @Test void disabledTickDoesNoNetworkWork(){monitor.tick();verifyNoInteractions(jdbc,admin);}
}
