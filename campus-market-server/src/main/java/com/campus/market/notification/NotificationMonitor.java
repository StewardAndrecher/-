package com.campus.market.notification;

import com.campus.market.notification.mq.NotificationMqSettings;
import java.util.HashMap;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.monitoring.enabled", havingValue="true")
public class NotificationMonitor {
    private final NotificationOverviewService source;
    private final NotificationMetrics metrics;
    private final ObjectProvider<AmqpAdmin> admin;
    private final ObjectProvider<NotificationMqSettings> settings;
    private final boolean deliveryEnabled,pollEnabled;
    public NotificationMonitor(NotificationOverviewService source,NotificationMetrics metrics,
            ObjectProvider<AmqpAdmin> admin,ObjectProvider<NotificationMqSettings> settings,
            @Value("${market.notifications.mq.enabled:false}") boolean deliveryEnabled,
            @Value("${market.monitoring.poll-enabled:true}") boolean pollEnabled) {
        this.source=source;this.metrics=metrics;this.admin=admin;this.settings=settings;
        this.deliveryEnabled=deliveryEnabled;this.pollEnabled=pollEnabled;
    }
    @Scheduled(scheduler="notificationMonitorTaskScheduler",initialDelayString="${market.notifications.monitor.initial-delay-ms:1000}",fixedDelayString="${market.notifications.monitor.poll-delay-ms:15000}")
    public void databaseTick() {if(pollEnabled)pollDatabase();}
    @Scheduled(scheduler="notificationMonitorTaskScheduler",initialDelayString="${market.notifications.monitor.initial-delay-ms:1000}",fixedDelayString="${market.notifications.monitor.poll-delay-ms:15000}")
    public void brokerTick() {if(pollEnabled&&deliveryEnabled)pollBroker();}
    public void pollDatabase() {
        try {metrics.database(source.overview());}
        catch(RuntimeException unavailable) {metrics.databaseFailed();}
    }
    public void pollBroker() {
        if(!deliveryEnabled)return;
        try {
            var connection=admin.getIfAvailable();var names=settings.getIfAvailable();
            if(connection==null||names==null)throw new IllegalStateException("通知MQ未配置");
            var ready=new HashMap<String,Double>();var consumers=new HashMap<String,Double>();
            for(var queue:java.util.Map.of("work",names.queue(),"parking",names.parkingQueue()).entrySet()) {
                var info=connection.getQueueInfo(queue.getValue());
                if(info==null)throw new IllegalStateException("通知队列不可读取");
                ready.put(queue.getKey(),(double)info.getMessageCount());
                consumers.put(queue.getKey(),(double)info.getConsumerCount());
            }
            metrics.broker(ready,consumers);
        } catch(RuntimeException unavailable) {metrics.brokerFailed();}
    }
}
