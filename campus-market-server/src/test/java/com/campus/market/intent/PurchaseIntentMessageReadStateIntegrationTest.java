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

@SpringBootTest(properties={"spring.datasource.url=${LESSON61_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON61_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson61_verify\\?.*")
class PurchaseIntentMessageReadStateIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON61_DB_URL").contains("/campus_lesson61_verify?"));
  for(String table:List.of("purchase_intent_message_reads","purchase_intent_messages","purchase_intents","product_draft_backups","product_publications","product_comments","product_favorites","products","product_images","login_sessions","users"))jdbc.update("DELETE FROM "+table);
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

 MockHttpServletRequestBuilder state(long id,String t){return get("/api/purchase-intents/"+id+"/messages/read-state").header("Authorization","Bearer "+t);}
 MockHttpServletRequestBuilder mark(long id,long through,String t){return post("/api/purchase-intents/"+id+"/messages/read").header("Authorization","Bearer "+t).contentType("application/json").content("{\"lastMessageId\":"+through+"}");}
 long unread(long id,String t)throws Exception{return check(state(id,t),200).get("unreadCount").asLong();}
 long cursor(long id,String t)throws Exception{return check(state(id,t),200).get("lastReadMessageId").asLong();}
 @Test void emptyStateDoesNotCreateReadRows()throws Exception {long id=opened();var r=check(state(id,buyerToken),200);assertEquals(0,r.get("latestMessageId").asLong());assertEquals(0,r.get("lastReadMessageId").asLong());assertEquals(0,unread(id,buyerToken));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_message_reads",Long.class));}
 @Test void unreadCountsOnlyOtherSendersActiveMessages()throws Exception {long id=opened();message(id,buyerToken,1);message(id,buyerToken,2);message(id,sellerToken,3);assertEquals(1,unread(id,buyerToken));assertEquals(2,unread(id,sellerToken));}
 @Test void participantsHaveIndependentReadCursors()throws Exception {long id=opened(),a=message(id,buyerToken,1),b=message(id,sellerToken,2);check(mark(id,b,buyerToken),200);assertEquals(b,cursor(id,buyerToken));assertEquals(0,cursor(id,sellerToken));assertEquals(1,unread(id,sellerToken));check(mark(id,a,sellerToken),200);assertEquals(0,unread(id,sellerToken));}
 @Test void zeroIsAnIdempotentNoOp()throws Exception {long id=opened();check(mark(id,0,buyerToken),200);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_message_reads",Long.class));long m=message(id,sellerToken,1);check(mark(id,m,buyerToken),200);var before=jdbc.queryForList("SELECT * FROM purchase_intent_message_reads");check(mark(id,0,buyerToken),200);assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intent_message_reads"));}
 @Test void markingOlderSnapshotLeavesLaterMessagesUnread()throws Exception {long id=opened(),a=message(id,sellerToken,1),b=message(id,sellerToken,2);var r=check(mark(id,a,buyerToken),200);assertEquals(a,r.get("lastReadMessageId").asLong());assertEquals(b,r.get("latestMessageId").asLong());assertEquals(1,r.get("unreadCount").asLong());}
 @Test void newArrivalAfterQueryRemainsUnread()throws Exception {long id=opened();message(id,sellerToken,1);long snapshot=check(state(id,buyerToken),200).get("latestMessageId").asLong();message(id,sellerToken,2);check(mark(id,snapshot,buyerToken),200);assertEquals(1,unread(id,buyerToken));}
 @Test void repeatedMarkDoesNotChangeTimestamp()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(mark(id,m,buyerToken),200);var before=jdbc.queryForList("SELECT * FROM purchase_intent_message_reads");check(mark(id,m,buyerToken),200);assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intent_message_reads"));}
 @Test void olderDeviceCannotMoveProgressBackwards()throws Exception {long id=opened(),a=message(id,sellerToken,1),b=message(id,sellerToken,2);check(mark(id,b,buyerToken),200);var before=jdbc.queryForList("SELECT * FROM purchase_intent_message_reads");check(mark(id,a,buyerToken),200);assertEquals(b,cursor(id,buyerToken));assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intent_message_reads"));}
 @Test void concurrentMarksRetainMaximumCursor()throws Exception {long id=opened(),a=message(id,sellerToken,1),b=message(id,sellerToken,2);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var x=pool.submit(()->{start.await();return check(mark(id,a,buyerToken),200);});var y=pool.submit(()->{start.await();return check(mark(id,b,buyerToken),200);});start.countDown();x.get(10,TimeUnit.SECONDS);y.get(10,TimeUnit.SECONDS);assertEquals(b,cursor(id,buyerToken));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_message_reads",Long.class));}finally{pool.shutdownNow();}}
 @Test void strangerAndMissingIntentAreIndistinguishable()throws Exception {long id=opened(),m=message(id,sellerToken,1);assertEquals(check(state(id,otherToken),404),check(state(9007199254740991L,otherToken),404));assertEquals(check(mark(id,m,otherToken),404),check(mark(9007199254740991L,m,otherToken),404));}
 @Test void cursorMustBelongToPathIntent()throws Exception {long id=opened(),m=message(id,sellerToken,1);long p=seed("另一意向",1000,seller,"ON_SALE",0),otherId=check(create(p,buyerToken,0),201).at("/intent/id").asLong();assertEquals("INTENT_MESSAGE_NOT_FOUND",check(mark(otherId,m,buyerToken),404).get("code").asText());check(mark(id,9007199254740991L,buyerToken),404);assertEquals(0,cursor(id,buyerToken));}
 @Test void invalidCursorsAndIntentIdsAreRejected()throws Exception {long id=opened();for(long n:List.of(-1L,9007199254740992L))check(mark(id,n,buyerToken),400);for(String body:List.of("{}","{\"lastMessageId\":null}","{\"lastMessageId\":1.5}","{\"lastMessageId\":\"1\"}","{\"lastMessageId\":true}"))check(post("/api/purchase-intents/"+id+"/messages/read").header("Authorization","Bearer "+buyerToken).contentType("application/json").content(body),400);for(long n:List.of(0L,-1L,9007199254740992L)){check(state(n,buyerToken),400);check(mark(n,0,buyerToken),400);}}
 @Test void anonymousAndInvalidTokensCannotReadOrWrite()throws Exception {long id=opened();check(get("/api/purchase-intents/"+id+"/messages/read-state"),401);check(post("/api/purchase-intents/"+id+"/messages/read").contentType("application/json").content("{\"lastMessageId\":0}"),401);check(state(id,"bad"),401);check(mark(id,0,"bad"),401);}
 @Test void pendingAndCancelledIntentAllowOwnEmptyProgress()throws Exception {long id=check(create(product,buyerToken,7),201).at("/intent/id").asLong();check(state(id,buyerToken),200);check(mark(id,0,sellerToken),200);check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);check(state(id,sellerToken),200);check(mark(id,0,buyerToken),200);}
 @Test void withdrawnMessageIsNotUnreadOrRestored()throws Exception {long id=opened(),m=message(id,sellerToken,1);assertEquals(1,unread(id,buyerToken));check(withdraw(id,m,sellerToken),200);assertEquals(0,unread(id,buyerToken));check(mark(id,m,buyerToken),200);check(send(id,sellerToken,"秘密正文1",key(1)),200);assertEquals(0,unread(id,buyerToken));assertTrue(check(messages(id,buyerToken,1,10),200).at("/items/0/content").isNull());}
 @Test void ownLatestMessageCanBeSnapshotBoundary()throws Exception {long id=opened();message(id,sellerToken,1);long own=message(id,buyerToken,2);assertEquals(1,unread(id,buyerToken));check(mark(id,own,buyerToken),200);assertEquals(own,cursor(id,buyerToken));assertEquals(0,unread(id,buyerToken));}
 @Test void withdrawingCursorTargetDoesNotRemoveProgress()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(mark(id,m,buyerToken),200);check(withdraw(id,m,sellerToken),200);assertEquals(m,cursor(id,buyerToken));assertEquals(0,unread(id,buyerToken));}
 @Test void readProgressNeverChangesMessagesProductsOrIntents()throws Exception {long id=opened(),m=message(id,sellerToken,1);var before=new ArrayList<List<Map<String,Object>>>();for(String t:List.of("products","purchase_intents","purchase_intent_messages"))before.add(jdbc.queryForList("SELECT * FROM "+t+" ORDER BY id"));check(mark(id,m,buyerToken),200);int i=0;for(String t:List.of("products","purchase_intents","purchase_intent_messages"))assertEquals(before.get(i++),jdbc.queryForList("SELECT * FROM "+t+" ORDER BY id"));}
 @Test void stateExposesOnlyScalarReadFieldsAndNoAutomaticAck()throws Exception {long id=opened();message(id,sellerToken,1);var r=check(state(id,buyerToken),200);assertEquals(4,r.size());assertFalse(r.toString().contains("秘密正文"));check(messages(id,buyerToken,1,10),200);assertEquals(0,cursor(id,buyerToken));assertEquals(1,unread(id,buyerToken));}
 @Test void allReadResponsesAreNoStore()throws Exception {long id=opened(),m=message(id,sellerToken,1);for(var r:List.of(state(id,buyerToken),mark(id,m,buyerToken),state(id,otherToken),mark(id,m,otherToken),mark(id,9007199254740991L,buyerToken),mark(id,-1,buyerToken),state(id,"bad")))assertEquals("no-store",mvc.perform(r).andReturn().getResponse().getHeader("Cache-Control"));}
}
