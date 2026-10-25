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
@SpringBootTest(properties={"spring.datasource.url=${LESSON73_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=true","market.orders.simulated-payment-enabled=true","market.orders.timeout-sweep-enabled=false","spring.data.redis.port=${LESSON67_REDIS_PORT:1}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON73_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson73_verify\\?.*")
class OrderTimelineIntegrationTest {
 @Autowired OrderRepository orders;
 @Autowired OrderTimeoutSweep sweep;
 @Autowired ProductDetailCache cache;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired OrderService service;@Autowired ProductService products;
 @Autowired ProductRepository repository;@Autowired SessionService sessions;@Autowired PlatformTransactionManager transactions;@Autowired PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();String hash,sellerToken,buyerToken,otherToken;long product,seller,buyer,other;
 @BeforeAll void hash(){hash=encoder.encode("Campus2026!");}
 @BeforeEach void fixtures()throws Exception {
  assertTrue(System.getenv("LESSON73_DB_URL").contains("/campus_lesson73_verify?"));
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




 Timeline timeline(long id){return service.timeline(id,buyerToken);}
 void expired(long id){jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);}
 void paid(long id){service.simulatePayment(id,buyerToken);}
 void delivered(long id){paid(id);service.deliver(id,sellerToken);}
 void full(long id){delivered(id);service.complete(id,buyerToken);}
 void eventFailure(Runnable action){jdbc.execute("CREATE TRIGGER reject_order_event BEFORE INSERT ON order_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated event failure'");try{assertThrows(RuntimeException.class,action::run);}finally{jdbc.execute("DROP TRIGGER reject_order_event");}}
 void rollback(Runnable action){var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);tx.executeWithoutResult(s->{action.run();s.setRollbackOnly();});}
 @Test void creationHasOneBuyerEventAndDatabaseTime()throws Exception{long id=created();var t=timeline(id);assertEquals(1,t.events().size());var e=t.events().get(0);assertEquals(1,e.sequence());assertEquals("CREATED",e.action());assertNull(e.fromStatus());assertEquals("PENDING_PAYMENT",e.toStatus());assertEquals("BUYER",e.actorRole());assertEquals(buyer,e.actorId());assertEquals(t.order().createdAt(),e.occurredAt());assertNotNull(e.recordedAt());}
 @Test void fullFlowHasFourOrderedEventsAndBothActors()throws Exception{long id=created();full(id);var t=timeline(id);assertEquals("COMPLETED",t.order().status());assertEquals(List.of("CREATED","SIMULATED_PAYMENT","DELIVERED","COMPLETED"),t.events().stream().map(Event::action).toList());assertEquals(List.of(buyer,buyer,seller,buyer),t.events().stream().map(Event::actorId).toList());assertEquals(List.of(1,2,3,4),t.events().stream().map(Event::sequence).toList());assertEquals(t.order().paidAt(),t.events().get(1).occurredAt());assertEquals(t.order().deliveredAt(),t.events().get(2).occurredAt());assertEquals(t.order().completedAt(),t.events().get(3).occurredAt());}
 @Test void actorNicknameIsCapturedAtOperationTime()throws Exception{long id=created();jdbc.update("UPDATE users SET nickname='买家新昵称' WHERE id=?",buyer);paid(id);var events=timeline(id).events();assertEquals("order_buyer",events.get(0).actorNickname());assertEquals("买家新昵称",events.get(1).actorNickname());assertEquals("order_buyer",timeline(id).order().buyer().nickname());jdbc.update("UPDATE users SET nickname='后来改名' WHERE id=?",buyer);assertEquals("买家新昵称",timeline(id).events().get(1).actorNickname());}
 @Test void creationReplayDoesNotAppend()throws Exception{String key=uuid();var first=service.create(product,new Create(0L,key),buyerToken);service.create(product,new Create(0L,key),buyerToken);assertEquals(1,timeline(first.order().id()).events().size());}
 @Test void completedOrderReplaysDoNotAppend()throws Exception{String key=uuid();long id=service.create(product,new Create(0L,key),buyerToken).order().id();full(id);service.create(product,new Create(0L,key),buyerToken);paid(id);service.deliver(id,sellerToken);service.complete(id,buyerToken);assertEquals(4,timeline(id).events().size());}
 @Test void cancelledOrderHasExactlyTwoEvents()throws Exception{long id=created();service.cancel(id,buyerToken);service.cancel(id,buyerToken);assertEquals(List.of("CREATED","CANCELLED"),timeline(id).events().stream().map(Event::action).toList());}
 @Test void sweepTimeoutIsSystemRule()throws Exception{long id=created();expired(id);assertTrue(service.closeExpired(id));assertFalse(service.closeExpired(id));var e=timeline(id).events().get(1);assertEquals("TIMEOUT_CLOSED",e.action());assertEquals("SYSTEM",e.actorRole());assertNull(e.actorId());assertEquals("CLOSED_TIMEOUT",timeline(id).order().status());}
 @Test void paymentTriggeredTimeoutCommitsSystemEventAndReturns409()throws Exception{long id=created();expired(id);check(post("/api/orders/"+id+"/simulate-payment").header("Authorization",buyerToken),409);var t=timeline(id);assertEquals("CLOSED_TIMEOUT",t.order().status());assertEquals("SYSTEM",t.events().get(1).actorRole());assertNull(t.events().get(1).actorId());}
 @Test void cancellationTriggeredTimeoutIsAlsoSystem()throws Exception{long id=created();expired(id);service.cancel(id,buyerToken);assertEquals("TIMEOUT_CLOSED",timeline(id).events().get(1).action());}
 @Test void onlyParticipantsCanReadTimeline()throws Exception{long id=created();check(get("/api/orders/"+id+"/timeline").header("Authorization",otherToken),404);check(get("/api/orders/"+id+"/timeline"),401);check(get("/api/orders/"+id+"/timeline").header("Authorization",sellerToken),200);}
 @Test void timelineDoesNotRevealMissingOrOtherOrders()throws Exception{long id=created();var a=check(get("/api/orders/"+id+"/timeline").header("Authorization",otherToken),404);var b=check(get("/api/orders/999999999/timeline").header("Authorization",otherToken),404);assertEquals(a,b);}
 @Test void invalidTimelineIdIsRejected()throws Exception{for(String id:List.of("0","-1","abc","9007199254740992"))check(get("/api/orders/"+id+"/timeline").header("Authorization",buyerToken),400);}
 @Test void timelineUsesNoStore()throws Exception{long id=created();var r=mvc.perform(get("/api/orders/"+id+"/timeline").header("Authorization",buyerToken)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));}
 @Test void privateReadDoesNotCloseExpiredOrAppend()throws Exception{long id=created();expired(id);var before=jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id);assertEquals(1,timeline(id).events().size());assertEquals(before,jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id));}
 @Test void rollbackCreateRemovesOrderEventAndProductVersion()throws Exception{rollback(()->service.create(product,new Create(0L,uuid()),buyerToken));assertEquals(0,count());assertEquals(0,version());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_events",Long.class));}
 @Test void rollbackPaymentRestoresBothStateAndEvents()throws Exception{long id=created();rollback(()->paid(id));assertEquals("PENDING_PAYMENT",timeline(id).order().status());assertEquals(1,timeline(id).events().size());assertEquals(ProductStatus.ON_SALE,repository.findById(product).orElseThrow().status());assertEquals(1,version());}
 @Test void rollbackCancellationRestoresEvents()throws Exception{long id=created();rollback(()->service.cancel(id,buyerToken));assertEquals("PENDING_PAYMENT",timeline(id).order().status());assertEquals(1,timeline(id).events().size());}
 @Test void rollbackDeliveryRestoresEvents()throws Exception{long id=created();paid(id);rollback(()->service.deliver(id,sellerToken));assertEquals("PAID",timeline(id).order().status());assertEquals(2,timeline(id).events().size());}
 @Test void rollbackCompletionRestoresEvents()throws Exception{long id=created();delivered(id);rollback(()->service.complete(id,buyerToken));assertEquals("DELIVERED",timeline(id).order().status());assertEquals(3,timeline(id).events().size());}
 @Test void rollbackTimeoutRestoresReservation()throws Exception{long id=created();expired(id);rollback(()->service.closeExpired(id));assertEquals("PENDING_PAYMENT",timeline(id).order().status());assertEquals(1,timeline(id).events().size());assertEquals(1,version());}
 @Test void eventWriteFailureRollsBackCreation(){eventFailure(()->service.create(product,new Create(0L,uuid()),buyerToken));assertEquals(0,count());assertEquals(0,version());}
 @Test void eventWriteFailureRollsBackPaymentAndProduct()throws Exception{long id=created();eventFailure(()->paid(id));assertEquals("PENDING_PAYMENT",timeline(id).order().status());assertEquals(1,timeline(id).events().size());assertEquals(ProductStatus.ON_SALE,repository.findById(product).orElseThrow().status());assertEquals(1,version());}
 @Test void eventWriteFailureRollsBackTimeout()throws Exception{long id=created();expired(id);eventFailure(()->service.closeExpired(id));assertEquals("PENDING_PAYMENT",timeline(id).order().status());assertEquals(1,timeline(id).events().size());assertEquals(1,version());}
 @Test void failedUnauthorizedMutationHasNoEvent()throws Exception{long id=created();check(post("/api/orders/"+id+"/simulate-payment").header("Authorization",sellerToken),404);check(post("/api/orders/"+id+"/deliver").header("Authorization",otherToken),404);assertEquals(1,timeline(id).events().size());}
 @Test void appendMustRunInBusinessTransaction()throws Exception{long id=created();assertThrows(IllegalStateException.class,()->orders.append(id,"CANCELLED","PENDING_PAYMENT","CANCELLED","BUYER",buyer,"buyer"));}
 @Test void duplicateEventActionAndSequenceAreRejected()throws Exception{long id=created();assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO order_events SELECT * FROM order_events WHERE order_id=?",id));assertEquals(1,timeline(id).events().size());}
 @Test void malformedTransitionAndActorAreRejected()throws Exception{long id=created();for(String suffix:List.of("'SIMULATED_PAYMENT',NULL,'PAID','BUYER',"+buyer,"'SIMULATED_PAYMENT','PENDING_PAYMENT','PAID','BUYER',NULL","'TIMEOUT_CLOSED','PENDING_PAYMENT','CLOSED_TIMEOUT','SYSTEM',"+buyer)){assertThrows(RuntimeException.class,()->jdbc.execute("INSERT INTO order_events(order_id,event_no,action,from_status,to_status,actor_role,actor_id,actor_nickname,occurred_at) VALUES("+id+",2,"+suffix+",'fixture',CURRENT_TIMESTAMP(6))"));}}
 @Test void futureOccurrenceBeforeRecordingIsRejected()throws Exception{long id=created();assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO order_events(order_id,event_no,action,from_status,to_status,actor_role,actor_id,actor_nickname,occurred_at) VALUES(?,2,'CANCELLED','PENDING_PAYMENT','CANCELLED','BUYER',?,'fixture',CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR)",id,buyer));}
 @Test void concurrentPaymentsProduceOnlyOnePaymentEvent()throws Exception{long id=created();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.simulatePayment(id,buyerToken));var b=pool.submit(()->service.simulatePayment(id,buyerToken));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(2,timeline(id).events().size());}finally{pool.shutdownNow();}}
 @Test void concurrentDeliveriesProduceOnlyOneDeliveryEvent()throws Exception{long id=created();paid(id);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.deliver(id,sellerToken));var b=pool.submit(()->service.deliver(id,sellerToken));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(3,timeline(id).events().size());}finally{pool.shutdownNow();}}
 @Test void concurrentReceiptsProduceOnlyOneCompletionEvent()throws Exception{long id=created();delivered(id);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.complete(id,buyerToken));var b=pool.submit(()->service.complete(id,buyerToken));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(4,timeline(id).events().size());}finally{pool.shutdownNow();}}
 @Test void orderAndEventsShareRepeatableReadSnapshot()throws Exception{long id=created();delivered(id);var pool=Executors.newSingleThreadExecutor();try{var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(s->{var before=timeline(id);try{pool.submit(()->service.complete(id,buyerToken)).get(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}assertEquals(before,timeline(id));assertEquals(3,timeline(id).events().size());});assertEquals(4,timeline(id).events().size());}finally{pool.shutdownNow();}}
 @Test void historySnapshotIsExplicitAndLaterProgressCanContinue()throws Exception{long id=created();paid(id);/* This fixture represents an order from before notifications existed. */jdbc.update("DELETE FROM notification_outbox WHERE category='ORDER' AND resource_id=?",id);jdbc.update("DELETE FROM order_events WHERE order_id=?",id);jdbc.update("INSERT INTO order_events(order_id,event_no,action,to_status,actor_role,actor_nickname,occurred_at) VALUES(?,1,'HISTORY_SNAPSHOT','PAID','SYSTEM','迁移补录',CURRENT_TIMESTAMP(6))",id);service.deliver(id,sellerToken);service.complete(id,buyerToken);var t=timeline(id);assertEquals(3,t.events().size());assertEquals("HISTORY_SNAPSHOT",t.events().get(0).action());assertNull(t.events().get(0).actorId());assertEquals("COMPLETED",t.order().status());}
}
