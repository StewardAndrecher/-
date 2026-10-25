package com.campus.market.notification;

import io.micrometer.core.instrument.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.monitoring.enabled", havingValue="true")
public class NotificationMetrics {
    private record Database(boolean up, NotificationOverviewService.Overview value, long at) {}
    private record Broker(boolean up, Map<String,Double> ready, Map<String,Double> consumers, long at) {}
    private final AtomicReference<Database> database = new AtomicReference<>(new Database(false,null,0));
    private final AtomicReference<Broker> broker = new AtomicReference<>(new Broker(false,Map.of(),Map.of(),0));
    private final LongSupplier clock;
    @Autowired
    public NotificationMetrics(MeterRegistry registry,
            @Value("${market.notifications.mq.enabled:false}") boolean enabled) {
        this(registry,enabled,System::nanoTime);
    }
    public NotificationMetrics(MeterRegistry registry, boolean enabled, LongSupplier clock) {
        this.clock=clock;
        Gauge.builder("campus.notification.delivery.enabled",()->enabled?1:0).register(registry);
        Gauge.builder("campus.notification.outbox.poll.up",()->database.get().up()?1:0).register(registry);
        Gauge.builder("campus.notification.outbox.sample.age.seconds",()->age(database.get().at())).register(registry);
        gauge(registry,"pending",v->v.pending());
        gauge(registry,"unconfirmed",v->v.unconfirmed());
        gauge(registry,"awaiting.delivery",v->v.awaitingDelivery());
        gauge(registry,"retrying",v->v.retrying());
        gauge(registry,"leased",v->v.leased());
        gauge(registry,"delivered",v->v.delivered());
        gauge(registry,"oldest.seconds",v->v.oldestPendingSeconds());
        gauge(registry,"max.attempts",v->v.maxPendingAttempts());
        Gauge.builder("campus.notification.mq.broker.up",()->enabled?(broker.get().up()?1:0):Double.NaN).register(registry);
        Gauge.builder("campus.notification.mq.sample.age.seconds",()->age(broker.get().at())).register(registry);
        for(String queue:new String[]{"work","parking"}) {
            Gauge.builder("campus.notification.mq.queue.ready",()->broker.get().ready().getOrDefault(queue,Double.NaN)).tag("queue",queue).register(registry);
            Gauge.builder("campus.notification.mq.queue.consumers",()->broker.get().consumers().getOrDefault(queue,Double.NaN)).tag("queue",queue).register(registry);
        }
    }
    private void gauge(MeterRegistry registry,String name,ToDoubleFunction<NotificationOverviewService.Overview> value) {
        Gauge.builder("campus.notification.outbox."+name,()->{
            var snapshot=database.get();return snapshot.value()==null?Double.NaN:value.applyAsDouble(snapshot.value());
        }).register(registry);
    }
    private double age(long at) {return at==0?Double.NaN:Math.max(0,clock.getAsLong()-at)/1_000_000_000.0;}
    public void database(NotificationOverviewService.Overview value) {database.set(new Database(true,value,clock.getAsLong()));}
    public void databaseFailed() {database.updateAndGet(old->new Database(false,null,old.at()));}
    public void broker(Map<String,Double> ready,Map<String,Double> consumers) {
        if(!ready.keySet().equals(java.util.Set.of("work","parking"))||!consumers.keySet().equals(ready.keySet()))throw new IllegalArgumentException("队列采样不完整");
        broker.set(new Broker(true,Map.copyOf(ready),Map.copyOf(consumers),clock.getAsLong()));
    }
    public void brokerFailed() {broker.updateAndGet(old->new Broker(false,Map.of(),Map.of(),old.at()));}
}
