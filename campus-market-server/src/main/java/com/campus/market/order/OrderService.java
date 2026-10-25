package com.campus.market.order;
import com.campus.market.auth.SessionService;
import com.campus.market.product.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.market.order.OrderModels.*;
@Service
public class OrderService {
 @Value("${market.orders.simulated-payment-enabled:false}") private boolean simulatedPaymentEnabled;
 private final com.campus.market.finance.FinancialEvidenceService finance;
 private final OrderRepository orders;private final ProductRepository products;private final SessionService sessions;private final ProductDetailCache cache;
 public OrderService(OrderRepository orders,ProductRepository products,SessionService sessions,ProductDetailCache cache,com.campus.market.finance.FinancialEvidenceService finance){this.finance=finance;this.orders=orders;this.products=products;this.sessions=sessions;this.cache=cache;}
 private void id(long value){if(value<1||value>9007199254740991L)throw OrderException.invalid("编号必须是有效正整数");}
 private OrderException missing(){return new OrderException(404,"ORDER_NOT_FOUND","订单不存在或无权查看");}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Receipt create(long productId,Create request,String auth){
  var buyer=sessions.currentUser(auth);id(productId);
  if(request==null||request.productVersion()==null||request.productVersion()<0||request.productVersion()>9007199254740991L||request.requestId()==null||!request.requestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw OrderException.invalid("请提供有效商品版本和小写UUID提交标识");
  var product=products.findByIdForUpdate(productId).orElseThrow(()->new OrderException(404,"PRODUCT_NOT_FOUND","商品不存在"));
  // 丢失响应重试先恢复原结果，包括已取消订单，不能重新占用商品。
  var replay=orders.request(buyer.id(),request.requestId());
  if(replay!=null){if(replay.productId()!=productId||replay.version()!=request.productVersion())throw OrderException.conflict("提交标识已用于另一笔商品信息，请刷新后重试");return new Receipt(orders.read(replay.id()),true);}
  if(!simulatedPaymentEnabled)throw new OrderException(503,"PAYMENT_UNAVAILABLE","在线支付尚未开通，暂不接受新订单");
  if(product.seller()==null)throw OrderException.conflict("历史商品未登记卖家，暂不能下单");
  if(product.seller().id()==buyer.id())throw new OrderException(403,"SELF_ORDER","不能购买自己发布的商品");
  if(product.status()!=ProductStatus.ON_SALE)throw OrderException.conflict("商品当前不在售，不能下单");
  orders.requireUnreserved(productId);
  if(product.version()!=request.productVersion())throw OrderException.conflict("商品信息已变化，请重新加载详情并确认价格");
  if(product.version()>=9007199254740990L)throw OrderException.conflict("商品版本已达上限，暂不能创建订单");
  long orderId;
  try{orderId=orders.insert(product,buyer,request.requestId());}catch(DuplicateKeyException duplicate){throw OrderException.conflict("商品已有订单或提交标识已被使用，请刷新确认");}
  if(orders.bumpProduct(productId,product.version())!=1)throw OrderException.conflict("商品版本已变化，订单未创建，请重新加载");
  orders.append(orderId,"CREATED",null,"PENDING_PAYMENT","BUYER",buyer.id(),buyer.nickname());
  cache.evictAfterCommit(productId,product.version());
  return new Receipt(orders.read(orderId),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Receipt cancel(long orderId,String auth){
  var actor=sessions.currentUser(auth);long buyerId=actor.id();id(orderId);
  var visible=orders.participant(orderId,buyerId);if(visible==null||visible.buyerId()!=buyerId)throw missing();
  // 始终按商品→订单顺序加锁，和创建/卖家编辑保持相同的商品锁顺序。
  var product=products.findByIdForUpdate(visible.productId()).orElseThrow(this::missing);
  var owned=orders.lockBuyer(orderId,buyerId);if(owned==null)throw missing();
  if("CANCELLED".equals(owned.status()))return new Receipt(orders.read(orderId),true);
  if("CLOSED_TIMEOUT".equals(owned.status()))return new Receipt(orders.read(orderId),true);
  if("PENDING_PAYMENT".equals(owned.status())&&orders.expired(orderId)){expireLocked(orderId,product);return new Receipt(orders.read(orderId),false);}
  if(!"PENDING_PAYMENT".equals(owned.status()))throw OrderException.conflict("已模拟付款的订单不能取消");
  if(orders.cancel(orderId,buyerId)==0){expireLocked(orderId,product);return new Receipt(orders.read(orderId),false);}
  if(orders.bumpProduct(product.id(),product.version())!=1)throw OrderException.conflict("商品版本已变化，取消未完成");
  orders.append(orderId,"CANCELLED","PENDING_PAYMENT","CANCELLED","BUYER",buyerId,actor.nickname());
  cache.evictAfterCommit(product.id(),product.version());return new Receipt(orders.read(orderId),false);
 }
 /** 仅教学模拟：没有调用支付渠道，也不发生扣款。 */
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Receipt simulatePayment(long orderId,String auth) {
  var actor=sessions.currentUser(auth);long buyerId=actor.id();id(orderId);
  var visible=orders.participant(orderId,buyerId);
  if(visible==null||visible.buyerId()!=buyerId)throw missing();
  if(!simulatedPaymentEnabled)throw new OrderException(503,"SIMULATED_PAYMENT_DISABLED","模拟付款功能未启用");
  var product=products.findByIdForUpdate(visible.productId()).orElseThrow(this::missing);
  var owned=orders.lockBuyer(orderId,buyerId);if(owned==null)throw missing();
  if("PAID".equals(owned.status())||"DELIVERED".equals(owned.status())||"COMPLETED".equals(owned.status()))return new Receipt(orders.read(orderId),true);
  if("CLOSED_TIMEOUT".equals(owned.status()))return new Receipt(orders.read(orderId),true);
  if(!"PENDING_PAYMENT".equals(owned.status()))throw OrderException.conflict("订单已取消，不能模拟付款");
  // SQL在拿到两把锁后重新判断数据库时间；到期与付款的条件互斥。
  if(orders.pay(orderId,buyerId)==0){
   expireLocked(orderId,product);
   return new Receipt(orders.read(orderId),false);
  }
  finance.simulatedPayment(orderId);
  if(orders.sell(product.id(),product.version())!=1)throw OrderException.conflict("商品状态已变化，模拟付款未完成");
  orders.append(orderId,"SIMULATED_PAYMENT","PENDING_PAYMENT","PAID","BUYER",buyerId,actor.nickname());
  cache.evictAfterCommit(product.id(),product.version());
  return new Receipt(orders.read(orderId),false);
 }
 /** 卖家确认已交付，不代表系统核验了线下交接。 */
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Receipt deliver(long orderId,String auth) {
  return progress(orderId,auth,true);
 }
 /** 买家确认收货；不做自动收货，也不改变已售出商品。 */
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Receipt complete(long orderId,String auth) {
  return progress(orderId,auth,false);
 }
 private Receipt progress(long orderId,String auth,boolean delivery) {
  var actor=sessions.currentUser(auth);long actorId=actor.id();id(orderId);
  var visible=orders.participant(orderId,actorId);
  if(visible==null||(delivery?visible.sellerId():visible.buyerId())!=actorId)throw missing();
  // 延续所有订单写操作的商品→订单加锁顺序。
  products.findByIdForUpdate(visible.productId()).orElseThrow(this::missing);
  var owned=orders.lock(orderId);
  if(owned==null||(delivery?owned.sellerId():owned.buyerId())!=actorId)throw missing();
  if("COMPLETED".equals(owned.status())||(delivery&&"DELIVERED".equals(owned.status())))
   return new Receipt(orders.read(orderId),true);
  orders.requireNoActiveRefund(orderId);
  String expected=delivery?"PAID":"DELIVERED";
  if(!expected.equals(owned.status()))throw OrderException.conflict(delivery?"只有已模拟付款、待交付的订单才能确认交付":"请等待卖家确认交付后，再确认收货");
  int changed=delivery?orders.deliver(orderId,actorId):orders.complete(orderId,actorId);
  if(changed!=1)throw OrderException.conflict("订单状态已变化，请重新加载");
  orders.append(orderId,delivery?"DELIVERED":"COMPLETED",expected,delivery?"DELIVERED":"COMPLETED",delivery?"SELLER":"BUYER",actorId,actor.nickname());
  // 只改变私有订单，不改变商品正文/版本，因此无需删除商品缓存。
  return new Receipt(orders.read(orderId),false);
 }
 /** 定时任务从其他Bean经事务代理调用，每笔订单单独提交。 */
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public boolean closeExpired(long orderId) {
  var visible=orders.find(orderId);if(visible==null)return false;
  var product=products.findByIdForUpdate(visible.productId()).orElseThrow(this::missing);
  var owned=orders.lock(orderId);
  if(owned==null||!"PENDING_PAYMENT".equals(owned.status()))return false;
  if(orders.expire(orderId)==0)return false;
  orders.append(orderId,"TIMEOUT_CLOSED","PENDING_PAYMENT","CLOSED_TIMEOUT","SYSTEM",null,"系统（超时规则）");
  release(product);return true;
 }
 private void expireLocked(long orderId,ProductResponse product) {
  if(orders.expire(orderId)!=1)throw OrderException.conflict("订单状态已变化，请重新加载");
  orders.append(orderId,"TIMEOUT_CLOSED","PENDING_PAYMENT","CLOSED_TIMEOUT","SYSTEM",null,"系统（超时规则）");
  release(product);
 }
 private void release(ProductResponse product) {
  if(orders.bumpProduct(product.id(),product.version())!=1)throw OrderException.conflict("商品版本已变化，超时关闭未完成");
  cache.evictAfterCommit(product.id(),product.version());
 }
 @Transactional(readOnly=true)
 public Order detail(long orderId,String auth){long userId=sessions.currentUser(auth).id();id(orderId);if(orders.participant(orderId,userId)==null)throw missing();return orders.read(orderId);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Timeline timeline(long orderId,String auth) {
  long userId=sessions.currentUser(auth).id();id(orderId);
  if(orders.participant(orderId,userId)==null)throw missing();
  return new Timeline(orders.read(orderId),orders.events(orderId));
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(String role,String status,int page,int size,String auth) {return page(role,status,"",page,size,auth);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(String role,String status,String rawOrder,int page,int size,String auth) {
  long userId=sessions.currentUser(auth).id();
  Long exact=filterOrder(rawOrder);
  boolean seller=sellerScope(role,status);
  if(page<1||page>1000000||size<1||size>20)throw OrderException.invalid("订单分页参数不合法");
  // 统计和列表共享当前REPEATABLE_READ快照，汇总不受下方筛选/分页影响。
  var summary=orders.summary(userId,seller);
  long total=switch(status){
   case "PENDING_PAYMENT"->summary.pendingPayment();case "PAID"->summary.paid();
   case "DELIVERED"->summary.delivered();case "COMPLETED"->summary.completed();
   case "CANCELLED"->summary.cancelled();case "CLOSED_TIMEOUT"->summary.closedTimeout();
   default->summary.total();
  };
  if(exact!=null)total=orders.count(userId,seller,status,exact);
  int pages=(int)Math.max(1,(total+size-1)/size);int actual=Math.min(page,pages);
  return new Page(orders.page(userId,seller,status,exact,size,(long)(actual-1)*size),total,actual,size,pages,role,status,exact,summary);
 }
 static boolean sellerScope(String role,String status) {
  if(!"BUYER".equals(role)&&!"SELLER".equals(role))throw OrderException.invalid("订单角色只能是BUYER或SELLER");
  if(status==null||!java.util.Set.of("ALL","PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT").contains(status))throw OrderException.invalid("订单筛选状态不合法");
  return "SELLER".equals(role);
 }
 static Long filterOrder(String raw) {
  if(raw==null||(!raw.isEmpty()&&!raw.matches("[1-9][0-9]{0,15}")))throw OrderException.invalid("订单编号格式不正确");
  if(raw.isEmpty())return null;
  long value=Long.parseLong(raw);
  if(value>9007199254740991L)throw OrderException.invalid("订单编号超出范围");
  return value;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public State state(long productId,String auth){id(productId);var product=products.findById(productId).orElseThrow(()->new OrderException(404,"PRODUCT_NOT_FOUND","商品不存在或不在售"));Long userId=auth==null||auth.isBlank()?null:sessions.currentUser(auth).id();
  if(product.status()!=ProductStatus.ON_SALE&&(userId==null||product.seller()==null||product.seller().id()!=userId))throw new OrderException(404,"PRODUCT_NOT_FOUND","商品不存在或不在售");
  var active=orders.active(productId);Long mine=active!=null&&userId!=null&&(active.buyerId()==userId||active.sellerId()==userId)?active.id():null;
  String message=product.status()!=ProductStatus.ON_SALE?"商品当前不在售":product.seller()==null?"历史商品未登记卖家":active!=null?"商品已有有效订单，暂不能重复下单":userId!=null&&product.seller().id()==userId?"这是你发布的商品":"可创建待付款订单；下单不会自动付款";
  boolean available=product.status()==ProductStatus.ON_SALE&&product.seller()!=null&&active==null&&(userId==null||product.seller().id()!=userId);
  return new State(productId,available,mine,message);
 }
}
