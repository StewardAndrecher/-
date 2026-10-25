package com.campus.market.notification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class NotificationMetricsTest {
    final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    final AtomicLong now=new AtomicLong(1);
    final NotificationMetrics metrics=new NotificationMetrics(registry,true,now::get);
    final NotificationOverviewService.Overview sample=new NotificationOverviewService.Overview(true,4,3,1,2,1,7,120,3,"2026-10-20T20:00");
    double value(String suffix){return registry.get("campus.notification."+suffix).gauge().value();}
    @AfterEach void close(){registry.close();}
    @Test void unsampledValuesAreUnknownRatherThanZero(){assertEquals(0,value("outbox.poll.up"));assertTrue(Double.isNaN(value("outbox.pending")));assertTrue(Double.isNaN(value("outbox.sample.age.seconds")));assertTrue(Double.isNaN(registry.get("campus.notification.mq.queue.ready").tag("queue","work").gauge().value()));}
    @Test void databaseSnapshotExportsExpectedValues(){metrics.database(sample);assertEquals(4,value("outbox.pending"));assertEquals(3,value("outbox.unconfirmed"));assertEquals(1,value("outbox.awaiting.delivery"));assertEquals(2,value("outbox.retrying"));assertEquals(1,value("outbox.leased"));assertEquals(7,value("outbox.delivered"));assertEquals(120,value("outbox.oldest.seconds"));assertEquals(3,value("outbox.max.attempts"));assertEquals(1,value("outbox.poll.up"));}
    @Test void databaseFailureClearsOldSuccessValues(){metrics.database(sample);metrics.databaseFailed();assertEquals(0,value("outbox.poll.up"));assertTrue(Double.isNaN(value("outbox.pending")));}
    @Test void ageUsesMonotonicLastSuccessfulSample(){metrics.database(sample);now.addAndGet(5_000_000_000L);metrics.databaseFailed();assertEquals(5,value("outbox.sample.age.seconds"));metrics.database(sample);assertEquals(0,value("outbox.sample.age.seconds"));}
    @Test void brokerSnapshotsAreCopiedAndHaveBoundedQueueLabels(){var ready=new HashMap<>(Map.of("work",2d,"parking",1d));metrics.broker(ready,Map.of("work",1d,"parking",0d));ready.put("work",99d);assertEquals(2,registry.get("campus.notification.mq.queue.ready").tag("queue","work").gauge().value());assertEquals(1,value("mq.broker.up"));assertEquals(2,registry.find("campus.notification.mq.queue.ready").gauges().size());}
    @Test void brokerFailureClearsValuesButPreservesLastSuccessAge(){metrics.broker(Map.of("work",2d,"parking",1d),Map.of("work",1d,"parking",0d));now.addAndGet(2_000_000_000L);metrics.brokerFailed();assertEquals(0,value("mq.broker.up"));assertEquals(2,value("mq.sample.age.seconds"));assertTrue(Double.isNaN(registry.get("campus.notification.mq.queue.ready").tag("queue","work").gauge().value()));}
    @Test void disabledDeliveryIsExplicitAndBrokerStateUnknown(){var disabledRegistry=new SimpleMeterRegistry();try{new NotificationMetrics(disabledRegistry,false,now::get);assertEquals(0,disabledRegistry.get("campus.notification.delivery.enabled").gauge().value());assertTrue(Double.isNaN(disabledRegistry.get("campus.notification.mq.broker.up").gauge().value()));}finally{disabledRegistry.close();}}
    @Test void incompleteBrokerSnapshotIsRejectedWithoutPublishingPartialData(){assertThrows(IllegalArgumentException.class,()->metrics.broker(Map.of("work",1d),Map.of("work",1d)));assertEquals(0,value("mq.broker.up"));assertTrue(Double.isNaN(value("mq.sample.age.seconds")));}
}
