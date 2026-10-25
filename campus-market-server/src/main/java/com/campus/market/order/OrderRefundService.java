package com.campus.market.order;
import com.campus.market.auth.SessionService;
import com.campus.market.product.ProductVersionDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.*;
@Service
public class OrderRefundService {
 public record Create(String requestId,String reason,@JsonDeserialize(using=IssueTextDeserializer.class) String description) {}
 public record Decision(@JsonDeserialize(using=ProductVersionDeserializer.class) Long version,String decision,@JsonDeserialize(using=IssueTextDeserializer.class) String explanation) {}
 public record Withdraw(@JsonDeserialize(using=ProductVersionDeserializer.class) Long version) {}
 public record Event(long id,String actorRole,String action,String explanation,String createdAt) {}
 public record Refund(long id,long orderId,long buyerId,long sellerId,String requestId,long amountInCents,String reason,String description,String status,long version,String explanation,String paymentKind,String createdAt,String updatedAt,List<Event> events) {}
 public record Summary(long orderId,long userId,String viewerRole,String orderStatus,String productTitle,long amountInCents,String paymentKind,boolean canApply,boolean hasActiveRefund,List<Refund> requests) {}
 public record Receipt(Refund refund,boolean replayed) {}
 public record Item(long refundId,long orderId,String productTitle,long amountInCents,String status,String createdAt) {}
 public record Page(List<Item> items,long total,int page,int size,int totalPages,String role,String status) {}
 private record Owned(long id,long buyer,long seller,String status,String title,long amount,String paymentKind) {}
 private static final Set<String> ELIGIBLE=Set.of("PAID","DELIVERED","COMPLETED"),REASONS=Set.of("NOT_AS_DESCRIBED","DAMAGED","HANDOVER","OTHER"),STATES=Set.of("ALL","REQUESTED","APPROVED","REJECTED","WITHDRAWN");
 private final com.campus.market.finance.FinancialEvidenceService finance;
 private final JdbcTemplate jdbc;private final SessionService sessions;
 public OrderRefundService(JdbcTemplate jdbc,SessionService sessions,com.campus.market.finance.FinancialEvidenceService finance){this.finance=finance;this.jdbc=jdbc;this.sessions=sessions;}
 private static void id(long id){if(id<1||id>9007199254740991L)throw invalid("编号无效");}
 private Owned owned(long id,long user,boolean lock){id(id);return jdbc.query("SELECT id,buyer_id,seller_id,status,product_title_snapshot,price_in_cents_snapshot,payment_kind FROM market_orders WHERE id=? AND (buyer_id=? OR seller_id=?)"+(lock?" FOR UPDATE":""),(r,n)->new Owned(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getLong(6),r.getString(7)),id,user,user).stream().findFirst().orElseThrow(OrderRefundService::missing);}
 private List<Event> events(long refund){return jdbc.query("SELECT id,actor_role,action,explanation,created_at FROM order_refund_events WHERE refund_id=? ORDER BY id",(r,n)->new Event(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(5).toLocalDateTime().toString()),refund);}
 private Refund map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Refund(r.getLong("id"),r.getLong("order_id"),r.getLong("buyer_id"),r.getLong("seller_id"),r.getString("request_id"),r.getLong("amount_in_cents"),r.getString("reason"),r.getString("description"),r.getString("status"),r.getLong("version"),r.getString("explanation"),r.getString("payment_kind"),r.getTimestamp("created_at").toLocalDateTime().toString(),r.getTimestamp("updated_at").toLocalDateTime().toString(),List.of());}
 private Refund withEvents(Refund r){return new Refund(r.id(),r.orderId(),r.buyerId(),r.sellerId(),r.requestId(),r.amountInCents(),r.reason(),r.description(),r.status(),r.version(),r.explanation(),r.paymentKind(),r.createdAt(),r.updatedAt(),events(r.id()));}
 private Refund read(long refund,long order){return jdbc.query("SELECT * FROM order_refunds WHERE id=? AND order_id=?",this::map,refund,order).stream().findFirst().map(this::withEvents).orElseThrow(OrderRefundService::missing);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Summary summary(long orderId,String auth){long user=sessions.currentUser(auth).id();Owned order=owned(orderId,user,false);var records=jdbc.query("SELECT * FROM order_refunds WHERE order_id=? ORDER BY id DESC LIMIT 5",this::map,orderId).stream().map(this::withEvents).toList();boolean active=records.stream().anyMatch(r->Set.of("REQUESTED","APPROVED").contains(r.status()));return new Summary(orderId,user,user==order.buyer()?"BUYER":"SELLER",order.status(),order.title(),order.amount(),order.paymentKind(),user==order.buyer()&&ELIGIBLE.contains(order.status())&&"SIMULATED".equals(order.paymentKind())&&!active&&records.size()<5,active,records);}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt create(long orderId,Create request,String auth){
  long user=sessions.currentUser(auth).id();Owned order=owned(orderId,user,true);sessions.currentUser(auth);
  if(user!=order.buyer())throw missing();
  if(request==null||request.requestId()==null||!request.requestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||request.reason()==null||!REASONS.contains(request.reason()))throw invalid("请选择退款原因并提供有效提交标识");
  String description=OrderIssueText.normalize(request.description());
  var existing=jdbc.query("SELECT * FROM order_refunds WHERE buyer_id=? AND request_id=?",this::map,user,request.requestId());
  if(!existing.isEmpty()){var saved=existing.get(0);if(saved.orderId()!=orderId||!saved.reason().equals(request.reason())||!saved.description().equals(description))throw conflict("提交标识已用于另一份申请，请使用原内容确认");return new Receipt(withEvents(saved),true);}
  if(!ELIGIBLE.contains(order.status())||!"SIMULATED".equals(order.paymentKind()))throw conflict("只有已演示付款的订单可申请；真实退款渠道尚未开通");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM order_refunds WHERE order_id=? AND status IN ('REQUESTED','APPROVED')",Long.class,orderId)>0)throw conflict("此订单已有处理中的退款申请");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM order_refunds WHERE order_id=?",Long.class,orderId)>=5)throw conflict("此订单最多提交5次申请，请通过交易问题反馈继续协商");
  var key=new GeneratedKeyHolder();try{jdbc.update(c->{var st=c.prepareStatement("INSERT INTO order_refunds(order_id,buyer_id,seller_id,request_id,amount_in_cents,reason,description,payment_kind) VALUES(?,?,?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);st.setLong(1,orderId);st.setLong(2,user);st.setLong(3,order.seller());st.setString(4,request.requestId());st.setLong(5,order.amount());st.setString(6,request.reason());st.setString(7,description);st.setString(8,order.paymentKind());return st;},key);}catch(org.springframework.dao.DuplicateKeyException duplicate){throw conflict("提交标识或订单申请已存在，请刷新确认");}
  long refund=Objects.requireNonNull(key.getKey()).longValue();append(refund,user,"BUYER","REQUESTED",description);return new Receipt(read(refund,orderId),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt decide(long orderId,long refundId,Decision request,String auth){
  long user=sessions.currentUser(auth).id();Owned order=owned(orderId,user,true);sessions.currentUser(auth);id(refundId);if(user!=order.seller())throw missing();
  if(request==null||request.decision()==null||!Set.of("APPROVE","REJECT").contains(request.decision()))throw invalid("请选择同意或拒绝申请");
  String explanation=OrderIssueText.normalize(request.explanation()),target="APPROVE".equals(request.decision())?"APPROVED":"REJECTED";Refund before=read(refundId,orderId);
  if(target.equals(before.status())&&explanation.equals(before.explanation()))return new Receipt(before,true);
  version(request.version(),before);if(!"REQUESTED".equals(before.status()))throw conflict("申请已处理或撤回，请刷新进度");
  change(before,target,explanation);if("APPROVED".equals(target))finance.approvedRefund(refundId);append(refundId,user,"SELLER",target,explanation);return new Receipt(read(refundId,orderId),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt withdraw(long orderId,long refundId,Withdraw request,String auth){
  long user=sessions.currentUser(auth).id();Owned order=owned(orderId,user,true);sessions.currentUser(auth);id(refundId);if(user!=order.buyer())throw missing();Refund before=read(refundId,orderId);
  if("WITHDRAWN".equals(before.status()))return new Receipt(before,true);
  version(request==null?null:request.version(),before);if(!"REQUESTED".equals(before.status()))throw conflict("申请已处理，不能撤回");change(before,"WITHDRAWN","");append(refundId,user,"BUYER","WITHDRAWN","");return new Receipt(read(refundId,orderId),false);
 }
 private void change(Refund before,String state,String explanation){if(jdbc.update("UPDATE order_refunds SET status=?,explanation=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=? AND status='REQUESTED'",state,explanation,before.id(),before.version())!=1)throw conflict("申请已变化，请刷新");}
 private static void version(Long value,Refund before){if(value==null||value<0||value>=9007199254740991L)throw invalid("申请版本无效");if(value!=before.version())throw conflict("申请已变化，请刷新后处理");}
 private void append(long refund,long actor,String role,String action,String text){jdbc.update("INSERT INTO order_refund_events(refund_id,actor_id,actor_role,action,explanation) VALUES(?,?,?,?,?)",refund,actor,role,action,text);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Page page(String role,String status,int page,int size,String auth){long user=sessions.currentUser(auth).id();if(!Set.of("BUYER","SELLER").contains(role)||!STATES.contains(status)||page<1||page>1000000||size<1||size>20)throw invalid("售后筛选或分页无效");String column="BUYER".equals(role)?"r.buyer_id":"r.seller_id",where=" WHERE "+column+"=?"+("ALL".equals(status)?"":" AND r.status=?");var args=new ArrayList<Object>();args.add(user);if(!"ALL".equals(status))args.add(status);long total=jdbc.queryForObject("SELECT COUNT(*) FROM order_refunds r"+where,Long.class,args.toArray());int pages=(int)Math.max(1,(total+size-1)/size),actual=Math.min(page,pages);args.add(size);args.add((long)(actual-1)*size);var items=jdbc.query("SELECT r.id,r.order_id,o.product_title_snapshot,r.amount_in_cents,r.status,r.created_at FROM order_refunds r JOIN market_orders o ON o.id=r.order_id"+where+" ORDER BY r.id DESC LIMIT ? OFFSET ?",(r,n)->new Item(r.getLong(1),r.getLong(2),r.getString(3),r.getLong(4),r.getString(5),r.getTimestamp(6).toLocalDateTime().toString()),args.toArray());return new Page(items,total,actual,size,pages,role,status);}
 private static OrderException invalid(String message){return new OrderException(400,"INVALID_REFUND",message);}
 private static OrderException conflict(String message){return new OrderException(409,"REFUND_CONFLICT",message);}
 private static OrderException missing(){return new OrderException(404,"REFUND_NOT_FOUND","订单或售后申请不存在，或当前账号不可见");}
}
