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

@SpringBootTest(properties={"spring.datasource.url=${LESSON60_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON60_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson60_verify\\?.*")
class PurchaseIntentMessageWithdrawalIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON60_DB_URL").contains("/campus_lesson60_verify?"));
  for(String table:List.of("purchase_intent_messages","purchase_intents","product_draft_backups","product_publications","product_comments","product_favorites","products","product_images","login_sessions","users"))jdbc.update("DELETE FROM "+table);
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

 List<Long> ids=new ArrayList<>();
 void seedMixed()throws Exception {
  ids.clear();for(int i=0;i<8;i++){long p=seed("状态教材"+i,1000+i,seller,"ON_SALE",0);long id=check(create(p,buyerToken,0),201).at("/intent/id").asLong();ids.add(id);if(i==3||i==4)check(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken),200);if(i>=6)check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);if(i<2)check(create(p,otherToken,0),201);}
 }
 MockHttpServletRequestBuilder filtered(String t,String role,String status,int n,int size) { return page(t,role,n,size).param("status",status); }
 List<Long> rowIds(JsonNode p) { var list=new ArrayList<Long>();for(var r:p.get("items"))list.add(r.get("id").asLong());return list; }



 String key(int i){return String.format("00000000-0000-4000-8000-%012d",i);}
 long opened()throws Exception {long id=check(create(product,buyerToken,7),201).at("/intent/id").asLong();check(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken),200);return id;}
 MockHttpServletRequestBuilder send(long id,String t,String text,String k)throws Exception {return post("/api/purchase-intents/"+id+"/messages").header("Authorization","Bearer "+t).contentType("application/json").content(mapper.writeValueAsString(Map.of("content",text,"requestId",k)));}
 MockHttpServletRequestBuilder messages(long id,String t,int page,int size){return get("/api/purchase-intents/"+id+"/messages").header("Authorization","Bearer "+t).param("page",""+page).param("size",""+size);}

 MockHttpServletRequestBuilder withdraw(long id,long messageId,String t){return post("/api/purchase-intents/"+id+"/messages/"+messageId+"/withdraw").header("Authorization","Bearer "+t);}
 long message(long id,String t,int key)throws Exception {return check(send(id,t,"秘密正文"+key,key(key)),201).at("/message/id").asLong();}
 @Test void senderWithdrawsAndResponseMasksContent()throws Exception {long id=opened(),m=message(id,buyerToken,1);var r=check(withdraw(id,m,buyerToken),200);assertFalse(r.get("replayed").asBoolean());assertTrue(r.at("/message/withdrawn").asBoolean());assertTrue(r.at("/message/content").isNull());assertFalse(r.at("/message/withdrawnAt").isNull());assertEquals(m,r.at("/message/id").asLong());assertEquals(1,check(messages(id,buyerToken,1,10),200).get("total").asInt());}
 @Test void otherParticipantCannotWithdrawSendersMessage()throws Exception {long id=opened(),m=message(id,buyerToken,1);assertEquals("INTENT_MESSAGE_FORBIDDEN",check(withdraw(id,m,sellerToken),403).get("code").asText());assertFalse(check(messages(id,buyerToken,1,10),200).at("/items/0/withdrawn").asBoolean());}
 @Test void strangerAndMissingIntentAreIndistinguishable()throws Exception {long id=opened(),m=message(id,buyerToken,1);assertEquals(check(withdraw(id,m,otherToken),404),check(withdraw(9007199254740991L,m,otherToken),404));}
 @Test void messageMustBelongToPathIntent()throws Exception {long id=opened(),m=message(id,buyerToken,1);long p=seed("另一件商品",2000,seller,"ON_SALE",0);long otherId=check(create(p,buyerToken,0),201).at("/intent/id").asLong();check(post("/api/purchase-intents/"+otherId+"/accept").header("Authorization","Bearer "+sellerToken),200);assertEquals("INTENT_MESSAGE_NOT_FOUND",check(withdraw(otherId,m,buyerToken),404).get("code").asText());check(withdraw(id,9007199254740991L,buyerToken),404);}
 @Test void anonymousInvalidTokenAndUnsafeIdsRejected()throws Exception {long id=opened(),m=message(id,buyerToken,1);check(post("/api/purchase-intents/"+id+"/messages/"+m+"/withdraw"),401);check(withdraw(id,m,"invalid-token"),401);for(long n:List.of(0L,-1L,9007199254740992L)){check(withdraw(n,m,buyerToken),400);check(withdraw(id,n,buyerToken),400);}}
 @Test void repeatedWithdrawalKeepsOriginalTimestamp()throws Exception {long id=opened(),m=message(id,buyerToken,1);var first=check(withdraw(id,m,buyerToken),200);var replay=check(withdraw(id,m,buyerToken),200);assertTrue(replay.get("replayed").asBoolean());assertEquals(first.get("message"),replay.get("message"));}
 @Test void concurrentWithdrawalsHaveOneFirstAndOneReplay()throws Exception {long id=opened(),m=message(id,buyerToken,1);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();return check(withdraw(id,m,buyerToken),200);});var b=pool.submit(()->{start.await();return check(withdraw(id,m,buyerToken),200);});start.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);assertNotEquals(first.get("replayed").asBoolean(),second.get("replayed").asBoolean());assertEquals(first.get("message"),second.get("message"));}finally{pool.shutdownNow();}}
 @Test void originalSendReplayCannotRestoreWithdrawnContent()throws Exception {long id=opened(),m=message(id,buyerToken,1);var withdrawn=check(withdraw(id,m,buyerToken),200).get("message");var replay=check(send(id,buyerToken,"秘密正文1",key(1)),200);assertTrue(replay.get("replayed").asBoolean());assertEquals(withdrawn,replay.get("message"));assertEquals(1,check(messages(id,buyerToken,1,10),200).get("total").asInt());}
 @Test void changedPayloadStillConflictsAfterWithdrawal()throws Exception {long id=opened(),m=message(id,buyerToken,1);check(withdraw(id,m,buyerToken),200);check(send(id,buyerToken,"换内容",key(1)),409);assertTrue(check(messages(id,buyerToken,1,10),200).at("/items/0/content").isNull());}
 @Test void sendReplayAndWithdrawalRaceNeverResurrectRow()throws Exception {long id=opened(),m=message(id,buyerToken,1);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->check(withdraw(id,m,buyerToken),200));var b=pool.submit(()->check(send(id,buyerToken,"秘密正文1",key(1)),200));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);var last=check(messages(id,buyerToken,1,10),200);assertTrue(last.at("/items/0/withdrawn").asBoolean());assertTrue(last.at("/items/0/content").isNull());assertEquals(1,last.get("total").asInt());}finally{pool.shutdownNow();}}
 @Test void paginationOrderAndTotalsDoNotChangeOnWithdrawal()throws Exception {long id=opened();var ids=new ArrayList<Long>();for(int i=1;i<=6;i++)ids.add(message(id,buyerToken,i));jdbc.update("UPDATE purchase_intent_messages SET created_at='2026-01-01 00:00:00'");var before=check(messages(id,buyerToken,3,2),200);check(withdraw(id,ids.get(4),buyerToken),200);var after=check(messages(id,buyerToken,3,2),200);assertEquals(before.get("total"),after.get("total"));assertEquals(before.get("page"),after.get("page"));assertEquals(before.at("/items/0/id"),after.at("/items/0/id"));assertTrue(after.at("/items/0/withdrawn").asBoolean());}
 @Test void bothParticipantsNeverReceiveOriginalContentAfterWithdrawal()throws Exception {long id=opened(),m=message(id,buyerToken,1);check(withdraw(id,m,buyerToken),200);for(String t:List.of(buyerToken,sellerToken)){var body=check(messages(id,t,1,10),200);assertFalse(body.toString().contains("秘密正文1"));assertTrue(body.at("/items/0/content").isNull());}}
 @Test void rawContentAndUniqueKeyRemainForIdempotencyOnly()throws Exception {long id=opened(),m=message(id,buyerToken,1);var before=jdbc.queryForList("SELECT id,intent_id,sender_id,request_id,content,created_at FROM purchase_intent_messages");check(withdraw(id,m,buyerToken),200);assertEquals(before,jdbc.queryForList("SELECT id,intent_id,sender_id,request_id,content,created_at FROM purchase_intent_messages"));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO purchase_intent_messages(intent_id,sender_id,request_id,content) VALUES(?,?,?,'重复')",id,buyer,key(1)));}
 void assertWithdrawalConstraint(String sql,long messageId) {
  var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,messageId));
  assertInstanceOf(java.sql.SQLException.class,failure.getMostSpecificCause());
  var cause=(java.sql.SQLException)failure.getMostSpecificCause();
  assertEquals(3819,cause.getErrorCode());
  assertTrue(cause.getMessage().contains("chk_intent_message_withdrawal"));
 }
 @Test void databaseEnforcesWithdrawalTimeConsistency()throws Exception {
  long id=opened(),m=message(id,buyerToken,1);
  assertWithdrawalConstraint("UPDATE purchase_intent_messages SET is_withdrawn=TRUE WHERE id=?",m);
  assertWithdrawalConstraint("UPDATE purchase_intent_messages SET withdrawn_at=CURRENT_TIMESTAMP(6) WHERE id=?",m);
  assertFalse(check(messages(id,buyerToken,1,10),200).at("/items/0/withdrawn").asBoolean());
 }
 @Test void senderCanWithdrawAfterProductSold()throws Exception {long id=opened(),m=message(id,buyerToken,1);jdbc.update("UPDATE products SET status='SOLD',version=version+1 WHERE id=?",product);check(withdraw(id,m,buyerToken),200);assertTrue(check(messages(id,buyerToken,1,10),200).at("/items/0/withdrawn").asBoolean());}
 @Test void allBusinessResponsesAreNoStore()throws Exception {long id=opened(),m=message(id,buyerToken,1);for(var r:List.of(withdraw(id,m,buyerToken),withdraw(id,m,sellerToken),withdraw(id,m,otherToken),withdraw(id,9007199254740991L,buyerToken),withdraw(id,0,buyerToken)))assertEquals("no-store",mvc.perform(r).andReturn().getResponse().getHeader("Cache-Control"));}
 @Test void withdrawalNeverChangesProductsOrIntentStates()throws Exception {long id=opened(),m=message(id,buyerToken,1);var products=jdbc.queryForList("SELECT * FROM products ORDER BY id");var intents=jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id");check(withdraw(id,m,buyerToken),200);assertEquals(products,jdbc.queryForList("SELECT * FROM products ORDER BY id"));assertEquals(intents,jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id"));}
 @Test void activeDefaultsAndCurrentNicknameRemainExplicit()throws Exception {long id=opened(),m=message(id,buyerToken,1);var active=check(messages(id,buyerToken,1,10),200).at("/items/0");assertFalse(active.get("withdrawn").asBoolean());assertTrue(active.get("withdrawnAt").isNull());check(withdraw(id,m,buyerToken),200);jdbc.update("UPDATE users SET nickname='发送者新昵称' WHERE id=?",buyer);var row=check(messages(id,sellerToken,1,10),200).at("/items/0");assertEquals("发送者新昵称",row.at("/sender/nickname").asText());assertTrue(row.get("content").isNull());assertFalse(row.has("originalContent"));}
}
