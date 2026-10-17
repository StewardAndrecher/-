package com.campus.market.intent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.util.List;
import static com.campus.market.intent.PurchaseIntentModels.*;

@Repository
public class PurchaseIntentRepository {
 private final JdbcTemplate jdbc;
 public PurchaseIntentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 // Only lock the product row. Joining users in this locking read would lock unrelated account rows.
 ProductSnapshot lockProduct(long id) {
  var rows=jdbc.query("SELECT id,title,price_in_cents,version,seller_id,status FROM products WHERE id=? FOR UPDATE",
    (rs,n)->new ProductSnapshot(rs.getLong("id"),rs.getString("title"),rs.getLong("price_in_cents"),rs.getLong("version"),(Long)rs.getObject("seller_id"),rs.getString("status")),id);
  return rows.isEmpty()?null:rows.get(0);
 }
 Long existingId(long productId,long buyerId) {
  var ids=jdbc.query("SELECT id FROM purchase_intents WHERE product_id=? AND buyer_id=? FOR UPDATE",(rs,n)->rs.getLong(1),productId,buyerId);
  return ids.isEmpty()?null:ids.get(0);
 }
 long insert(ProductSnapshot p,long buyerId) {
  var key=new GeneratedKeyHolder();
  jdbc.update(connection->{var s=connection.prepareStatement("INSERT INTO purchase_intents(product_id,buyer_id,seller_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
   s.setLong(1,p.id());s.setLong(2,buyerId);s.setLong(3,p.sellerId());s.setString(4,p.title());s.setLong(5,p.priceInCents());s.setLong(6,p.version());return s;},key);
  return key.getKey().longValue();
 }
 OwnedState lockOwnedIntent(long id,long buyerId) {
  var rows=jdbc.query("SELECT id,status FROM purchase_intents WHERE id=? AND buyer_id=? FOR UPDATE",
    (rs,n)->new OwnedState(rs.getLong("id"),rs.getString("status")),id,buyerId);
  return rows.isEmpty()?null:rows.get(0);
 }
 int cancelOwned(long id,long buyerId) {
  return jdbc.update("UPDATE purchase_intents SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=? AND buyer_id=? AND status='PENDING'",id,buyerId);
 }
 Long sellerIntentProductId(long id,long sellerId) {
  var ids=jdbc.query("SELECT product_id FROM purchase_intents WHERE id=? AND seller_id=?",(rs,n)->rs.getLong(1),id,sellerId);
  return ids.isEmpty()?null:ids.get(0);
 }
 OwnedState lockSellerIntent(long id,long sellerId) {
  var rows=jdbc.query("SELECT id,status FROM purchase_intents WHERE id=? AND seller_id=? FOR UPDATE",
    (rs,n)->new OwnedState(rs.getLong("id"),rs.getString("status")),id,sellerId);
  return rows.isEmpty()?null:rows.get(0);
 }
 int acceptOwned(long id,long sellerId) {
  return jdbc.update("UPDATE purchase_intents SET status='ACCEPTED',accepted_at=CURRENT_TIMESTAMP(6) WHERE id=? AND seller_id=? AND status='PENDING'",id,sellerId);
 }
 private static final String SELECT="SELECT i.*,b.nickname buyer_nickname,s.nickname seller_nickname FROM purchase_intents i JOIN users b ON b.id=i.buyer_id JOIN users s ON s.id=i.seller_id ";
 private static final RowMapper<Intent> MAPPER=(rs,n)->new Intent(rs.getLong("id"),rs.getLong("product_id"),rs.getString("product_title_snapshot"),rs.getLong("price_in_cents_snapshot"),rs.getLong("product_version_snapshot"),new Participant(rs.getLong("buyer_id"),rs.getString("buyer_nickname")),new Participant(rs.getLong("seller_id"),rs.getString("seller_nickname")),rs.getString("status"),rs.getTimestamp("created_at").toLocalDateTime().toString(),rs.getTimestamp("cancelled_at")==null?null:rs.getTimestamp("cancelled_at").toLocalDateTime().toString(),rs.getTimestamp("accepted_at")==null?null:rs.getTimestamp("accepted_at").toLocalDateTime().toString());
 Intent read(long id) { return jdbc.queryForObject(SELECT+"WHERE i.id=?",MAPPER,id); }
 Intent readParticipant(long id,long userId) {
  var rows=jdbc.query(SELECT+"WHERE i.id=? AND (i.buyer_id=? OR i.seller_id=?)",MAPPER,id,userId,userId);
  return rows.isEmpty()?null:rows.get(0);
 }

 private java.util.ArrayList<Object> filterArguments(long userId,PurchaseIntentStatusFilter status) {
  var args=new java.util.ArrayList<Object>();args.add(userId);
  if(status!=PurchaseIntentStatusFilter.ALL)args.add(status.value());
  return args;
 }
 // One aggregate query for this user and role, independent of the selected status/page.
 Summary summary(long userId,PurchaseIntentRole role) {
  return jdbc.queryForObject("SELECT COUNT(*) total,"
    +"COALESCE(SUM(CASE WHEN i.status='PENDING' THEN 1 ELSE 0 END),0) pending,"
    +"COALESCE(SUM(CASE WHEN i.status='ACCEPTED' THEN 1 ELSE 0 END),0) accepted,"
    +"COALESCE(SUM(CASE WHEN i.status='CANCELLED' THEN 1 ELSE 0 END),0) cancelled "
    +"FROM purchase_intents i WHERE "+role.column()+"=?",
    (rs,n)->new Summary(rs.getLong("total"),rs.getLong("pending"),rs.getLong("accepted"),rs.getLong("cancelled")),userId);
 }

 // EXISTS filters intentions without multiplying rows when several messages are unread.
 private static final String UNREAD=" AND EXISTS (SELECT 1 FROM purchase_intent_messages m "
   +"LEFT JOIN purchase_intent_message_reads r ON r.intent_id=m.intent_id AND r.user_id=? "
   +"WHERE m.intent_id=i.id AND m.sender_id<>? AND m.is_withdrawn=FALSE "
   +"AND m.id>COALESCE(r.last_read_message_id,0))";
 long unreadCount(long userId,PurchaseIntentRole role,PurchaseIntentStatusFilter status) {
  var args=filterArguments(userId,status);args.add(userId);args.add(userId);
  return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intents i WHERE "+role.column()+"=?"+status.clause()+UNREAD,Long.class,args.toArray());
 }
 List<Intent> page(long userId,PurchaseIntentRole role,PurchaseIntentStatusFilter status,int size,long offset,boolean unreadOnly,PurchaseIntentSort sort) {
  var args=filterArguments(userId,status);
  if(unreadOnly){args.add(userId);args.add(userId);}
  if(sort==PurchaseIntentSort.RECENT_MESSAGE)args.add(userId);
  args.add(size);args.add(offset);
  return jdbc.query(SELECT+"WHERE "+role.column()+"=?"+status.clause()+(unreadOnly?UNREAD:"")+" ORDER BY "+sort.orderBy()+" LIMIT ? OFFSET ?",MAPPER,args.toArray());
 }

 // Fetch current-page metadata in one query, including empty conversations via LEFT JOIN.
 List<ReceivedMessageTime> receivedTimes(long userId,PurchaseIntentRole role,List<Intent> items) {
  if(items.isEmpty())return List.of();
  var args=new java.util.ArrayList<Object>();args.add(userId);args.add(userId);
  for(var item:items)args.add(item.id());
  var placeholders=String.join(",",java.util.Collections.nCopies(items.size(),"?"));
  var rows=jdbc.query("SELECT i.id,MAX(m.created_at) latest_received_at FROM purchase_intents i "
    +"LEFT JOIN purchase_intent_messages m ON m.intent_id=i.id AND m.sender_id<>? AND m.is_withdrawn=FALSE "
    +"WHERE "+role.column()+"=? AND i.id IN ("+placeholders+") GROUP BY i.id",
    (rs,n)->{var at=rs.getTimestamp("latest_received_at");return new ReceivedMessageTime(rs.getLong("id"),at==null?null:at.toLocalDateTime().toString());},args.toArray());
  var byId=new java.util.HashMap<Long,ReceivedMessageTime>();for(var row:rows)byId.put(row.intentId(),row);
  return items.stream().map(item->java.util.Objects.requireNonNull(byId.get(item.id()),"Missing owned intention metadata")).toList();
 }
}
