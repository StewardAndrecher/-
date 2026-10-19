package com.campus.market.order;
import com.campus.market.order.mq.OrderMqMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OrderMqMetricsTest {
 final SimpleMeterRegistry registry=new SimpleMeterRegistry();final AtomicLong clock=new AtomicLong(1_000_000_000L);final OrderMqMetrics metrics=new OrderMqMetrics(registry,clock::get);
 double gauge(String name){return registry.get(name).gauge().value();}
 @Test void unsampledValuesAreUnknown(){assertEquals(0,gauge("campus.order.outbox.poll.up"));assertTrue(Double.isNaN(gauge("campus.order.outbox.pending")));assertTrue(Double.isNaN(gauge("campus.order.mq.sample.age.seconds")));}
 @Test void publishResultsAndDurationAreSeparate(){metrics.published(OrderMqMetrics.Publish.CONFIRMED,2_000_000);metrics.published(OrderMqMetrics.Publish.FAILED,3_000_000);assertEquals(1,registry.get("campus.order.mq.publish").tag("result","confirmed").counter().count());assertEquals(1,registry.get("campus.order.mq.publish").tag("result","failed").counter().count());assertEquals(.002,registry.get("campus.order.mq.publish.duration").tag("result","confirmed").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS),.000001);}
 @Test void consumeCountsEveryAttemptByFiniteOutcome(){for(var result:OrderMqMetrics.Consume.values())metrics.consumed(result,10);for(String result:new String[]{"closed","noop","rejected","failed"})assertEquals(1,registry.get("campus.order.mq.consume").tag("result",result).counter().count());}
 @Test void labelsHaveNoDynamicIdentity(){assertTrue(registry.getMeters().stream().flatMap(m->m.getId().getTags().stream()).allMatch(tag->tag.getKey().equals("result")||tag.getKey().equals("queue")));}
 @Test void emptyOutboxIsKnownZero(){metrics.outbox(0,0,0,0);assertEquals(1,gauge("campus.order.outbox.poll.up"));assertEquals(0,gauge("campus.order.outbox.pending"));assertEquals(0,gauge("campus.order.outbox.oldest.seconds"));}
 @Test void failedOutboxDoesNotPretendEmpty(){metrics.outbox(5,2,1,60);metrics.outboxFailed();assertEquals(0,gauge("campus.order.outbox.poll.up"));assertTrue(Double.isNaN(gauge("campus.order.outbox.pending")));}
 @Test void brokerFailureClearsQueueMeasurements(){metrics.broker(Map.of("delay",2d,"timeout",0d,"parking",1d),Map.of("delay",0d,"timeout",1d,"parking",0d));metrics.brokerFailed();assertEquals(0,gauge("campus.order.mq.broker.up"));assertTrue(Double.isNaN(registry.get("campus.order.mq.queue.ready").tag("queue","parking").gauge().value()));}
 @Test void sampleAgeUsesMonotonicTimeAndSurvivesFailure(){metrics.outbox(1,0,0,2);clock.addAndGet(5_000_000_000L);metrics.outboxFailed();assertEquals(5,gauge("campus.order.outbox.sample.age.seconds"));}
 @Test void restartCreatesNewCounters(){metrics.published(OrderMqMetrics.Publish.CONFIRMED,1);var fresh=new SimpleMeterRegistry();new OrderMqMetrics(fresh);assertEquals(0,fresh.get("campus.order.mq.publish").tag("result","confirmed").counter().count());}
}
