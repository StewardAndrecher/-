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

@SpringBootTest(properties={"spring.datasource.url=${LESSON59_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON59_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson59_verify\\?.*")
class PurchaseIntentMessageIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON59_DB_URL").contains("/campus_lesson59_verify?"));
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
 @Test void anonymousReadAndSendRequireLogin()throws Exception {long id=opened();check(get("/api/purchase-intents/"+id+"/messages"),401);check(post("/api/purchase-intents/"+id+"/messages").contentType("application/json").content("{}"),401);}
 @Test void strangerAndMissingIntentReturnIdentical404()throws Exception {long id=opened();assertEquals(check(messages(id,otherToken,1,10),404),check(messages(9007199254740991L,otherToken,1,10),404));check(send(id,otherToken,"越权",key(1)),404);}
 @Test void bothParticipantsSendAndReadSameConversation()throws Exception {long id=opened();var a=check(send(id,buyerToken,"明天下午交接可以吗？",key(1)),201);var b=check(send(id,sellerToken,"可以，在图书馆门口。",key(2)),201);assertFalse(a.get("replayed").asBoolean());assertEquals(buyer,a.at("/message/sender/id").asLong());assertEquals(seller,b.at("/message/sender/id").asLong());assertEquals(check(messages(id,buyerToken,1,10),200),check(messages(id,sellerToken,1,10),200));assertEquals(2,check(messages(id,buyerToken,1,10),200).get("total").asInt());}
 @Test void pendingAndCancelledCanReadButCannotSend()throws Exception {long id=check(create(product,buyerToken,7),201).at("/intent/id").asLong();var p=check(messages(id,buyerToken,1,10),200);assertEquals("PENDING",p.get("intentStatus").asText());assertEquals(0,p.get("total").asInt());check(send(id,buyerToken,"暂不能发",key(1)),409);check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);assertEquals("CANCELLED",check(messages(id,sellerToken,1,10),200).get("intentStatus").asText());check(send(id,sellerToken,"不能发",key(2)),409);}
 @Test void repeatedRequestReturnsSameMessageAndTimestamp()throws Exception {long id=opened();var first=check(send(id,buyerToken,"你好",key(1)),201);var replay=check(send(id,buyerToken,"你好",key(1)),200);assertTrue(replay.get("replayed").asBoolean());assertEquals(first.get("message"),replay.get("message"));assertEquals(1,check(messages(id,buyerToken,1,10),200).get("total").asInt());}
 @Test void reusedKeyWithDifferentContentConflicts()throws Exception {long id=opened();check(send(id,buyerToken,"原内容",key(1)),201);assertEquals("INTENT_MESSAGE_CONFLICT",check(send(id,buyerToken,"替换内容",key(1)),409).get("code").asText());assertEquals("原内容",check(messages(id,buyerToken,1,10),200).at("/items/0/content").asText());}
 @Test void concurrentDuplicateRequestsInsertExactlyOnce()throws Exception {long id=opened();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();return mvc.perform(send(id,buyerToken,"并发",key(1))).andReturn().getResponse().getStatus();});var b=pool.submit(()->{start.await();return mvc.perform(send(id,buyerToken,"并发",key(1))).andReturn().getResponse().getStatus();});start.countDown();var codes=new ArrayList<>(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,201),codes);assertEquals(1,check(messages(id,sellerToken,1,10),200).get("total").asInt());}finally{pool.shutdownNow();}}
 @Test void requestKeyIsScopedBySenderAndIntent()throws Exception {long id=opened();check(send(id,buyerToken,"买家",key(1)),201);check(send(id,sellerToken,"卖家",key(1)),201);long p=seed("另一本教材",1000,seller,"ON_SALE",0);long id2=check(create(p,buyerToken,0),201).at("/intent/id").asLong();check(post("/api/purchase-intents/"+id2+"/accept").header("Authorization","Bearer "+sellerToken),200);check(send(id2,buyerToken,"另一意向",key(1)),201);assertEquals(2,check(messages(id,buyerToken,1,10),200).get("total").asInt());assertEquals(1,check(messages(id2,buyerToken,1,10),200).get("total").asInt());}
 @Test void invalidContentAndUuidAreRejectedWithoutInsertion()throws Exception {long id=opened();for(String text:List.of("","  ","\n\t","x".repeat(501),"a\u0000b"))check(send(id,buyerToken,text,key(1)),400);for(String k:List.of("bad","","00000000-0000-4000-8000-00000000000G"))check(send(id,buyerToken,"正常",k),400);assertEquals(0,check(messages(id,buyerToken,1,10),200).get("total").asInt());}
 @Test void unicodeLengthAndPlainTextArePreserved()throws Exception {long id=opened();String text="😀".repeat(500);assertEquals(text,check(send(id,buyerToken,text,key(1)),201).at("/message/content").asText());check(send(id,buyerToken,"😀".repeat(501),key(2)),400);String plain="<script>alert(1)</script>\n第二行";assertEquals(plain,check(send(id,sellerToken,plain,key(3)),201).at("/message/content").asText());}
 @Test void stableAscendingPaginationAndLastPageClamp()throws Exception {long id=opened();var ids=new ArrayList<Long>();for(int i=1;i<=6;i++)ids.add(check(send(id,buyerToken,"第"+i+"条",key(i)),201).at("/message/id").asLong());jdbc.update("UPDATE purchase_intent_messages SET created_at='2026-01-01 00:00:00'");var seen=new ArrayList<Long>();for(int page=1;page<=3;page++){var p=check(messages(id,buyerToken,page,2),200);assertEquals(6,p.get("total").asInt());for(var row:p.get("items"))seen.add(row.get("id").asLong());}assertEquals(ids,seen);assertEquals(3,check(messages(id,buyerToken,999,2),200).get("page").asInt());}
 @Test void emptyConversationIsPageOneOfOne()throws Exception {long id=opened();var p=check(messages(id,buyerToken,999,10),200);assertEquals(0,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals(1,p.get("totalPages").asInt());assertEquals("ACCEPTED",p.get("intentStatus").asText());}
 @Test void invalidIdsAndPaginationAreRejected()throws Exception {long id=opened();for(long n:List.of(0L,-1L,9007199254740992L)){check(messages(n,buyerToken,1,10),400);check(send(n,buyerToken,"你好",key(1)),400);}for(int n:List.of(0,1000001))check(messages(id,buyerToken,n,10),400);for(int n:List.of(0,21))check(messages(id,buyerToken,1,n),400);}
 @Test void spoofedIdentityCannotReadOrSend()throws Exception {long id=opened();check(messages(id,otherToken,1,10).param("userId",""+buyer).param("role","BUYER"),404);check(send(id,otherToken,"冒充",key(1)).param("senderId",""+buyer),404);}
 @Test void conversationDoesNotChangeProductsOrIntentSnapshots()throws Exception {long id=opened();var products=jdbc.queryForList("SELECT * FROM products ORDER BY id");var intents=jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id");check(send(id,buyerToken,"交接时间？",key(1)),201);check(send(id,sellerToken,"明天。",key(2)),201);check(messages(id,buyerToken,1,10),200);assertEquals(products,jdbc.queryForList("SELECT * FROM products ORDER BY id"));assertEquals(intents,jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id"));}
 @Test void historicalProductChangesDoNotCloseAcceptedConversation()throws Exception {long id=opened();check(send(id,buyerToken,"原留言",key(1)),201);jdbc.update("UPDATE products SET status='SOLD',title='已改名',price_in_cents=9999,version=version+1 WHERE id=?",product);check(send(id,sellerToken,"售出后确认交接",key(2)),201);assertTrue(check(send(id,buyerToken,"原留言",key(1)),200).get("replayed").asBoolean());assertEquals(2,check(messages(id,buyerToken,1,10),200).get("total").asInt());}
 @Test void responsesAreNoStoreAndDoNotExposeRequestKeysOrPrivateAccounts()throws Exception {long id=opened();var r=mvc.perform(send(id,buyerToken,"私密内容",key(1))).andReturn().getResponse();assertEquals(201,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));var msg=mapper.readTree(r.getContentAsString(StandardCharsets.UTF_8)).get("message");assertEquals(2,msg.get("sender").size());assertFalse(msg.has("requestId"));assertFalse(msg.has("passwordHash"));assertEquals("no-store",mvc.perform(messages(id,buyerToken,1,10)).andReturn().getResponse().getHeader("Cache-Control"));assertEquals("no-store",mvc.perform(messages(id,otherToken,1,10)).andReturn().getResponse().getHeader("Cache-Control"));}
 @Test void migrationPreservesOriginalTablesAndDatabaseRejectsDuplicateKey()throws Exception {long id=opened();check(send(id,buyerToken,"唯一",key(1)),201);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO purchase_intent_messages(intent_id,sender_id,request_id,content) VALUES(?,?,?,'重复')",id,buyer,key(1)));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_messages",Integer.class));assertEquals(1,count());}
}
