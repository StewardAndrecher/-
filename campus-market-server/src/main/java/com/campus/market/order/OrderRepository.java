package com.campus.market.order;
import com.campus.market.product.ProductResponse;
import com.campus.market.user.UserResponse;
import java.sql.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static com.campus.market.order.OrderModels.*;
@Repository
public class OrderRepository {
 private final JdbcTemplate jdbc;
 public OrderRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public boolean hasActive(long productId){return jdbc.queryForObject("SELECT COUNT(*) FROM market_orders WHERE active_product_id=?",Long.class,productId)>0;}
 public void requireUnreserved(long productId){if(!jdbc.queryForList("SELECT id FROM market_orders WHERE active_product_id=? FOR UPDATE",Long.class,productId).isEmpty())throw OrderException.conflict("商品已有有效订单，暂不能修改或继续发起交易；未付款订单取消或超时后释放");}
 Stored request(long buyerId,String requestId){return stored("SELECT * FROM market_orders WHERE buyer_id=? AND request_id=?",buyerId,requestId);}
 Stored active(long productId){return stored("SELECT * FROM market_orders WHERE active_product_id=?",productId);}
 Stored participant(long id,long userId){return stored("SELECT * FROM market_orders WHERE id=? AND (buyer_id=? OR seller_id=?)",id,userId,userId);}
 Stored lockBuyer(long id,long userId){return stored("SELECT * FROM market_orders WHERE id=? AND buyer_id=? FOR UPDATE",id,userId);}
 private Stored stored(String sql,Object...args){return jdbc.query(sql,(rs,n)->new Stored(rs.getLong("id"),rs.getLong("product_id"),rs.getLong("buyer_id"),rs.getLong("seller_id"),rs.getLong("product_version_snapshot"),rs.getString("status")),args).stream().findFirst().orElse(null);}
 long insert(ProductResponse p,UserResponse buyer,String requestId){
  var key=new GeneratedKeyHolder();
  jdbc.update(c->{var s=c.prepareStatement("INSERT INTO market_orders(product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot) VALUES(?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
   s.setLong(1,p.id());s.setLong(2,buyer.id());s.setLong(3,p.seller().id());s.setString(4,requestId);s.setString(5,p.title());s.setLong(6,p.priceInCents());s.setLong(7,p.version());s.setString(8,buyer.nickname());s.setString(9,p.seller().nickname());return s;},key);
  long id=key.getKey().longValue();
  String eventId=java.util.UUID.randomUUID().toString();
  jdbc.update("INSERT INTO order_timeout_outbox(event_id,order_id) VALUES(?,?)",eventId,id);
  com.campus.market.order.mq.OrderMqLog.outboxInserted(eventId,id);
  return id;
 }
 int bumpProduct(long id,long version){return jdbc.update("UPDATE products SET version=version+1 WHERE id=? AND version=? AND version<9007199254740991",id,version);}
 int cancel(long id,long buyerId){return jdbc.update("UPDATE market_orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=? AND buyer_id=? AND status='PENDING_PAYMENT' AND payment_due_at > CURRENT_TIMESTAMP(6)",id,buyerId);}
 boolean expired(long id) {
  return jdbc.queryForObject("SELECT payment_due_at <= CURRENT_TIMESTAMP(6) FROM market_orders WHERE id=?", Boolean.class, id);
 }
 int pay(long id,long buyerId) {
  return jdbc.update("UPDATE market_orders SET status='PAID',paid_at=CURRENT_TIMESTAMP(6),payment_kind='SIMULATED' WHERE id=? AND buyer_id=? AND status='PENDING_PAYMENT' AND payment_due_at > CURRENT_TIMESTAMP(6)", id,buyerId);
 }
 int expire(long id) {
  return jdbc.update("UPDATE market_orders SET status='CLOSED_TIMEOUT',closed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PENDING_PAYMENT' AND payment_due_at <= CURRENT_TIMESTAMP(6)", id);
 }
 int sell(long productId,long version) {
  return jdbc.update("UPDATE products SET status='SOLD',version=version+1 WHERE id=? AND version=? AND version<9007199254740991 AND status='ON_SALE'",productId,version);
 }
 int deliver(long id,long sellerId) {
  return jdbc.update("UPDATE market_orders SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6) WHERE id=? AND seller_id=? AND status='PAID'",id,sellerId);
 }
 int complete(long id,long buyerId) {
  return jdbc.update("UPDATE market_orders SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND buyer_id=? AND status='DELIVERED'",id,buyerId);
 }
 List<Long> dueIds() {
  return jdbc.queryForList("SELECT id FROM market_orders WHERE status='PENDING_PAYMENT' AND payment_due_at <= CURRENT_TIMESTAMP(6) ORDER BY payment_due_at,id LIMIT 100", Long.class);
 }
 Stored find(long id) {return stored("SELECT * FROM market_orders WHERE id=?",id);}
 Stored lock(long id) {return stored("SELECT * FROM market_orders WHERE id=? FOR UPDATE",id);}
 private String time(ResultSet rs,String column)throws SQLException {var value=rs.getTimestamp(column);return value==null?null:value.toLocalDateTime().toString();}
 Order read(long id){return jdbc.query("SELECT * FROM market_orders WHERE id=?",this::map,id).stream().findFirst().orElseThrow();}
 Summary summary(long userId,boolean seller) {
  String column=seller?"seller_id":"buyer_id";
  String sql="""
   SELECT COUNT(*) AS total,
    COALESCE(SUM(status='PENDING_PAYMENT'),0) AS pending_payment,
    COALESCE(SUM(status='PAID'),0) AS paid,
    COALESCE(SUM(status='DELIVERED'),0) AS delivered,
    COALESCE(SUM(status='COMPLETED'),0) AS completed,
    COALESCE(SUM(status='CANCELLED'),0) AS cancelled,
    COALESCE(SUM(status='CLOSED_TIMEOUT'),0) AS closed_timeout,
    COALESCE(SUM(CASE WHEN status='COMPLETED' THEN price_in_cents_snapshot ELSE 0 END),0) AS completed_amount
   FROM market_orders
   """+" WHERE "+column+"=?";
  return jdbc.queryForObject(sql,(rs,n)->{
   long amount=rs.getBigDecimal("completed_amount").longValueExact();
   if(amount<0||amount>9007199254740991L)throw new OrderException(503,"ORDER_SUMMARY_UNAVAILABLE","订单统计金额超出可展示范围");
   return new Summary(rs.getLong("total"),rs.getLong("pending_payment"),rs.getLong("paid"),rs.getLong("delivered"),rs.getLong("completed"),rs.getLong("cancelled"),rs.getLong("closed_timeout"),amount);
  },userId);
 }
 List<Order> page(long userId,boolean seller,String status,int size,long offset) {
  String sql="SELECT * FROM market_orders WHERE "+(seller?"seller_id":"buyer_id")+"=?";
  if("ALL".equals(status))return jdbc.query(sql+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::map,userId,size,offset);
  return jdbc.query(sql+" AND status=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::map,userId,status,size,offset);
 }
 /** 只由已持有商品/订单锁的业务事务追加。应用没有修改或删除事件的接口。 */
 void append(long orderId,String action,String from,String to,String role,Long actorId,String nickname) {
  if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("操作记录必须参与业务事务");
  var previous=jdbc.queryForList("SELECT event_no,to_status FROM order_events WHERE order_id=? ORDER BY event_no DESC LIMIT 1",orderId);
  int sequence=previous.isEmpty()?1:((Number)previous.get(0).get("event_no")).intValue()+1;
  if((from==null&&!previous.isEmpty())||(from!=null&&(previous.isEmpty()||!from.equals(previous.get(0).get("to_status")))))throw new IllegalStateException("订单操作记录与当前流转不一致");
  String timeColumn=switch(action){case "CREATED"->"created_at";case "SIMULATED_PAYMENT"->"paid_at";case "DELIVERED"->"delivered_at";case "COMPLETED"->"completed_at";case "CANCELLED"->"cancelled_at";case "TIMEOUT_CLOSED"->"closed_at";default->throw new IllegalArgumentException("不支持的订单事件");};
  String scope=switch(role){case "BUYER"->"buyer_id=?";case "SELLER"->"seller_id=?";case "SYSTEM"->"? IS NULL";default->throw new IllegalArgumentException("不支持的操作角色");};
  int changed=jdbc.update("INSERT INTO order_events(order_id,event_no,action,from_status,to_status,actor_role,actor_id,actor_nickname,occurred_at) SELECT id,?,?,?,?,?,?,?,"+timeColumn+" FROM market_orders WHERE id=? AND status=? AND "+scope,sequence,action,from,to,role,actorId,nickname,orderId,to,actorId);
  if(changed!=1)throw new IllegalStateException("订单操作记录写入失败");
 }
 List<Event> events(long orderId) {
  return jdbc.query("SELECT * FROM order_events WHERE order_id=? ORDER BY event_no",(rs,n)->new Event(rs.getInt("event_no"),rs.getString("action"),rs.getString("from_status"),rs.getString("to_status"),rs.getString("actor_role"),rs.getObject("actor_id",Long.class),rs.getString("actor_nickname"),time(rs,"occurred_at"),time(rs,"recorded_at")),orderId);
 }
 private Order map(ResultSet rs,int n)throws SQLException{return new Order(rs.getLong("id"),rs.getLong("product_id"),rs.getString("product_title_snapshot"),rs.getLong("price_in_cents_snapshot"),rs.getLong("product_version_snapshot"),new Participant(rs.getLong("buyer_id"),rs.getString("buyer_nickname_snapshot")),new Participant(rs.getLong("seller_id"),rs.getString("seller_nickname_snapshot")),rs.getString("status"),rs.getTimestamp("created_at").toLocalDateTime().toString(),time(rs,"cancelled_at"),time(rs,"payment_due_at"),time(rs,"paid_at"),time(rs,"closed_at"),rs.getString("payment_kind"),time(rs,"delivered_at"),time(rs,"completed_at"));}
}
