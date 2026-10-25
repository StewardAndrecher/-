package com.campus.market.notification;
import com.campus.market.notification.mq.NotificationMqSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.core.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NotificationMonitorTest {
    final NotificationOverviewService source=mock(NotificationOverviewService.class);
    final AmqpAdmin admin=mock(AmqpAdmin.class);
    @SuppressWarnings("unchecked") final ObjectProvider<AmqpAdmin> connection=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") final ObjectProvider<NotificationMqSettings> names=mock(ObjectProvider.class);
    final NotificationMqSettings settings=new NotificationMqSettings("campus.notification.unit94",100);
    final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    final NotificationMetrics metrics=new NotificationMetrics(registry,true);
    final NotificationMonitor monitor=new NotificationMonitor(source,metrics,connection,names,true,true);
    @BeforeEach void setup(){when(connection.getIfAvailable()).thenReturn(admin);when(names.getIfAvailable()).thenReturn(settings);when(source.overview()).thenReturn(new NotificationOverviewService.Overview(true,3,2,1,1,1,5,90,2,"2026-10-20T20:00"));when(admin.getQueueInfo(anyString())).thenAnswer(call->new QueueInformation(call.getArgument(0),2,1));}
    @AfterEach void close(){registry.close();}
    double value(String name){return registry.get("campus.notification."+name).gauge().value();}
    @Test void databasePollOnlyReadsExistingAggregation(){monitor.pollDatabase();assertEquals(3,value("outbox.pending"));verify(source).overview();verifyNoMoreInteractions(source);verifyNoInteractions(admin);}
    @Test void databaseFailureDoesNotPreventSeparateBrokerSampling(){when(source.overview()).thenThrow(new IllegalStateException());monitor.pollDatabase();monitor.pollBroker();assertEquals(0,value("outbox.poll.up"));assertEquals(1,value("mq.broker.up"));assertTrue(Double.isNaN(value("outbox.pending")));}
    @Test void brokerPollOnlyPassivelyQueriesTwoProjectQueues(){monitor.pollBroker();assertEquals(1,value("mq.broker.up"));verify(admin).getQueueInfo(settings.queue());verify(admin).getQueueInfo(settings.parkingQueue());verifyNoMoreInteractions(admin);verifyNoInteractions(source);}
    @Test void missingQueueClearsTheEntireBrokerSnapshot(){monitor.pollBroker();when(admin.getQueueInfo(settings.parkingQueue())).thenReturn(null);monitor.pollBroker();assertEquals(0,value("mq.broker.up"));assertTrue(Double.isNaN(registry.get("campus.notification.mq.queue.ready").tag("queue","work").gauge().value()));}
    @Test void brokerFailureDoesNotEraseDatabaseSnapshot(){monitor.pollDatabase();when(admin.getQueueInfo(anyString())).thenThrow(new IllegalStateException());monitor.pollBroker();assertEquals(1,value("outbox.poll.up"));assertEquals(3,value("outbox.pending"));assertEquals(0,value("mq.broker.up"));}
    @Test void disabledPollingDoesNoNetworkWork(){var disabled=new NotificationMonitor(source,metrics,connection,names,true,false);disabled.databaseTick();disabled.brokerTick();verifyNoInteractions(source,connection,names,admin);}
    @Test void disabledDeliveryStillAllowsDatabaseMonitoring(){var disabled=new NotificationMonitor(source,metrics,connection,names,false,true);disabled.databaseTick();disabled.brokerTick();disabled.pollBroker();verify(source).overview();verifyNoInteractions(admin,connection,names);}
    @Test void missingConfigurationReportsUnknownBroker(){when(names.getIfAvailable()).thenReturn(null);monitor.pollBroker();assertEquals(0,value("mq.broker.up"));verifyNoInteractions(admin);}
}
