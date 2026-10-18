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
@SpringBootTest(properties={"spring.datasource.url=${LESSON72_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=true","market.orders.simulated-payment-enabled=true","market.orders.timeout-sweep-enabled=false","spring.data.redis.port=${LESSON67_REDIS_PORT:1}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON72_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson72_verify\\?.*")
class OrderQueryIntegrationTest {
 @Autowired OrderTimeoutSweep sweep;
 @Autowired ProductDetailCache cache;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired OrderService service;@Autowired ProductService products;
 @Autowired ProductRepository repository;@Autowired SessionService sessions;@Autowired PlatformTransactionManager transactions;@Autowired PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();String hash,sellerToken,buyerToken,otherToken;long product,seller,buyer,other;
 @BeforeAll void hash(){hash=encoder.encode("Campus2026!");}
 @BeforeEach void fixtures()throws Exception {
  assertTrue(System.getenv("LESSON72_DB_URL").contains("/campus_lesson72_verify?"));
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



 long order(String state,long price,long buyerId,String auth)throws Exception{
  long p=repository.insert("订单筛选教材","",price,seller,null,"BOOK");long id=service.create(p,new Create(0L,uuid()),auth).order().id();
  switch(state){
   case "CANCELLED"->service.cancel(id,auth);
   case "CLOSED_TIMEOUT"->{jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);service.closeExpired(id);}
   case "PAID","DELIVERED","COMPLETED"->{service.simulatePayment(id,auth);if(!"PAID".equals(state))service.deliver(id,sellerToken);if("COMPLETED".equals(state))service.complete(id,auth);}
  }
  return id;
 }
 void six()throws Exception{for(String state:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(state,3500,buyer,buyerToken);}
 JsonNode page(String query,String token)throws Exception{return check(get("/api/orders"+query).header("Authorization",token),200);}
 @Test void emptySummaryContainsAllZeroFields()throws Exception{var r=page("",buyerToken);assertEquals("ALL",r.get("status").asText());assertEquals(0,r.get("total").asLong());assertEquals(8,r.get("summary").size());for(var field:r.get("summary"))assertEquals(0,field.asLong());assertEquals(1,r.get("totalPages").asInt());}
 @Test void allSixStatesAreCountedAndCompletedMoneyOnly()throws Exception{six();var r=page("",buyerToken);assertEquals(6,r.get("total").asLong());var s=r.get("summary");assertEquals(6,s.get("total").asLong());for(String key:List.of("pendingPayment","paid","delivered","completed","cancelled","closedTimeout"))assertEquals(1,s.get(key).asLong());assertEquals(3500,s.get("completedAmountInCents").asLong());}
 @Test void eachStatusFiltersSqlResultsWithoutNarrowingSummary()throws Exception{six();for(String state:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT")){var r=page("?status="+state,buyerToken);assertEquals(state,r.get("status").asText());assertEquals(1,r.get("total").asLong());assertEquals(1,r.get("items").size());assertEquals(state,r.get("items").get(0).get("status").asText());assertEquals(6,r.get("summary").get("total").asLong());}}
 @Test void summaryIncludesEveryPageNotOnlyVisibleRows()throws Exception{for(int n=0;n<12;n++)order("COMPLETED",100+n,buyer,buyerToken);var first=page("?status=COMPLETED&page=1&size=10",buyerToken);var second=page("?status=COMPLETED&page=2&size=10",buyerToken);assertEquals(10,first.get("items").size());assertEquals(2,second.get("items").size());assertEquals(12,second.get("total").asLong());assertEquals(first.get("summary"),second.get("summary"));assertEquals(1266,second.get("summary").get("completedAmountInCents").asLong());}
 @Test void buyerAndSellerScopesCannotReadOtherPeoplesOrders()throws Exception{order("COMPLETED",3500,buyer,buyerToken);order("PAID",5800,other,otherToken);assertEquals(1,page("",buyerToken).get("summary").get("total").asLong());assertEquals(1,page("",otherToken).get("summary").get("total").asLong());assertEquals(2,page("?role=SELLER",sellerToken).get("summary").get("total").asLong());assertEquals(0,page("",sellerToken).get("summary").get("total").asLong());assertEquals(0,page("?role=SELLER",buyerToken).get("summary").get("total").asLong());}
 @Test void sellerFilterIncludesOnlyOwnMatchingOrders()throws Exception{six();var r=page("?role=SELLER&status=DELIVERED",sellerToken);assertEquals(1,r.get("items").size());assertEquals(seller,r.get("items").get(0).get("seller").get("id").asLong());assertEquals(6,r.get("summary").get("total").asLong());}
 @Test void invalidStatusValuesAndInjectionAreRejected()throws Exception{for(String status:List.of("paid","UNKNOWN","ALL ","COMPLETED' OR 1=1")){var request=get("/api/orders").param("status",status).header("Authorization",buyerToken);check(request,400);}assertEquals(0,count());}
 @Test void anonymousCannotReadStatistics(){assertThrows(com.campus.market.auth.LoginRequiredException.class,()->service.page("BUYER","ALL",1,10,null));}
 @Test void invalidSessionCannotReadStatistics()throws Exception{check(get("/api/orders?status=COMPLETED").header("Authorization","Bearer "+"A".repeat(43)),401);}
 @Test void emptyFilteredResultStillReturnsWholeRoleSummary()throws Exception{order("PAID",3500,buyer,buyerToken);var r=page("?status=COMPLETED&page=20",buyerToken);assertEquals(0,r.get("items").size());assertEquals(0,r.get("total").asLong());assertEquals(1,r.get("page").asInt());assertEquals(1,r.get("summary").get("paid").asLong());}
 @Test void pageClampsAfterASelectedOrderChangesState()throws Exception{long last=0;for(int n=0;n<11;n++)last=order("PENDING_PAYMENT",3500,buyer,buyerToken);assertEquals(2,page("?status=PENDING_PAYMENT&page=2",buyerToken).get("page").asInt());service.cancel(last,buyerToken);var r=page("?status=PENDING_PAYMENT&page=2",buyerToken);assertEquals(1,r.get("page").asInt());assertEquals(10,r.get("total").asLong());assertEquals(11,r.get("summary").get("total").asLong());assertEquals(1,r.get("summary").get("cancelled").asLong());}
 @Test void completionMovesCountsAndAddsSnapshotAmount()throws Exception{long id=order("DELIVERED",5801,buyer,buyerToken);var before=page("?status=DELIVERED",buyerToken);assertEquals(0,before.get("summary").get("completedAmountInCents").asLong());service.complete(id,buyerToken);var after=page("?status=DELIVERED",buyerToken);assertEquals(0,after.get("total").asLong());assertEquals(1,after.get("summary").get("completed").asLong());assertEquals(5801,after.get("summary").get("completedAmountInCents").asLong());}
 @Test void cancellationAndTimeoutAreNeverCountedAsCompletedAmount()throws Exception{order("CANCELLED",99999999,buyer,buyerToken);order("CLOSED_TIMEOUT",99999999,buyer,buyerToken);var r=page("",buyerToken);assertEquals(2,r.get("summary").get("total").asLong());assertEquals(0,r.get("summary").get("completedAmountInCents").asLong());}
 @Test void completedAmountUsesOrderSnapshotsRatherThanCurrentProducts()throws Exception{long id=order("COMPLETED",3500,buyer,buyerToken);long p=service.detail(id,buyerToken).productId();jdbc.update("UPDATE products SET price_in_cents=9999 WHERE id=?",p);assertEquals(3500,page("",buyerToken).get("summary").get("completedAmountInCents").asLong());}
 @Test void completedAmountAccumulatesBeyondAnIntWithoutOverflow()throws Exception{for(int n=0;n<22;n++)order("COMPLETED",99999999,buyer,buyerToken);assertEquals(2199999978L,page("",buyerToken).get("summary").get("completedAmountInCents").asLong());}
 @Test void duplicateCompletionDoesNotDoubleCountMoney()throws Exception{long id=order("COMPLETED",3500,buyer,buyerToken);service.complete(id,buyerToken);service.deliver(id,sellerToken);assertEquals(3500,page("",buyerToken).get("summary").get("completedAmountInCents").asLong());assertEquals(1,page("",buyerToken).get("summary").get("completed").asLong());}
 @Test void rolledBackCompletionDoesNotAffectSummary()throws Exception{long id=order("DELIVERED",3500,buyer,buyerToken);new TransactionTemplate(transactions).executeWithoutResult(tx->{service.complete(id,buyerToken);tx.setRollbackOnly();});var r=page("",buyerToken);assertEquals(1,r.get("summary").get("delivered").asLong());assertEquals(0,r.get("summary").get("completedAmountInCents").asLong());}
 @Test void summaryAndItemsShareTheSameRepeatableReadSnapshot()throws Exception{long id=order("DELIVERED",3500,buyer,buyerToken);var pool=Executors.newSingleThreadExecutor();try{var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(state->{var before=service.page("BUYER","DELIVERED",1,10,buyerToken);try{pool.submit(()->service.complete(id,buyerToken)).get(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}var same=service.page("BUYER","DELIVERED",1,10,buyerToken);assertEquals(before.summary(),same.summary());assertEquals(1,same.total());assertEquals("DELIVERED",same.items().get(0).status());});var fresh=service.page("BUYER","COMPLETED",1,10,buyerToken);assertEquals(1,fresh.total());assertEquals(3500,fresh.summary().completedAmountInCents());}finally{pool.shutdownNow();}}
 @Test void listReadsDoNotCloseExpiredOrdersOrWriteAnything()throws Exception{long id=order("PENDING_PAYMENT",3500,buyer,buyerToken);jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);var before=jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id);page("?status=PENDING_PAYMENT",buyerToken);assertEquals(before,jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id));}
 @Test void equalCreationTimesUseIdDescendingAndPaginationHasNoDuplicates()throws Exception{List<Long> ids=new ArrayList<>();for(int n=0;n<12;n++)ids.add(order("COMPLETED",100,buyer,buyerToken));jdbc.update("UPDATE market_orders SET completed_at=NULL,delivered_at=NULL,status='PAID'");jdbc.update("UPDATE market_orders SET paid_at='2026-01-01 10:01:00',created_at='2026-01-01 10:00:00'");var one=page("?status=PAID&page=1",buyerToken);var two=page("?status=PAID&page=2",buyerToken);Set<Long> found=new HashSet<>();long previous=Long.MAX_VALUE;for(var result:List.of(one,two))for(var row:result.get("items")){long id=row.get("id").asLong();assertTrue(id<previous);assertTrue(found.add(id));previous=id;}assertEquals(12,found.size());}
 @Test void allStatusDefaultAndExplicitAllAreEquivalent()throws Exception{six();assertEquals(page("",buyerToken),page("?status=ALL",buyerToken));}
 @Test void statusFilterDoesNotBypassRoleAndPaginationValidation()throws Exception{for(String q:List.of("role=ADMIN&status=PAID","status=COMPLETED&page=0","status=PAID&size=21"))check(get("/api/orders?"+q).header("Authorization",buyerToken),400);}
 @Test void privateSummaryResponseIsNotCached()throws Exception{var r=mvc.perform(get("/api/orders?status=COMPLETED").header("Authorization",buyerToken)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));}

}
