package com.campus.market.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON54_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON54_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson54_verify\\?.*")
class PurchaseIntentCancellationIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON54_DB_URL").contains("/campus_lesson54_verify?"));
  for(String table:List.of("purchase_intents","product_draft_backups","product_publications","product_comments","product_favorites","products","product_images","login_sessions","users"))jdbc.update("DELETE FROM "+table);
  for(String n:List.of("seller_53","buyer_53","other_53"))check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!","nickname",n))),201);
  sellerToken=login("seller_53");buyerToken=login("buyer_53");otherToken=login("other_53");
  seller=user("seller_53");buyer=user("buyer_53");other=user("other_53");product=seed("九成新教材",3500L,seller,"ON_SALE",7);
 }
 long user(String n) { return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,n); }
 long seed(String title,long price,Long owner,String status,long version) {
  jdbc.update("INSERT INTO products(title,description,price_in_cents,seller_id,status,version,category_code) VALUES(?,'意向测试',?,?,?,?, 'BOOK')",title,price,owner,status,version);
  return jdbc.queryForObject("SELECT MAX(id) FROM products",Long.class);
 }
 MockHttpServletRequestBuilder create(long id,String token,long version) { return post("/api/products/"+id+"/purchase-intent").header("Authorization","Bearer "+token).contentType("application/json").content("{\"productVersion\":"+version+"}"); }
 MockHttpServletRequestBuilder page(String token,String role,int n,int size) { return get("/api/purchase-intents/mine").header("Authorization","Bearer "+token).param("role",role).param("page",""+n).param("size",""+size); }
 long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intents",Long.class); }

 long intention()throws Exception { return check(create(product,buyerToken,7),201).at("/intent/id").asLong(); }
 MockHttpServletRequestBuilder cancel(long id,String token) { return post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+token); }
 @Test void cancellationRequiresAuthentication()throws Exception { long id=intention();check(post("/api/purchase-intents/"+id+"/cancel"),401);assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id)); }
 @Test void buyerCancelsPendingIntentAndRecordsTime()throws Exception { long id=intention();var r=check(cancel(id,buyerToken),200);assertFalse(r.get("replayed").asBoolean());assertEquals("CANCELLED",r.at("/intent/status").asText());assertFalse(r.at("/intent/cancelledAt").isNull());assertEquals(1,count()); }
 @Test void repeatedCancellationReturnsSameRecordAndTime()throws Exception { long id=intention();var first=check(cancel(id,buyerToken),200);var again=check(cancel(id,buyerToken),200);assertTrue(again.get("replayed").asBoolean());assertEquals(first.get("intent"),again.get("intent"));assertEquals(1,count()); }
 @Test void sellerCannotCancelBuyerIntent()throws Exception { long id=intention();assertEquals("PURCHASE_INTENT_NOT_FOUND",check(cancel(id,sellerToken),404).get("code").asText());assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id)); }
 @Test void anotherBuyerCannotCancelOrSpoofOwnership()throws Exception { long id=intention();check(cancel(id,otherToken).param("buyerId",""+buyer).contentType("application/json").content("{\"buyerId\":"+buyer+"}"),404);assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id)); }
 @Test void missingIntentAndNonOwnedIntentHaveSameError()throws Exception { long id=intention();assertEquals(check(cancel(id,otherToken),404),check(cancel(9007199254740991L,buyerToken),404)); }
 @Test void invalidIntentIdsDoNotWrite()throws Exception { intention();var before=jdbc.queryForList("SELECT * FROM purchase_intents");for(long id:new long[]{0,-1,9007199254740992L})check(cancel(id,buyerToken),400);check(post("/api/purchase-intents/1.5/cancel").header("Authorization","Bearer "+buyerToken),400);assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intents")); }
 @Test void concurrentCancellationHasOneTransitionAndOneReplay()throws Exception {
  long id=intention();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {var tasks=new ArrayList<Future<JsonNode>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();return check(cancel(id,buyerToken),200);}));start.countDown();var first=tasks.get(0).get(15,TimeUnit.SECONDS);var second=tasks.get(1).get(15,TimeUnit.SECONDS);assertNotEquals(first.get("replayed").asBoolean(),second.get("replayed").asBoolean());assertEquals(first.get("intent"),second.get("intent"));assertEquals(1,count());}finally {pool.shutdownNow();}
 }
 @Test void cancellationDoesNotTouchProductsOrSnapshot()throws Exception {
  long id=intention();var products=jdbc.queryForList("SELECT * FROM products");var before=check(page(buyerToken,"BUYER",1,5),200).at("/items/0");var after=check(cancel(id,buyerToken),200).get("intent");for(String field:List.of("id","productId","productTitleSnapshot","priceInCentsSnapshot","productVersionSnapshot","buyer","seller","createdAt"))assertEquals(before.get(field),after.get(field));assertEquals(products,jdbc.queryForList("SELECT * FROM products"));
 }
 @Test void cancellationWorksAfterProductOffShelfOrSold()throws Exception {
  for(String status:List.of("OFF_SHELF","SOLD")){long id=seed("下架教材",2000,seller,"ON_SALE",0);long intent=check(create(id,buyerToken,0),201).at("/intent/id").asLong();jdbc.update("UPDATE products SET status=?,version=version+1 WHERE id=?",status,id);assertEquals("CANCELLED",check(cancel(intent,buyerToken),200).at("/intent/status").asText());assertEquals(status,jdbc.queryForObject("SELECT status FROM products WHERE id=?",String.class,id));}
 }
 @Test void repeatedCreateAfterCancellationDoesNotReopen()throws Exception { long id=intention();var cancelled=check(cancel(id,buyerToken),200);var replay=check(create(product,buyerToken,7),200);assertTrue(replay.get("replayed").asBoolean());assertEquals(cancelled.get("intent"),replay.get("intent"));assertEquals(1,count()); }
 @Test void bothPartiesKeepCancelledHistoryAndTotals()throws Exception {
  long id=intention();check(cancel(id,buyerToken),200);for(var query:List.of(page(buyerToken,"BUYER",99,5),page(sellerToken,"SELLER",1,5))){var p=check(query,200);assertEquals(1,p.get("total").asInt());assertEquals("CANCELLED",p.at("/items/0/status").asText());}assertEquals(0,check(page(otherToken,"BUYER",1,5),200).get("total").asInt());
 }
 @Test void cancellationResponsesNoStoreAndPendingTimeNull()throws Exception {
  long id=intention();assertTrue(check(page(buyerToken,"BUYER",1,5),200).at("/items/0/cancelledAt").isNull());var r=mvc.perform(cancel(id,buyerToken)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-store",mvc.perform(cancel(id,otherToken)).andReturn().getResponse().getHeader("Cache-Control"));
 }
 @Test void schemaEnforcesStatusTimestampConsistency()throws Exception {
  long id=intention();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET status='CANCELLED' WHERE id=?",id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET status='UNKNOWN' WHERE id=?",id));check(cancel(id,buyerToken),200);assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET cancelled_at=NULL WHERE id=?",id));
 }
}
