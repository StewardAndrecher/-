package com.campus.market.order.mq;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class OrderTimeoutOutbox {
 public record Job(String eventId,long orderId,String leaseToken,int attempts) {}
 private final JdbcTemplate jdbc;
 public OrderTimeoutOutbox(JdbcTemplate jdbc){this.jdbc=jdbc;}
 /** 只在短事务中抢占，发送网络请求时不持有数据库锁。过期租约可被恢复。 */
 @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
 public Job claim() {
  var rows=jdbc.queryForList("SELECT event_id,order_id,attempts FROM order_timeout_outbox WHERE published_at IS NULL AND next_attempt_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
  if(rows.isEmpty())return null;
  var row=rows.get(0);String id=(String)row.get("event_id"),token=UUID.randomUUID().toString();
  int attempt=(int)Math.min(2147483647L,((Number)row.get("attempts")).longValue()+1);
  jdbc.update("UPDATE order_timeout_outbox SET attempts=?,lease_token=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE event_id=?",attempt,token,id);
  return new Job(id,((Number)row.get("order_id")).longValue(),token,attempt);
 }
 public boolean published(Job job){return jdbc.update("UPDATE order_timeout_outbox SET published_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE event_id=? AND lease_token=? AND published_at IS NULL",job.eventId(),job.leaseToken())==1;}
 public boolean failed(Job job){
  int seconds=1<<Math.min(job.attempts(),6);
  return jdbc.update("UPDATE order_timeout_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)+INTERVAL ? SECOND,lease_token=NULL,lease_until=NULL,last_error='DELIVERY_UNCONFIRMED' WHERE event_id=? AND lease_token=? AND published_at IS NULL",seconds,job.eventId(),job.leaseToken())==1;
 }
 public boolean matches(String eventId,long orderId){return jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE event_id=? AND order_id=?",Long.class,eventId,orderId)==1;}
}
