package com.campus.market.order;
import com.campus.market.product.*;
import com.campus.market.auth.SessionService;
import com.campus.market.user.UserResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.campus.market.order.OrderModels.*;
@SpringBootTest(properties={"spring.datasource.url=${LESSON71_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=true","market.orders.simulated-payment-enabled=true","market.orders.timeout-sweep-enabled=false","spring.data.redis.port=${LESSON67_REDIS_PORT:1}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON71_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson71_verify\\?.*")
class OrderFulfillmentIntegrationTest {
 @Autowired OrderTimeoutSweep sweep;
 @Autowired ProductDetailCache cache;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired OrderService service;@Autowired ProductService products;
 @Autowired ProductRepository repository;@Autowired SessionService sessions;@Autowired PlatformTransactionManager transactions;@Autowired PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();String hash,sellerToken,buyerToken,otherToken;long product,seller,buyer,other;
 @BeforeAll void hash(){hash=encoder.encode("Campus2026!");}
 @BeforeEach void fixtures()throws Exception {
  assertTrue(System.getenv("LESSON71_DB_URL").contains("/campus_lesson71_verify?"));
  com.campus.market.support.FinancialFixtureCleanup.clear(jdbc);jdbc.update("DELETE FROM market_orders");jdbc.update("DELETE FROM product_comments");jdbc.update("DELETE FROM product_favorites");jdbc.update("DELETE FROM purchase_intents");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  seller=user("order_seller");buyer=user("order_buyer");other=user("order_other");
  sellerToken=token(seller,"order_seller");buyerToken=token(buyer,"order_buyer");otherToken=token(other,"order_other");
  product=repository.insert("订单教材","原始描述",3500,seller,null,"BOOK");
 }
 long user(String name){jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES(?,?,?)",name,hash,name);return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);}
 String token(long id,String username){return "Bearer "+sessions.issueToken(new UserResponse(id,username,username)).accessToken();}
 String uuid(){return UUID.randomUUID().toString();}
 JsonNode check(MockHttpServletRequestBuilder request,int status)throws Exception {var r=mvc.perform(request).andReturn().getResponse();assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));return r.getContentAsByteArray().length==0?null:json.readTree(r.getContentAsString(StandardCharsets.UTF_8));}
 MockHttpServletRequestBuilder create(long id,long version,String key,String auth){var q=post("/api/products/"+id+"/orders").contentType("application/json").content(json.writeValueAsString(Map.of("productVersion",version,"requestId",key)));return auth==null?q:q.header("Authorization",auth);}
 long created()throws Exception{return check(create(product,0,uuid(),buyerToken),201).get("order").get("id").asLong();}
 long version(){return repository.findById(product).orElseThrow().version();}
 long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM market_orders",Long.class);}


 long paid()throws Exception{long id=created();service.simulatePayment(id,buyerToken);return id;}
 long delivered()throws Exception{long id=paid();service.deliver(id,sellerToken);return id;}
 JsonNode deliver(long id,String auth,int code)throws Exception{var q=post("/api/orders/"+id+"/deliver");if(auth!=null)q.header("Authorization",auth);return check(q,code);}
 JsonNode complete(long id,String auth,int code)throws Exception{var q=post("/api/orders/"+id+"/complete");if(auth!=null)q.header("Authorization",auth);return check(q,code);}
 String status(long id){return service.detail(id,buyerToken).status();}
 @Test void sellerCanConfirmDeliveryAfterPayment()throws Exception{long id=paid();var r=deliver(id,sellerToken,200);assertEquals("DELIVERED",r.get("order").get("status").asText());assertFalse(r.get("replayed").asBoolean());assertFalse(r.get("order").get("deliveredAt").isNull());assertTrue(r.get("order").get("completedAt").isNull());assertEquals(2,version());}
 @Test void buyerCanCompleteOnlyAfterDelivery()throws Exception{long id=delivered();var r=complete(id,buyerToken,200);assertEquals("COMPLETED",r.get("order").get("status").asText());assertFalse(r.get("replayed").asBoolean());assertFalse(r.get("order").get("completedAt").isNull());assertEquals(2,version());}
 @Test void buyerCannotConfirmSellersDelivery()throws Exception{long id=paid();deliver(id,buyerToken,404);assertEquals("PAID",status(id));}
 @Test void sellerCannotConfirmBuyersReceipt()throws Exception{long id=delivered();complete(id,sellerToken,404);assertEquals("DELIVERED",status(id));}
 @Test void thirdPartyCannotPerformEitherAction()throws Exception{long id=paid();deliver(id,otherToken,404);complete(id,otherToken,404);service.deliver(id,sellerToken);complete(id,otherToken,404);}
 @Test void anonymousActionsRequireLogin()throws Exception{long id=paid();deliver(id,null,401);complete(id,null,401);}
 @Test void missingAndInvalidOrderIdsAreRejected()throws Exception{deliver(0,sellerToken,400);complete(0,buyerToken,400);deliver(9007199254740991L,sellerToken,404);complete(9007199254740991L,buyerToken,404);}
 @Test void pendingOrderCannotBeDeliveredOrCompleted()throws Exception{long id=created();deliver(id,sellerToken,409);complete(id,buyerToken,409);assertEquals("PENDING_PAYMENT",status(id));}
 @Test void paidBuyerCannotSkipDelivery()throws Exception{long id=paid();complete(id,buyerToken,409);assertEquals("PAID",status(id));assertNull(service.detail(id,buyerToken).completedAt());}
 @Test void cancelledOrderCannotAdvance()throws Exception{long id=created();service.cancel(id,buyerToken);deliver(id,sellerToken,409);complete(id,buyerToken,409);assertEquals("CANCELLED",status(id));}
 @Test void timedOutOrderCannotAdvance()throws Exception{long id=created();jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);service.closeExpired(id);deliver(id,sellerToken,409);complete(id,buyerToken,409);assertEquals("CLOSED_TIMEOUT",status(id));}
 @Test void deliveryReplayKeepsFirstTimestamp()throws Exception{long id=paid();var first=service.deliver(id,sellerToken);var replay=service.deliver(id,sellerToken);assertTrue(replay.replayed());assertEquals(first.order().deliveredAt(),replay.order().deliveredAt());assertEquals(2,version());}
 @Test void completionReplayKeepsFirstTimestamp()throws Exception{long id=delivered();var first=service.complete(id,buyerToken);var replay=service.complete(id,buyerToken);assertTrue(replay.replayed());assertEquals(first.order().completedAt(),replay.order().completedAt());assertEquals(2,version());}
 @Test void lateDeliveryReplayReturnsCompletedWithoutRegression()throws Exception{long id=delivered();service.complete(id,buyerToken);var result=service.deliver(id,sellerToken);assertTrue(result.replayed());assertEquals("COMPLETED",result.order().status());}
 @Test void latePaymentReplayReturnsDeliveredOrder()throws Exception{long id=delivered();var before=service.detail(id,buyerToken);var result=service.simulatePayment(id,buyerToken);assertTrue(result.replayed());assertEquals("DELIVERED",result.order().status());assertEquals(before.paidAt(),result.order().paidAt());assertEquals(2,version());}
 @Test void latePaymentReplayReturnsCompletedOrder()throws Exception{long id=delivered();service.complete(id,buyerToken);var result=check(post("/api/orders/"+id+"/simulate-payment").header("Authorization",buyerToken),200);assertTrue(result.get("replayed").asBoolean());assertEquals("COMPLETED",result.get("order").get("status").asText());}
 @Test void originalCreateKeyRecoversCompletedOrder()throws Exception{String key=uuid();long id=service.create(product,new Create(0L,key),buyerToken).order().id();service.simulatePayment(id,buyerToken);service.deliver(id,sellerToken);service.complete(id,buyerToken);var r=service.create(product,new Create(0L,key),buyerToken);assertTrue(r.replayed());assertEquals("COMPLETED",r.order().status());assertEquals(1,count());}
 @Test void progressKeepsSnapshotsAndProductVersion()throws Exception{long id=paid();var before=service.detail(id,buyerToken);service.deliver(id,sellerToken);service.complete(id,buyerToken);var after=service.detail(id,buyerToken);assertEquals(before.productTitleSnapshot(),after.productTitleSnapshot());assertEquals(before.priceInCentsSnapshot(),after.priceInCentsSnapshot());assertEquals(before.createdAt(),after.createdAt());assertEquals(before.paymentDueAt(),after.paymentDueAt());assertEquals(before.paidAt(),after.paidAt());assertEquals(before.buyer(),after.buyer());assertEquals(before.seller(),after.seller());assertEquals(ProductStatus.SOLD,repository.findById(product).orElseThrow().status());assertEquals(2,version());}
 @Test void completedOrderRetainsUniqueProductOccupancy()throws Exception{long id=delivered();service.complete(id,buyerToken);assertEquals(product,jdbc.queryForObject("SELECT active_product_id FROM market_orders WHERE id=?",Long.class,id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO market_orders(product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot) VALUES(?,?,?,?,?,?,?,?,?)",product,other,seller,uuid(),"重复成交教材",3500,2,"买家","卖家"));}
 @Test void deliveredAndCompletedOrdersCannotBeCancelled()throws Exception{long id=delivered();check(post("/api/orders/"+id+"/cancel").header("Authorization",buyerToken),409);service.complete(id,buyerToken);check(post("/api/orders/"+id+"/cancel").header("Authorization",buyerToken),409);assertEquals("COMPLETED",status(id));}
 @Test void timeoutWorkerDoesNotCloseFulfillmentStates()throws Exception{long id=delivered();assertFalse(service.closeExpired(id));service.complete(id,buyerToken);assertFalse(service.closeExpired(id));assertEquals(0,sweep.runOnce());assertEquals("COMPLETED",status(id));}
 @Test void deliveryRollbackRestoresPaidState()throws Exception{long id=paid();new TransactionTemplate(transactions).executeWithoutResult(tx->{service.deliver(id,sellerToken);tx.setRollbackOnly();});assertEquals("PAID",status(id));assertNull(service.detail(id,buyerToken).deliveredAt());assertEquals(2,version());}
 @Test void completionRollbackRestoresDeliveredState()throws Exception{long id=delivered();new TransactionTemplate(transactions).executeWithoutResult(tx->{service.complete(id,buyerToken);tx.setRollbackOnly();});assertEquals("DELIVERED",status(id));assertNull(service.detail(id,buyerToken).completedAt());}
 @Test void twoDeliveryRequestsConvertOnceAndReplayOnce()throws Exception{long id=paid();var results=race(()->service.deliver(id,sellerToken));assertNotEquals(results.get(0).replayed(),results.get(1).replayed());assertEquals(results.get(0).order().deliveredAt(),results.get(1).order().deliveredAt());assertEquals("DELIVERED",status(id));}
 @Test void twoReceiptRequestsCompleteOnceAndReplayOnce()throws Exception{long id=delivered();var results=race(()->service.complete(id,buyerToken));assertNotEquals(results.get(0).replayed(),results.get(1).replayed());assertEquals(results.get(0).order().completedAt(),results.get(1).order().completedAt());assertEquals("COMPLETED",status(id));}
 List<Receipt> race(Callable<Receipt> action)throws Exception{var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{Callable<Receipt> task=()->{gate.await();return action.call();};var a=pool.submit(task);var b=pool.submit(task);gate.countDown();return List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
 @Test void concurrentDeliveryAndReceiptNeverSkipsDelivery()throws Exception{long id=paid();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var a=pool.submit(()->{gate.await();return service.deliver(id,sellerToken);});var b=pool.submit(()->{gate.await();try{return service.complete(id,buyerToken).order().status();}catch(OrderException e){assertEquals(409,e.status());return "EARLY";}});gate.countDown();a.get(5,TimeUnit.SECONDS);String result=b.get(5,TimeUnit.SECONDS);assertTrue(List.of("COMPLETED","EARLY").contains(result));if("EARLY".equals(result))service.complete(id,buyerToken);var order=service.detail(id,buyerToken);assertNotNull(order.deliveredAt());assertNotNull(order.completedAt());assertTrue(java.time.LocalDateTime.parse(order.completedAt()).compareTo(java.time.LocalDateTime.parse(order.deliveredAt()))>=0);assertEquals(2,version());}finally{pool.shutdownNow();}}
 @Test void sqlRejectsDeliveredWithoutDeliveryTime()throws Exception{long id=paid();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='DELIVERED' WHERE id=?",id));}
 @Test void sqlRejectsCompletedWithoutBothMilestones()throws Exception{long id=paid();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));}
 @Test void sqlRejectsDeliveryBeforePaymentAndReceiptBeforeDelivery()throws Exception{long id=paid();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='DELIVERED',delivered_at=paid_at-INTERVAL 1 SECOND WHERE id=?",id));service.deliver(id,sellerToken);assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='COMPLETED',completed_at=delivered_at-INTERVAL 1 SECOND WHERE id=?",id));}
 @Test void sqlRejectsUnpaidMilestonesAndCompletedTimeWhileDelivered()throws Exception{long id=created();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET delivered_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));service.simulatePayment(id,buyerToken);service.deliver(id,sellerToken);assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));}
 @Test void participantListsIncludeCompletedSnapshotAndThirdPartyCannotRead()throws Exception{long id=delivered();service.complete(id,buyerToken);assertEquals("COMPLETED",check(get("/api/orders?role=BUYER").header("Authorization",buyerToken),200).get("items").get(0).get("status").asText());assertEquals("COMPLETED",check(get("/api/orders?role=SELLER").header("Authorization",sellerToken),200).get("items").get(0).get("status").asText());check(get("/api/orders/"+id).header("Authorization",otherToken),404);}
 @Test void progressResponsesAndErrorsHaveNoStore()throws Exception{long id=paid();var r=mvc.perform(post("/api/orders/"+id+"/deliver").header("Authorization",sellerToken)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));r=mvc.perform(post("/api/orders/"+id+"/complete").header("Authorization",sellerToken)).andReturn().getResponse();assertEquals(404,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));}
 @Test void payloadCannotImpersonateTheSellerOrBuyer()throws Exception{long id=paid();check(post("/api/orders/"+id+"/deliver").header("Authorization",otherToken).contentType("application/json").content("{\"sellerId\":"+seller+"}"),404);service.deliver(id,sellerToken);check(post("/api/orders/"+id+"/complete").header("Authorization",otherToken).contentType("application/json").content("{\"buyerId\":"+buyer+"}"),404);}
 @Test void alreadyPaidOrdersCanFinishWhileSimulationDisabled()throws Exception{long id=paid();org.springframework.test.util.ReflectionTestUtils.setField(service,"simulatedPaymentEnabled",false);try{service.deliver(id,sellerToken);service.complete(id,buyerToken);assertEquals("COMPLETED",status(id));}finally{org.springframework.test.util.ReflectionTestUtils.setField(service,"simulatedPaymentEnabled",true);}}

}
