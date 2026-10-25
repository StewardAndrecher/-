package com.campus.market.notification;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class NotificationOutbox {
 public record Job(String eventId,String leaseToken,int attempts){}
 private final JdbcTemplate jdbc;
 public NotificationOutbox(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void enqueue(String source,long recipient,String category,long resource,String title,String body){
  if(source==null||!source.matches("[A-Z0-9:_-]{1,100}")||recipient<1||resource<1||resource>9007199254740991L||!Set.of("ORDER","REPORT","MODERATION").contains(category)||title==null||title.isBlank()||title.length()>80||body==null||body.isBlank()||body.length()>500)throw new IllegalArgumentException("通知内容无效");
  jdbc.update("INSERT INTO notification_outbox(event_id,source_key,recipient_id,category,resource_id,title,body) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),source,recipient,category,resource,title,body);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
 public Job claim(){
  var rows=jdbc.queryForList("SELECT event_id,attempts FROM notification_outbox WHERE delivered_at IS NULL AND next_attempt_at<=CURRENT_TIMESTAMP(6) AND (published_at IS NULL OR published_at<=CURRENT_TIMESTAMP(6)-INTERVAL 30 SECOND) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
  if(rows.isEmpty())return null;var row=rows.get(0);String event=(String)row.get("event_id"),lease=UUID.randomUUID().toString();int attempts=(int)Math.min(Integer.MAX_VALUE,((Number)row.get("attempts")).longValue()+1);
  jdbc.update("UPDATE notification_outbox SET attempts=?,lease_token=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE event_id=?",attempts,lease,event);return new Job(event,lease,attempts);
 }
 public boolean published(Job j){return jdbc.update("UPDATE notification_outbox SET published_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE event_id=? AND lease_token=?",j.eventId(),j.leaseToken())==1;}
 public boolean failed(Job j){int seconds=1<<Math.min(j.attempts(),6);return jdbc.update("UPDATE notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)+INTERVAL ? SECOND,lease_token=NULL,lease_until=NULL,last_error='DELIVERY_UNCONFIRMED' WHERE event_id=? AND lease_token=?",seconds,j.eventId(),j.leaseToken())==1;}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public boolean deliver(String eventId){
  var rows=jdbc.queryForList("SELECT delivered_at FROM notification_outbox WHERE event_id=? FOR UPDATE",eventId);
  if(rows.isEmpty())throw new UnknownNotificationEvent();
  if(rows.get(0).get("delivered_at")!=null)return false;
  jdbc.update("INSERT INTO user_notifications(event_id,recipient_id,category,resource_id,title,body,occurred_at) SELECT event_id,recipient_id,category,resource_id,title,body,created_at FROM notification_outbox WHERE event_id=?",eventId);
  jdbc.update("UPDATE notification_outbox SET delivered_at=CURRENT_TIMESTAMP(6) WHERE event_id=?",eventId);return true;
 }
 public static class UnknownNotificationEvent extends RuntimeException{}
}
