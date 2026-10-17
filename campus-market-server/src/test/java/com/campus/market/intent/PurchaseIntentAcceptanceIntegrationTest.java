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

@SpringBootTest(properties={"spring.datasource.url=${LESSON55_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON55_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson55_verify\\?.*")
class PurchaseIntentAcceptanceIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON55_DB_URL").contains("/campus_lesson55_verify?"));
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
 MockHttpServletRequestBuilder accept(long id,String token) { return post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+token); }
 MockHttpServletRequestBuilder cancel(long id,String token) { return post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+token); }
 @Test void acceptanceRequiresLogin()throws Exception { long id=intention();check(post("/api/purchase-intents/"+id+"/accept"),401);assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id)); }
 @Test void sellerAcceptsAndRecordsTime()throws Exception { long id=intention();var r=check(accept(id,sellerToken),200);assertFalse(r.get("replayed").asBoolean());assertEquals("ACCEPTED",r.at("/intent/status").asText());assertFalse(r.at("/intent/acceptedAt").isNull());assertTrue(r.at("/intent/cancelledAt").isNull());assertEquals(1,count()); }
 @Test void repeatedAcceptanceKeepsSameRecordAndTimestamp()throws Exception { long id=intention();var first=check(accept(id,sellerToken),200);var next=check(accept(id,sellerToken),200);assertTrue(next.get("replayed").asBoolean());assertEquals(first.get("intent"),next.get("intent")); }
 @Test void buyerCannotAcceptOwnPurchaseRequest()throws Exception { long id=intention();assertEquals("PURCHASE_INTENT_NOT_FOUND",check(accept(id,buyerToken),404).get("code").asText()); }
 @Test void unrelatedUserCannotSpoofSellerId()throws Exception { long id=intention();check(accept(id,otherToken).param("sellerId",""+seller).contentType("application/json").content("{\"sellerId\":"+seller+"}"),404);assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id)); }
 @Test void nonexistentAndNonOwnedIntentsShare404()throws Exception { long id=intention();assertEquals(check(accept(id,otherToken),404),check(accept(9007199254740991L,sellerToken),404)); }
 @Test void invalidIdsCannotWrite()throws Exception { intention();var before=jdbc.queryForList("SELECT * FROM purchase_intents");for(long id:new long[]{0,-1,9007199254740992L})check(accept(id,sellerToken),400);check(post("/api/purchase-intents/1.5/accept").header("Authorization","Bearer "+sellerToken),400);assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intents")); }
 @Test void concurrentAcceptsHaveOneTransitionAndOneReplay()throws Exception {
  long id=intention();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {var tasks=new ArrayList<Future<JsonNode>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();return check(accept(id,sellerToken),200);}));start.countDown();var a=tasks.get(0).get(15,TimeUnit.SECONDS);var b=tasks.get(1).get(15,TimeUnit.SECONDS);assertNotEquals(a.get("replayed").asBoolean(),b.get("replayed").asBoolean());assertEquals(a.get("intent"),b.get("intent"));}finally {pool.shutdownNow();}
 }
 @Test void cancelledIntentCannotBeAccepted()throws Exception { long id=intention();var cancelled=check(cancel(id,buyerToken),200).get("intent");assertEquals("PURCHASE_INTENT_CHANGED",check(accept(id,sellerToken),409).get("code").asText());assertEquals(cancelled,check(page(sellerToken,"SELLER",1,5),200).at("/items/0")); }
 @Test void acceptedIntentCannotBeCancelled()throws Exception { long id=intention();var accepted=check(accept(id,sellerToken),200).get("intent");check(cancel(id,buyerToken),409);assertEquals(accepted,check(page(buyerToken,"BUYER",1,5),200).at("/items/0")); }
 @Test void acceptAndCancelRaceHasOnlyOneWinner()throws Exception {
  long id=intention();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {var a=pool.submit(()->{start.await();return mvc.perform(accept(id,sellerToken)).andReturn().getResponse().getStatus();});var b=pool.submit(()->{start.await();return mvc.perform(cancel(id,buyerToken)).andReturn().getResponse().getStatus();});start.countDown();var statuses=new ArrayList<>(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);var row=check(page(sellerToken,"SELLER",1,5),200).at("/items/0");assertTrue(List.of("ACCEPTED","CANCELLED").contains(row.get("status").asText()));assertNotEquals(row.get("acceptedAt").isNull(),row.get("cancelledAt").isNull());}finally {pool.shutdownNow();}
 }
 @Test void offShelfAndSoldBlockNewAcceptance()throws Exception { long id=intention();for(String status:List.of("OFF_SHELF","SOLD")){jdbc.update("UPDATE products SET status=? WHERE id=?",status,product);assertEquals("PRODUCT_UNAVAILABLE",check(accept(id,sellerToken),409).get("code").asText());}assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id)); }
 @Test void replayWorksAfterProductIsSoldAndCreateReturnsAcceptedHistory()throws Exception { long id=intention();var first=check(accept(id,sellerToken),200).get("intent");jdbc.update("UPDATE products SET status='SOLD',version=version+1 WHERE id=?",product);assertEquals(first,check(accept(id,sellerToken),200).get("intent"));var replay=check(create(product,buyerToken,7),200);assertTrue(replay.get("replayed").asBoolean());assertEquals(first,replay.get("intent")); }
 @Test void acceptanceIsNonExclusiveAndDoesNotReserveProduct()throws Exception { long id=intention();long another=check(create(product,otherToken,7),201).at("/intent/id").asLong();var before=jdbc.queryForMap("SELECT * FROM products WHERE id=?",product);check(accept(id,sellerToken),200);check(accept(another,sellerToken),200);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intents WHERE status='ACCEPTED'",Integer.class));assertEquals(before,jdbc.queryForMap("SELECT * FROM products WHERE id=?",product)); }
 @Test void acceptancePreservesSnapshotAfterEditAndBothPartiesKeepPagination()throws Exception {
  long id=intention();var snapshot=check(page(buyerToken,"BUYER",1,5),200).at("/items/0");jdbc.update("UPDATE products SET price_in_cents=9900,version=version+1 WHERE id=?",product);var before=jdbc.queryForMap("SELECT * FROM products WHERE id=?",product);var accepted=check(accept(id,sellerToken),200).get("intent");for(String field:List.of("id","productId","productTitleSnapshot","priceInCentsSnapshot","productVersionSnapshot","buyer","seller","createdAt"))assertEquals(snapshot.get(field),accepted.get(field));assertEquals(before,jdbc.queryForMap("SELECT * FROM products WHERE id=?",product));for(var query:List.of(page(buyerToken,"BUYER",99,5),page(sellerToken,"SELLER",1,5))){var p=check(query,200);assertEquals(1,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals("ACCEPTED",p.at("/items/0/status").asText());}
 }
 @Test void noStoreAndSchemaTimestampConsistency()throws Exception {
  long id=intention();assertTrue(check(page(buyerToken,"BUYER",1,5),200).at("/items/0/acceptedAt").isNull());assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET status='ACCEPTED' WHERE id=?",id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET accepted_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));var r=mvc.perform(accept(id,sellerToken)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-store",mvc.perform(accept(id,otherToken)).andReturn().getResponse().getHeader("Cache-Control"));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE purchase_intents SET accepted_at=NULL WHERE id=?",id));
 }
}
