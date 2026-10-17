package com.campus.market.intent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.campus.market.intent.PurchaseIntentUnreadModels.*;
@Repository
public class PurchaseIntentUnreadRepository {
 private final JdbcTemplate jdbc;
 public PurchaseIntentUnreadRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
 List<Item> counts(long userId,PurchaseIntentRole role,List<Long> ids) {
  if(ids.isEmpty())return List.of();
  var args=new ArrayList<Object>();args.add(userId);args.add(userId);args.add(userId);args.addAll(ids);
  // Values stay bound parameters. The only SQL identifier comes from the closed role enum.
  String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
  String sql="SELECT i.id,COUNT(m.id) unread_count FROM purchase_intents i "
   +"LEFT JOIN purchase_intent_message_reads r ON r.intent_id=i.id AND r.user_id=? "
   +"LEFT JOIN purchase_intent_messages m ON m.intent_id=i.id AND m.sender_id<>? "
   +"AND m.id>COALESCE(r.last_read_message_id,0) AND m.is_withdrawn=FALSE "
   +"WHERE "+role.column()+"=? AND i.id IN ("+placeholders+") GROUP BY i.id";
  return jdbc.query(sql,(rs,n)->new Item(rs.getLong("id"),rs.getLong("unread_count")),args.toArray());
 }
 Summary summary(long userId,PurchaseIntentRole role) {
  String sql="SELECT COALESCE(SUM(t.unread_count),0) total_messages,"
    +"COALESCE(SUM(CASE WHEN t.unread_count>0 THEN 1 ELSE 0 END),0) unread_intents "
    +"FROM (SELECT i.id,COUNT(m.id) unread_count FROM purchase_intents i "
    +"LEFT JOIN purchase_intent_message_reads r ON r.intent_id=i.id AND r.user_id=? "
    +"LEFT JOIN purchase_intent_messages m ON m.intent_id=i.id AND m.sender_id<>? "
    +"AND m.id>COALESCE(r.last_read_message_id,0) AND m.is_withdrawn=FALSE "
    +"WHERE "+role.column()+"=? GROUP BY i.id) t";
  return jdbc.queryForObject(sql,(rs,n)->new Summary(role.name(),rs.getLong("total_messages"),rs.getLong("unread_intents")),userId,userId,userId);
 }

}
