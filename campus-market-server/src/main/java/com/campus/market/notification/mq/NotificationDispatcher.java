package com.campus.market.notification.mq;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.campus.market.notification.NotificationOutbox;
@Component
@ConditionalOnProperty(name="market.notifications.mq.enabled",havingValue="true")
public class NotificationDispatcher {
 private final NotificationOutbox outbox;private final NotificationSender sender;private static final Logger log=LoggerFactory.getLogger(NotificationDispatcher.class);private long lastWarning;
 @Value("${market.notifications.mq.dispatch-enabled:true}")private boolean enabled;
 public NotificationDispatcher(NotificationOutbox outbox,NotificationSender sender){this.outbox=outbox;this.sender=sender;}
 @Scheduled(scheduler="notificationTaskScheduler",fixedDelayString="${market.notifications.mq.dispatch-delay-ms:1000}")
 public void tick(){if(!enabled)return;try{for(int i=0;i<10&&!Thread.currentThread().isInterrupted();i++)if(!dispatchOne())break;}catch(RuntimeException e){warn();}}
 public boolean dispatchOne(){var job=outbox.claim();if(job==null)return false;
  try{sender.send(job);outbox.published(job);}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();outbox.failed(job);warn();}return true;
 }
 private synchronized void warn(){long now=System.nanoTime();if(now-lastWarning>5_000_000_000L){lastWarning=now;log.warn("站内通知投递暂未确认，任务保留等待重试");}}
}
