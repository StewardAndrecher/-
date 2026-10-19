package com.campus.market.order.mq;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.orders.mq.enabled",havingValue="true")
public class OrderMqMetrics {
 public enum Publish { CONFIRMED, FAILED }
 public enum Consume { CLOSED, NOOP, REJECTED, FAILED }
 public record Outbox(boolean up,double pending,double retrying,double leased,double oldestSeconds,long sampledAt) {}
 public record Broker(boolean up,Map<String,Double> ready,Map<String,Double> consumers,long sampledAt) {}
 private final Map<Publish,Counter> publishes=new EnumMap<>(Publish.class);
 private final Map<Publish,Timer> publishTimers=new EnumMap<>(Publish.class);
 private final Map<Consume,Counter> consumes=new EnumMap<>(Consume.class);
 private final Map<Consume,Timer> consumeTimers=new EnumMap<>(Consume.class);
 private final AtomicReference<Outbox> outbox=new AtomicReference<>(new Outbox(false,Double.NaN,Double.NaN,Double.NaN,Double.NaN,0));
 private final AtomicReference<Broker> broker=new AtomicReference<>(new Broker(false,unknown(),unknown(),0));
 private final LongSupplier nanoTime;
 private static Map<String,Double> unknown(){return Map.of("delay",Double.NaN,"timeout",Double.NaN,"parking",Double.NaN);}
 @Autowired public OrderMqMetrics(MeterRegistry registry){this(registry,System::nanoTime);}
 public OrderMqMetrics(MeterRegistry registry,LongSupplier nanoTime){
  this.nanoTime=nanoTime;
  for(var result:Publish.values()){
   var tags=Tags.of("result",result.name().toLowerCase(Locale.ROOT));
   publishes.put(result,Counter.builder("campus.order.mq.publish").tags(tags).register(registry));
   publishTimers.put(result,Timer.builder("campus.order.mq.publish.duration").tags(tags).register(registry));
  }
  for(var result:Consume.values()){
   var tags=Tags.of("result",result.name().toLowerCase(Locale.ROOT));
   consumes.put(result,Counter.builder("campus.order.mq.consume").tags(tags).register(registry));
   consumeTimers.put(result,Timer.builder("campus.order.mq.consume.duration").tags(tags).register(registry));
  }
  Gauge.builder("campus.order.outbox.poll.up",this,m->m.outbox.get().up()?1:0).register(registry);
  Gauge.builder("campus.order.outbox.pending",this,m->m.outbox.get().pending()).register(registry);
  Gauge.builder("campus.order.outbox.retrying",this,m->m.outbox.get().retrying()).register(registry);
  Gauge.builder("campus.order.outbox.leased",this,m->m.outbox.get().leased()).register(registry);
  Gauge.builder("campus.order.outbox.oldest.seconds",this,m->m.outbox.get().oldestSeconds()).register(registry);
  Gauge.builder("campus.order.outbox.sample.age.seconds",this,m->m.age(m.outbox.get().sampledAt())).register(registry);
  Gauge.builder("campus.order.mq.broker.up",this,m->m.broker.get().up()?1:0).register(registry);
  Gauge.builder("campus.order.mq.sample.age.seconds",this,m->m.age(m.broker.get().sampledAt())).register(registry);
  for(String queue:List.of("delay","timeout","parking")){
   Gauge.builder("campus.order.mq.queue.ready",this,m->m.broker.get().ready().get(queue)).tag("queue",queue).register(registry);
   Gauge.builder("campus.order.mq.queue.consumers",this,m->m.broker.get().consumers().get(queue)).tag("queue",queue).register(registry);
  }
 }
 private double age(long at){return at==0?Double.NaN:Math.max(0,nanoTime.getAsLong()-at)/1_000_000_000.0;}
 public void published(Publish result,long nanos){publishes.get(result).increment();publishTimers.get(result).record(Math.max(0,nanos),TimeUnit.NANOSECONDS);}
 public void consumed(Consume result,long nanos){consumes.get(result).increment();consumeTimers.get(result).record(Math.max(0,nanos),TimeUnit.NANOSECONDS);}
 public void outbox(double pending,double retrying,double leased,double oldestSeconds){outbox.set(new Outbox(true,pending,retrying,leased,oldestSeconds,nanoTime.getAsLong()));}
 public void outboxFailed(){outbox.updateAndGet(old->new Outbox(false,Double.NaN,Double.NaN,Double.NaN,Double.NaN,old.sampledAt()));}
 public void broker(Map<String,Double> ready,Map<String,Double> consumers){broker.set(new Broker(true,Map.copyOf(ready),Map.copyOf(consumers),nanoTime.getAsLong()));}
 public void brokerFailed(){broker.updateAndGet(old->new Broker(false,unknown(),unknown(),old.sampledAt()));}
}
