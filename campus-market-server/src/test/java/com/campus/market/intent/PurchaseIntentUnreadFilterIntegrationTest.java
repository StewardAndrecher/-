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

@SpringBootTest(properties={"spring.datasource.url=${LESSON63_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON63_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson63_verify\\?.*")
class PurchaseIntentUnreadFilterIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON63_DB_URL").contains("/campus_lesson63_verify?"));
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

 MockHttpServletRequestBuilder batch(String t,String role,String ids){return get("/api/purchase-intents/message-unread").header("Authorization","Bearer "+t).param("role",role).param("intentIds",ids);}
 JsonNode batch(String t,String role,long...ids)throws Exception {var text=new ArrayList<String>();for(long id:ids)text.add(""+id);return check(batch(t,role,String.join(",",text)),200);}
 long another(String ownerToken,long owner,long version)throws Exception {long p=seed("另一意向教材",2500,owner,"ON_SALE",version);String t=owner==seller?buyerToken:sellerToken;long id=check(create(p,t,version),201).at("/intent/id").asLong();check(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+ownerToken),200);return id;}

 MockHttpServletRequestBuilder listed(String t,String role,String status,int page,int size,String unread){return get("/api/purchase-intents/mine").header("Authorization","Bearer "+t).param("role",role).param("status",status).param("page",""+page).param("size",""+size).param("unreadOnly",unread);}
 JsonNode listed(String t,int page,int size)throws Exception{return check(listed(t,"BUYER","",page,size,"true"),200);}
 List<Long> openedMany(int n)throws Exception {var list=new ArrayList<Long>();for(int i=0;i<n;i++)list.add(another(sellerToken,seller,0));return list;}
 @Test void defaultAndFalseRetainFullHistory()throws Exception {var ids=openedMany(3);var normal=check(get("/api/purchase-intents/mine").header("Authorization","Bearer "+buyerToken),200);assertFalse(normal.get("unreadOnly").asBoolean());assertEquals(3,normal.get("total").asInt());assertEquals(normal,check(listed(buyerToken,"BUYER","",1,5,"false"),200));}
 @Test void emptyUnreadResultClampsToFirstPage()throws Exception {openedMany(3);var r=listed(buyerToken,9,2);assertTrue(r.get("unreadOnly").asBoolean());assertEquals(0,r.get("total").asInt());assertEquals(1,r.get("page").asInt());assertEquals(1,r.get("totalPages").asInt());assertTrue(r.get("items").isEmpty());}
 @Test void serverFindsUnreadAcrossOriginalPagesBeforeLimit()throws Exception {var ids=openedMany(8);message(ids.get(0),sellerToken,1);message(ids.get(6),sellerToken,2);var a=listed(buyerToken,1,1);var b=listed(buyerToken,2,1);assertEquals(2,a.get("total").asInt());assertEquals(ids.get(6).longValue(),a.at("/items/0/id").asLong());assertEquals(ids.get(0).longValue(),b.at("/items/0/id").asLong());assertEquals(8,a.at("/summary/total").asInt());}
 @Test void manyUnreadMessagesDoNotDuplicateIntentOrTotal()throws Exception {long id=opened();for(int i=1;i<=6;i++)message(id,sellerToken,i);var r=listed(buyerToken,2,1);assertEquals(1,r.get("total").asInt());assertEquals(1,r.get("items").size());assertEquals(1,r.get("page").asInt());}
 @Test void ownMessagesDoNotMatch()throws Exception {long id=opened();message(id,buyerToken,1);assertEquals(0,listed(buyerToken,1,5).get("total").asInt());assertEquals(1,check(listed(sellerToken,"SELLER","",1,5,"true"),200).get("total").asInt());}
 @Test void withdrawnOnlyConversationDoesNotMatch()throws Exception {long id=opened(),m=message(id,sellerToken,1);assertEquals(1,listed(buyerToken,1,5).get("total").asInt());check(withdraw(id,m,sellerToken),200);assertEquals(0,listed(buyerToken,1,5).get("total").asInt());}
 @Test void markAllReadRemovesIntentOnNextQuery()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(mark(id,m,buyerToken),200);assertEquals(0,listed(buyerToken,1,5).get("total").asInt());assertEquals(1,check(listed(buyerToken,"BUYER","",1,5,"false"),200).get("total").asInt());}
 @Test void partialCursorRetainsUnreadIntent()throws Exception {long id=opened(),a=message(id,sellerToken,1);message(id,sellerToken,2);check(mark(id,a,buyerToken),200);assertEquals(1,listed(buyerToken,1,5).get("total").asInt());}
 @Test void roleUsesCurrentParticipantsOwnCursor()throws Exception {long id=opened(),a=message(id,sellerToken,1);message(id,buyerToken,2);check(mark(id,a,buyerToken),200);assertEquals(0,listed(buyerToken,1,5).get("total").asInt());assertEquals(1,check(listed(sellerToken,"SELLER","",1,5,"true"),200).get("total").asInt());assertEquals(0,check(listed(buyerToken,"SELLER","",1,5,"true"),200).get("total").asInt());}
 @Test void otherAccountsNeverSeePrivateIntentMatches()throws Exception {long id=opened();message(id,sellerToken,1);assertEquals(0,check(listed(otherToken,"BUYER","",1,5,"true"),200).get("total").asInt());assertEquals(0,check(listed(otherToken,"SELLER","",1,5,"true"),200).get("total").asInt());}
 @Test void statusAndUnreadAreIntersected()throws Exception {long id=opened();message(id,sellerToken,1);long p=seed("待沟通",1000,seller,"ON_SALE",0);check(create(p,buyerToken,0),201);p=seed("已取消",1000,seller,"ON_SALE",0);long cancelled=check(create(p,buyerToken,0),201).at("/intent/id").asLong();check(post("/api/purchase-intents/"+cancelled+"/cancel").header("Authorization","Bearer "+buyerToken),200);assertEquals(1,check(listed(buyerToken,"BUYER","ACCEPTED",1,5,"true"),200).get("total").asInt());for(String status:List.of("PENDING","CANCELLED"))assertEquals(0,check(listed(buyerToken,"BUYER",status,1,5,"true"),200).get("total").asInt());}
 @Test void globalStatusSummaryDoesNotShrinkWithUnreadFilter()throws Exception {var ids=openedMany(4);message(ids.get(1),sellerToken,1);var all=check(listed(buyerToken,"BUYER","",1,2,"false"),200);var unread=listed(buyerToken,1,2);assertEquals(all.get("summary"),unread.get("summary"));assertEquals(4,unread.at("/summary/accepted").asInt());assertEquals(1,unread.get("total").asInt());}
 @Test void equalTimestampsUseStableDescendingIds()throws Exception {var ids=openedMany(3);for(int i=0;i<3;i++)message(ids.get(i),sellerToken,i+1);jdbc.update("UPDATE purchase_intents SET created_at='2026-01-01 00:00:00'");assertEquals(ids.get(2).longValue(),listed(buyerToken,1,1).at("/items/0/id").asLong());assertEquals(ids.get(1).longValue(),listed(buyerToken,2,1).at("/items/0/id").asLong());assertEquals(ids.get(0).longValue(),listed(buyerToken,3,1).at("/items/0/id").asLong());}
 @Test void shrinkingLastPageAfterReadReturnsEffectivePage()throws Exception {var ids=openedMany(2);long old=message(ids.get(0),sellerToken,1);message(ids.get(1),sellerToken,2);assertEquals(2,listed(buyerToken,2,1).get("page").asInt());check(mark(ids.get(0),old,buyerToken),200);var r=listed(buyerToken,2,1);assertEquals(1,r.get("page").asInt());assertEquals(1,r.get("totalPages").asInt());assertEquals(ids.get(1).longValue(),r.at("/items/0/id").asLong());}
 @Test void filterAcceptsOnlyLowercaseTrueOrFalse()throws Exception {for(String value:List.of("TRUE","False","1","0","yes","null","false OR 1=1"))check(listed(buyerToken,"BUYER","",1,5,value),400);}
 @Test void otherQueryValidationRemainsInEffect()throws Exception {check(listed(buyerToken,"WRONG","",1,5,"true"),400);check(listed(buyerToken,"BUYER","WRONG",1,5,"true"),400);check(listed(buyerToken,"BUYER","",0,5,"true"),400);check(listed(buyerToken,"BUYER","",1,21,"true"),400);}
 @Test void anonymousAndInvalidSessionsAreRejected()throws Exception {check(get("/api/purchase-intents/mine").param("unreadOnly","true"),401);check(listed("invalid","BUYER","",1,5,"true"),401);}
 @Test void filteredSuccessAndErrorsAreNoStore()throws Exception {for(var q:List.of(listed(buyerToken,"BUYER","",1,5,"true"),listed(buyerToken,"BUYER","",1,5,"bad"),listed("invalid","BUYER","",1,5,"true")))assertEquals("no-store",mvc.perform(q).andReturn().getResponse().getHeader("Cache-Control"));}
 @Test void filteredReadsDoNotChangeMessagesCursorsOrBusinessState()throws Exception {long id=opened();message(id,sellerToken,1);var before=new ArrayList<List<Map<String,Object>>>();for(String t:List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads"))before.add(jdbc.queryForList("SELECT * FROM "+t));listed(buyerToken,1,5);listed(buyerToken,2,1);int i=0;for(String t:List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads"))assertEquals(before.get(i++),jdbc.queryForList("SELECT * FROM "+t));}
 @Test void soldProductDoesNotHideExistingUnreadConversation()throws Exception {long id=opened();message(id,sellerToken,1);jdbc.update("UPDATE products SET status='SOLD',version=version+1 WHERE id=?",product);assertEquals(id,listed(buyerToken,1,5).at("/items/0/id").asLong());}
}
