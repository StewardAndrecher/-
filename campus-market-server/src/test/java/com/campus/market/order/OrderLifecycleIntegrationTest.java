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
@SpringBootTest(properties={"spring.datasource.url=${LESSON70_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=true","market.orders.simulated-payment-enabled=true","market.orders.timeout-sweep-enabled=false","spring.data.redis.port=${LESSON67_REDIS_PORT:1}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON70_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson70_verify\\?.*")
class OrderLifecycleIntegrationTest {
 @Autowired OrderTimeoutSweep sweep;
 @Autowired ProductDetailCache cache;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired OrderService service;@Autowired ProductService products;
 @Autowired ProductRepository repository;@Autowired SessionService sessions;@Autowired PlatformTransactionManager transactions;@Autowired PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();String hash,sellerToken,buyerToken,otherToken;long product,seller,buyer,other;
 @BeforeAll void hash(){hash=encoder.encode("Campus2026!");}
 @BeforeEach void fixtures()throws Exception {
  assertTrue(System.getenv("LESSON70_DB_URL").contains("/campus_lesson70_verify?"));
  jdbc.update("DELETE FROM market_orders");jdbc.update("DELETE FROM product_comments");jdbc.update("DELETE FROM product_favorites");jdbc.update("DELETE FROM purchase_intents");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
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

 void due(long id){jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);}
 String status(long id){return service.detail(id,buyerToken).status();}
 JsonNode pay(long id,String auth,int code)throws Exception{var q=post("/api/orders/"+id+"/simulate-payment");if(auth!=null)q.header("Authorization",auth);return check(q,code);}
 @Test void deadlineIsExactlyFifteenMinutesFromCreation()throws Exception{long id=created();assertEquals(900,jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,created_at,payment_due_at) FROM market_orders WHERE id=?",Integer.class,id));assertNotNull(service.detail(id,buyerToken).paymentDueAt());}
 @Test void buyerCanSimulatePaymentAndProductBecomesSold()throws Exception{long id=created();var result=pay(id,buyerToken,200);assertEquals("PAID",result.get("order").get("status").asText());assertEquals("SIMULATED",result.get("order").get("paymentKind").asText());assertFalse(result.get("order").get("paidAt").isNull());assertEquals(ProductStatus.SOLD,repository.findById(product).orElseThrow().status());assertEquals(2,version());}
 @Test void paymentReplayDoesNotBumpVersionOrChangeTimestamp()throws Exception{long id=created();var first=service.simulatePayment(id,buyerToken);var replay=service.simulatePayment(id,buyerToken);assertFalse(first.replayed());assertTrue(replay.replayed());assertEquals(first.order().paidAt(),replay.order().paidAt());assertEquals(2,version());}
 @Test void sellerCannotSimulateBuyersPayment()throws Exception{long id=created();pay(id,sellerToken,404);assertEquals("PENDING_PAYMENT",status(id));}
 @Test void thirdPartyCannotSimulatePayment()throws Exception{long id=created();pay(id,otherToken,404);assertEquals(1,version());}
 @Test void anonymousPaymentIsUnauthorized()throws Exception{pay(created(),null,401);}
 @Test void cancelledOrderCannotBePaid()throws Exception{long id=created();service.cancel(id,buyerToken);pay(id,buyerToken,409);assertEquals("CANCELLED",status(id));}
 @Test void paidOrderCannotBeCancelled()throws Exception{long id=created();service.simulatePayment(id,buyerToken);check(post("/api/orders/"+id+"/cancel").header("Authorization",buyerToken),409);assertEquals("PAID",status(id));assertEquals(2,version());}
 @Test void expiredPaymentClosesBeforeReturning409WithoutRollback()throws Exception{long id=created();due(id);pay(id,buyerToken,409);assertEquals("CLOSED_TIMEOUT",status(id));assertNotNull(service.detail(id,buyerToken).closedAt());assertEquals(2,version());assertEquals(ProductStatus.ON_SALE,repository.findById(product).orElseThrow().status());}
 @Test void repeatExpiredPaymentKeepsTheSameTerminalOrder()throws Exception{long id=created();due(id);pay(id,buyerToken,409);String closed=service.detail(id,buyerToken).closedAt();pay(id,buyerToken,409);assertEquals(closed,service.detail(id,buyerToken).closedAt());assertEquals(2,version());}
 @Test void cancellationAfterDeadlineReturnsTimeoutInsteadOfManualCancel()throws Exception{long id=created();due(id);var result=check(post("/api/orders/"+id+"/cancel").header("Authorization",buyerToken),200);assertEquals("CLOSED_TIMEOUT",result.get("order").get("status").asText());assertTrue(result.get("order").get("cancelledAt").isNull());assertTrue(service.cancel(id,buyerToken).replayed());}
 @Test void sweepClosesExpiredOrdersAndReleasesOccupancy()throws Exception{long id=created();due(id);assertEquals(1,sweep.runOnce());assertEquals("CLOSED_TIMEOUT",status(id));assertEquals(0,sweep.runOnce());assertEquals(2,version());assertTrue(service.state(product,otherToken).available());check(create(product,2,uuid(),otherToken),201);}
 @Test void sweepLeavesFuturePendingOrderUntouched()throws Exception{long id=created();assertEquals(0,sweep.runOnce());assertEquals("PENDING_PAYMENT",status(id));assertEquals(1,version());}
 @Test void alreadyPaidOrderNeverTimesOut()throws Exception{long id=created();service.simulatePayment(id,buyerToken);assertFalse(service.closeExpired(id));assertEquals(0,sweep.runOnce());assertEquals("PAID",status(id));}
 @Test void alreadyCancelledOrderDoesNotBumpAgain()throws Exception{long id=created();service.cancel(id,buyerToken);due(id);assertFalse(service.closeExpired(id));assertEquals(0,sweep.runOnce());assertEquals(2,version());}
 @Test void missingTimeoutCandidateIsHarmless(){assertFalse(service.closeExpired(9007199254740991L));}
 @Test void repeatedTimeoutIsIdempotent()throws Exception{long id=created();due(id);assertTrue(service.closeExpired(id));assertFalse(service.closeExpired(id));assertEquals(2,version());}
 @Test void originalCreateRequestReplaysTimedOutOrder()throws Exception{String key=uuid();var original=service.create(product,new Create(0L,key),buyerToken);due(original.order().id());service.closeExpired(original.order().id());var replay=service.create(product,new Create(0L,key),buyerToken);assertTrue(replay.replayed());assertEquals("CLOSED_TIMEOUT",replay.order().status());assertEquals(1,count());}
 @Test void originalCreateRequestReplaysPaidOrder()throws Exception{String key=uuid();var original=service.create(product,new Create(0L,key),buyerToken);service.simulatePayment(original.order().id(),buyerToken);var replay=service.create(product,new Create(0L,key),buyerToken);assertTrue(replay.replayed());assertEquals("PAID",replay.order().status());}
 @Test void paymentTransactionRollbackRestoresOrderAndProduct()throws Exception{long id=created();new TransactionTemplate(transactions).executeWithoutResult(tx->{service.simulatePayment(id,buyerToken);tx.setRollbackOnly();});assertEquals("PENDING_PAYMENT",status(id));assertEquals(ProductStatus.ON_SALE,repository.findById(product).orElseThrow().status());assertEquals(1,version());}
 @Test void timeoutTransactionRollbackRetainsReservation()throws Exception{long id=created();due(id);new TransactionTemplate(transactions).executeWithoutResult(tx->{assertTrue(service.closeExpired(id));tx.setRollbackOnly();});assertEquals("PENDING_PAYMENT",status(id));assertFalse(service.state(product,otherToken).available());assertEquals(1,version());}
 @Test void paymentEvictsOldCachedVersionAfterCommit()throws Exception{long id=created();products.getProduct(product,null);assertTrue(cache.read(product,1).isPresent());service.simulatePayment(id,buyerToken);assertTrue(cache.read(product,1).isEmpty());assertEquals(ProductStatus.SOLD,products.getProduct(product,sellerToken).status());assertThrows(ProductNotFoundException.class,()->products.getProduct(product,null));}
 @Test void rolledBackPaymentPreservesCache()throws Exception{long id=created();products.getProduct(product,null);new TransactionTemplate(transactions).executeWithoutResult(tx->{service.simulatePayment(id,buyerToken);tx.setRollbackOnly();});assertTrue(cache.read(product,1).isPresent());}
 @Test void timeoutEvictsOldCachedVersionAfterCommit()throws Exception{long id=created();products.getProduct(product,null);due(id);service.closeExpired(id);assertTrue(cache.read(product,1).isEmpty());assertEquals(2,products.getProduct(product,null).version());}
 @Test void paymentAndCancellationRaceHasExactlyOneWinner()throws Exception{long id=created();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var a=pool.submit(()->{gate.await();try{service.simulatePayment(id,buyerToken);return true;}catch(OrderException e){assertEquals(409,e.status());return false;}});var b=pool.submit(()->{gate.await();try{service.cancel(id,buyerToken);return true;}catch(OrderException e){assertEquals(409,e.status());return false;}});gate.countDown();assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertTrue(List.of("PAID","CANCELLED").contains(status(id)));assertEquals(2,version());}finally{pool.shutdownNow();}}
 @Test void twoPaymentsCommitOnlyOneStateChange()throws Exception{long id=created();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{Callable<Receipt> task=()->{gate.await();return service.simulatePayment(id,buyerToken);};var a=pool.submit(task);var b=pool.submit(task);gate.countDown();var first=a.get(5,TimeUnit.SECONDS);var second=b.get(5,TimeUnit.SECONDS);assertNotEquals(first.replayed(),second.replayed());assertEquals(first.order().paidAt(),second.order().paidAt());assertEquals(2,version());}finally{pool.shutdownNow();}}
 @Test void twoSweepWorkersCloseOnlyOnce()throws Exception{long id=created();due(id);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.closeExpired(id));var b=pool.submit(()->service.closeExpired(id));assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertEquals(2,version());}finally{pool.shutdownNow();}}
 @Test void paymentAndSweepAfterDeadlineBothObserveTimeout()throws Exception{long id=created();due(id);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.simulatePayment(id,buyerToken));var b=pool.submit(()->service.closeExpired(id));assertEquals("CLOSED_TIMEOUT",a.get(5,TimeUnit.SECONDS).order().status());b.get(5,TimeUnit.SECONDS);assertEquals(2,version());assertNull(service.detail(id,buyerToken).paidAt());}finally{pool.shutdownNow();}}
 @Test void failedSweepOrderDoesNotBlockFollowingCandidate()throws Exception{long id=created();long second=repository.insert("第二件超时教材","",5000,seller,null,"BOOK");long two=service.create(second,new Create(0L,uuid()),otherToken).order().id();due(id);due(two);jdbc.update("UPDATE products SET version=9007199254740991 WHERE id=?",product);assertEquals(1,sweep.runOnce());assertEquals("PENDING_PAYMENT",status(id));assertEquals("CLOSED_TIMEOUT",service.detail(two,otherToken).status());}
 @Test void sqlRejectsPaidWithoutSimulationKindOrTimestamp()throws Exception{long id=created();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='PAID',paid_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='PAID',payment_kind='SIMULATED' WHERE id=?",id));}
 @Test void sqlRejectsTimeoutBeforeDeadlineAndPaymentAfterDeadline()throws Exception{long id=created();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='CLOSED_TIMEOUT',closed_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));due(id);assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE market_orders SET status='PAID',paid_at=CURRENT_TIMESTAMP(6),payment_kind='SIMULATED' WHERE id=?",id));}
 @Test void timeoutDoesNotChangeSnapshotsOrOriginalRequest()throws Exception{long id=created();due(id);service.closeExpired(id);products.updateProduct(product,new UpdateProductRequest("超时后修改教材","",9900L,null,2L),sellerToken);var o=service.detail(id,buyerToken);assertEquals(3500,o.priceInCentsSnapshot());assertEquals("订单教材",o.productTitleSnapshot());assertEquals(0,o.productVersionSnapshot());}
 @Test void paidOrderStillReservesTheProduct()throws Exception{long id=created();service.simulatePayment(id,buyerToken);assertEquals(product,jdbc.queryForObject("SELECT active_product_id FROM market_orders WHERE id=?",Long.class,id));check(create(product,2,uuid(),otherToken),409);}
 @Test void paymentResponsesAndTimeoutErrorsAreNotCached()throws Exception{long id=created();var response=mvc.perform(post("/api/orders/"+id+"/simulate-payment").header("Authorization",buyerToken)).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(200,response.getStatus());}

 @Test void disabledSimulationCannotChangeAnyOrder()throws Exception{long id=created();org.springframework.test.util.ReflectionTestUtils.setField(service,"simulatedPaymentEnabled",false);try{pay(id,buyerToken,503);assertEquals("PENDING_PAYMENT",status(id));assertEquals(1,version());}finally{org.springframework.test.util.ReflectionTestUtils.setField(service,"simulatedPaymentEnabled",true);}}

}
