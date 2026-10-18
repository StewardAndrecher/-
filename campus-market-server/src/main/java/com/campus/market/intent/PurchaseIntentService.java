package com.campus.market.intent;

import com.campus.market.auth.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.market.intent.PurchaseIntentModels.*;

@Service
public class PurchaseIntentService {
 private static final long MAX_SAFE=9007199254740991L;
 private final PurchaseIntentRepository repository;
 private final SessionService sessions;
 private final com.campus.market.order.OrderRepository orders;
 public PurchaseIntentService(PurchaseIntentRepository repository,SessionService sessions,com.campus.market.order.OrderRepository orders) { this.repository=repository;this.sessions=sessions;this.orders=orders; }
 // READ_COMMITTED lets a waiting duplicate read the record committed by the first request.
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Created create(long productId,CreateRequest request,String auth) {
  long buyerId=sessions.currentUser(auth).id();
  if(productId<1||productId>MAX_SAFE||request==null||request.productVersion()==null||request.productVersion()<0||request.productVersion()>MAX_SAFE)
   throw PurchaseIntentException.invalid("商品 ID 和版本不合法，请重新打开商品详情");
  var product=repository.lockProduct(productId);
  if(product==null)throw new PurchaseIntentException(404,"PRODUCT_NOT_FOUND","商品不存在");
  // Replay precedes current status/version validation: a lost response must remain recoverable.
  Long existing=repository.existingId(productId,buyerId);
  if(existing!=null)return new Created(repository.read(existing),true);
  if(product.sellerId()==null)throw new PurchaseIntentException(409,"INTENT_SELLER_UNAVAILABLE","该历史商品尚未登记卖家，无法发起购买意向");
  if(product.sellerId()==buyerId)throw new PurchaseIntentException(403,"SELF_PURCHASE","不能购买自己发布的商品");
  if(!"ON_SALE".equals(product.status()))throw new PurchaseIntentException(409,"PRODUCT_UNAVAILABLE","商品已下架或售出，无法发起新的购买意向");
  orders.requireUnreserved(productId);
  if(product.version()!=request.productVersion())throw new PurchaseIntentException(409,"PRODUCT_CHANGED","商品信息已变化，请刷新详情并重新确认");
  long id=repository.insert(product,buyerId);
  return new Created(repository.read(id),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Cancelled cancel(long intentId,String auth) {
  long buyerId=sessions.currentUser(auth).id();
  if(intentId<1||intentId>MAX_SAFE)throw PurchaseIntentException.invalid("购买意向编号不合法");
  // Query with ownership in SQL: another buyer and the seller both see the same 404.
  var owned=repository.lockOwnedIntent(intentId,buyerId);
  if(owned==null)throw new PurchaseIntentException(404,"PURCHASE_INTENT_NOT_FOUND","购买意向不存在或不属于当前买家");
  if("CANCELLED".equals(owned.status()))return new Cancelled(repository.read(intentId),true);
  if(!"PENDING".equals(owned.status())||repository.cancelOwned(intentId,buyerId)!=1)
   throw new PurchaseIntentException(409,"PURCHASE_INTENT_CHANGED","意向状态已变化，请刷新列表后确认");
  return new Cancelled(repository.read(intentId),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Accepted accept(long intentId,String auth) {
  long sellerId=sessions.currentUser(auth).id();
  if(intentId<1||intentId>MAX_SAFE)throw PurchaseIntentException.invalid("购买意向编号不合法");
  Long productId=repository.sellerIntentProductId(intentId,sellerId);
  if(productId==null)throw new PurchaseIntentException(404,"PURCHASE_INTENT_NOT_FOUND","购买意向不存在或不属于当前卖家");
  // Match creation lock order: product first, intention second. Cancellation only locks intention.
  var product=repository.lockProduct(productId);
  var owned=repository.lockSellerIntent(intentId,sellerId);
  if(owned==null)throw new PurchaseIntentException(404,"PURCHASE_INTENT_NOT_FOUND","购买意向不存在或不属于当前卖家");
  // Lost-response recovery is valid even if the product was subsequently taken off sale.
  if("ACCEPTED".equals(owned.status()))return new Accepted(repository.read(intentId),true);
  if(!"PENDING".equals(owned.status()))throw new PurchaseIntentException(409,"PURCHASE_INTENT_CHANGED","意向已取消，不能接受，请刷新列表确认");
  if(product==null||product.sellerId()==null||product.sellerId()!=sellerId||!"ON_SALE".equals(product.status()))
   throw new PurchaseIntentException(409,"PRODUCT_UNAVAILABLE","商品当前不在售或卖家已变化，不能接受新的意向");
  orders.requireUnreserved(productId);
  if(repository.acceptOwned(intentId,sellerId)!=1)throw new PurchaseIntentException(409,"PURCHASE_INTENT_CHANGED","意向状态已变化，请刷新列表后确认");
  return new Accepted(repository.read(intentId),false);
 }
 @Transactional(readOnly=true)
 public Intent detail(long intentId,String auth) {
  long userId=sessions.currentUser(auth).id();
  if(intentId<1||intentId>MAX_SAFE)throw PurchaseIntentException.invalid("购买意向编号不合法");
  var intent=repository.readParticipant(intentId,userId);
  if(intent==null)throw new PurchaseIntentException(404,"PURCHASE_INTENT_NOT_FOUND","购买意向不存在或无权查看");
  return intent;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(String roleValue,int page,int size,String statusValue,String unreadOnlyValue,String sortValue,String auth) {
  long userId=sessions.currentUser(auth).id();
  var role=PurchaseIntentRole.parse(roleValue);
  var status=PurchaseIntentStatusFilter.parse(statusValue);
  var sort=PurchaseIntentSort.parse(sortValue);
  if(!"true".equals(unreadOnlyValue)&&!"false".equals(unreadOnlyValue))throw PurchaseIntentException.invalid("未读筛选只能是true或false");
  boolean unreadOnly="true".equals(unreadOnlyValue);
  if(page<1||page>1000000||size<1||size>20)throw PurchaseIntentException.invalid("页码须为 1–1000000，每页须为 1–20 条");
  var summary=repository.summary(userId,role);
  long total=unreadOnly?repository.unreadCount(userId,role,status):summary.count(status);
  int totalPages=(int)Math.max(1,Math.min(Integer.MAX_VALUE,(total+size-1)/size));
  int actualPage=Math.min(page,totalPages);
  var items=repository.page(userId,role,status,size,(long)(actualPage-1)*size,unreadOnly,sort);
  var receivedTimes=repository.receivedTimes(userId,role,items);
  return new Page(items,total,actualPage,size,totalPages,role.name(),status.value(),summary,unreadOnly,sort.name(),receivedTimes);
 }
}
