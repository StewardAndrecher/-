package com.campus.market.intent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.util.List;
import static com.campus.market.intent.PurchaseIntentMessageModels.*;
import static com.campus.market.intent.PurchaseIntentModels.Participant;
@Repository
public class PurchaseIntentMessageRepository {
 private final JdbcTemplate jdbc;
 public PurchaseIntentMessageRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 String participantStatus(long id,long userId,boolean lock) {
  var rows=jdbc.query("SELECT status FROM purchase_intents WHERE id=? AND (buyer_id=? OR seller_id=?)"+(lock?" FOR UPDATE":""),(rs,n)->rs.getString(1),id,userId,userId);
  return rows.isEmpty()?null:rows.get(0);
 }
 private static final String SELECT="SELECT m.id,m.intent_id,m.sender_id,m.content,m.created_at,m.is_withdrawn,m.withdrawn_at,u.nickname FROM purchase_intent_messages m JOIN users u ON u.id=m.sender_id ";
 private static final RowMapper<Message> MAPPER=(rs,n)->new Message(rs.getLong("id"),rs.getLong("intent_id"),new Participant(rs.getLong("sender_id"),rs.getString("nickname")),rs.getBoolean("is_withdrawn")?null:rs.getString("content"),rs.getTimestamp("created_at").toLocalDateTime().toString(),rs.getBoolean("is_withdrawn"),rs.getTimestamp("withdrawn_at")==null?null:rs.getTimestamp("withdrawn_at").toLocalDateTime().toString());
 // Parent intention is already locked by send; a plain current read under READ_COMMITTED avoids locking nickname rows.
 Submission existing(long intentId,long senderId,String requestId) {
  var rows=jdbc.query(SELECT+"WHERE m.intent_id=? AND m.sender_id=? AND m.request_id=?",(rs,n)->new Submission(MAPPER.mapRow(rs,n),rs.getString("content")),intentId,senderId,requestId);
  return rows.isEmpty()?null:rows.get(0);
 }
 WithdrawalState lockMessage(long intentId,long messageId) {
  var rows=jdbc.query("SELECT id,sender_id,is_withdrawn FROM purchase_intent_messages WHERE intent_id=? AND id=? FOR UPDATE",(rs,n)->new WithdrawalState(rs.getLong("id"),rs.getLong("sender_id"),rs.getBoolean("is_withdrawn")),intentId,messageId);
  return rows.isEmpty()?null:rows.get(0);
 }
 int withdraw(long intentId,long messageId,long senderId) {
  return jdbc.update("UPDATE purchase_intent_messages SET is_withdrawn=TRUE,withdrawn_at=CURRENT_TIMESTAMP(6) WHERE intent_id=? AND id=? AND sender_id=? AND is_withdrawn=FALSE",intentId,messageId,senderId);
 }
 long insert(long intentId,long senderId,String content,String requestId) {
  var key=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement("INSERT INTO purchase_intent_messages(intent_id,sender_id,content,request_id) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS);s.setLong(1,intentId);s.setLong(2,senderId);s.setString(3,content);s.setString(4,requestId);return s;},key);return key.getKey().longValue();
 }
 Message read(long id) { return jdbc.queryForObject(SELECT+"WHERE m.id=?",MAPPER,id); }
 long count(long intentId) { return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_messages WHERE intent_id=?",Long.class,intentId); }
 List<Message> page(long intentId,int size,long offset) { return jdbc.query(SELECT+"WHERE m.intent_id=? ORDER BY m.created_at ASC,m.id ASC LIMIT ? OFFSET ?",MAPPER,intentId,size,offset); }
 ReadState readState(long intentId,long userId) {
  long cursor=readCursor(intentId,userId);
  long latest=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM purchase_intent_messages WHERE intent_id=?",Long.class,intentId);
  long unread=jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_messages WHERE intent_id=? AND sender_id<>? AND id>? AND is_withdrawn=FALSE",Long.class,intentId,userId,cursor);
  return new ReadState(intentId,cursor,latest,unread);
 }
 long readCursor(long intentId,long userId) {
  var rows=jdbc.query("SELECT last_read_message_id FROM purchase_intent_message_reads WHERE intent_id=? AND user_id=?",(rs,n)->rs.getLong(1),intentId,userId);
  return rows.isEmpty()?0:rows.get(0);
 }
 boolean containsMessage(long intentId,long messageId) {
  return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_messages WHERE intent_id=? AND id=?",Long.class,intentId,messageId)==1;
 }
 // Caller holds the parent intention lock; a replay must not change the cursor or timestamp.
 void advanceReadCursor(long intentId,long userId,long messageId) {
  long current=readCursor(intentId,userId);
  if(messageId<=current)return;
  if(current==0)jdbc.update("INSERT INTO purchase_intent_message_reads(intent_id,user_id,last_read_message_id) VALUES(?,?,?)",intentId,userId,messageId);
  else jdbc.update("UPDATE purchase_intent_message_reads SET last_read_message_id=?,updated_at=CURRENT_TIMESTAMP(6) WHERE intent_id=? AND user_id=?",messageId,intentId,userId);
 }

}
