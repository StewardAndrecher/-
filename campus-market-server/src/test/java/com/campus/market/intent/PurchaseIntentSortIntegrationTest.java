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

@SpringBootTest(properties={"spring.datasource.url=${LESSON65_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON65_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson65_verify\\?.*")
class PurchaseIntentSortIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON65_DB_URL").contains("/campus_lesson65_verify?"));
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

 MockHttpServletRequestBuilder overview(String t,String role){return get("/api/purchase-intents/message-unread-summary").header("Authorization","Bearer "+t).param("role",role);}
 JsonNode summary(String t,String role)throws Exception{return check(overview(t,role),200);}
 void counts(String t,String role,long messages,long intents)throws Exception {var r=summary(t,role);assertEquals(role,r.get("role").asText());assertEquals(messages,r.get("totalUnreadMessages").asLong());assertEquals(intents,r.get("unreadIntentCount").asLong());}

 MockHttpServletRequestBuilder sorted(String token,String role,String sort,int n,int size) {
  return page(token,role,n,size).param("sort",sort);
 }
 JsonNode recent(String token,String role,int n,int size)throws Exception {
  return check(sorted(token,role,"RECENT_MESSAGE",n,size),200);
 }
 List<Long> accepted(int n)throws Exception {
  var list=new ArrayList<Long>();for(int k=0;k<n;k++)list.add(another(sellerToken,seller,0));
  jdbc.update("UPDATE purchase_intents SET created_at='2026-10-01 10:00:00.000000'");return list;
 }
 long timed(long id,String token,int key,String at)throws Exception {
  long m=message(id,token,key);jdbc.update("UPDATE purchase_intent_messages SET created_at=? WHERE id=?",at,m);return m;
 }
 @Test void defaultAndExplicitNewestPreserveOriginalOrderAndEcho()throws Exception {
  var ids=accepted(3);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");
  var a=check(page(buyerToken,"BUYER",1,5),200);var b=check(sorted(buyerToken,"BUYER","NEWEST",1,5),200);
  assertEquals(a,b);assertEquals("NEWEST",a.get("sort").asText());assertEquals(List.of(ids.get(2),ids.get(1),ids.get(0)),rowIds(a));
 }
 @Test void oldIntentWithIncomingMessageMovesAcrossPagesBeforeLimit()throws Exception {
  var ids=accepted(8);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");
  var first=recent(buyerToken,"BUYER",1,5);var last=recent(buyerToken,"BUYER",2,5);
  assertEquals(List.of(ids.get(0),ids.get(7),ids.get(6),ids.get(5),ids.get(4)),rowIds(first));
  assertEquals(List.of(ids.get(3),ids.get(2),ids.get(1)),rowIds(last));assertEquals(8,first.get("total").asLong());
 }
 @Test void ownMessagesDoNotLiftIntentsAndMultipleMessagesDoNotDuplicateRows()throws Exception {
  var ids=accepted(3);for(int k=1;k<=3;k++)timed(ids.get(0),buyerToken,k,"2026-10-10 10:00:00");
  timed(ids.get(1),sellerToken,4,"2026-10-02 10:00:00");
  assertEquals(List.of(ids.get(1),ids.get(2),ids.get(0)),rowIds(recent(buyerToken,"BUYER",1,5)));
 }
 @Test void withdrawingLatestFallsBackToRemainingIncomingTime()throws Exception {
  var ids=accepted(2);timed(ids.get(0),sellerToken,1,"2026-10-02 10:00:00");
  timed(ids.get(1),sellerToken,2,"2026-10-03 10:00:00");long last=timed(ids.get(0),sellerToken,3,"2026-10-04 10:00:00");
  assertEquals(ids,rowIds(recent(buyerToken,"BUYER",1,5)));check(withdraw(ids.get(0),last,sellerToken),200);
  assertEquals(List.of(ids.get(1),ids.get(0)),rowIds(recent(buyerToken,"BUYER",1,5)));
 }
 @Test void allWithdrawnAndEmptyConversationsFallBehindReceivedMessages()throws Exception {
  var ids=accepted(3);long m=timed(ids.get(2),sellerToken,1,"2026-10-10 10:00:00");check(withdraw(ids.get(2),m,sellerToken),200);
  timed(ids.get(0),sellerToken,2,"2026-10-01 01:00:00");assertEquals(List.of(ids.get(0),ids.get(2),ids.get(1)),rowIds(recent(buyerToken,"BUYER",1,5)));
 }
 @Test void noMessagesUseCreatedTimeThenIdIncludingEmptyResults()throws Exception {
  assertEquals(List.of(),rowIds(recent(buyerToken,"BUYER",8,5)));var ids=accepted(3);
  jdbc.update("UPDATE purchase_intents SET created_at='2026-10-02 10:00:00' WHERE id=?",ids.get(0));
  assertEquals(List.of(ids.get(0),ids.get(2),ids.get(1)),rowIds(recent(buyerToken,"BUYER",1,5)));
 }
 @Test void equalMessageTimesHaveStableUniquePagination()throws Exception {
  var ids=accepted(6);for(int k=0;k<6;k++)timed(ids.get(k),sellerToken,k+1,"2026-10-10 10:00:00.123456");
  var all=new ArrayList<Long>();for(int n=1;n<=3;n++){var a=recent(buyerToken,"BUYER",n,2);assertEquals(a,recent(buyerToken,"BUYER",n,2));all.addAll(rowIds(a));}
  var expected=new ArrayList<>(ids);Collections.reverse(expected);assertEquals(expected,all);assertEquals(6,new HashSet<>(all).size());
 }
 @Test void buyerAndSellerUseIncomingMessagesFromTheirOwnPerspective()throws Exception {
  var ids=accepted(3);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");timed(ids.get(1),buyerToken,2,"2026-10-11 10:00:00");
  assertEquals(List.of(ids.get(0),ids.get(2),ids.get(1)),rowIds(recent(buyerToken,"BUYER",1,5)));
  assertEquals(List.of(ids.get(1),ids.get(2),ids.get(0)),rowIds(recent(sellerToken,"SELLER",1,5)));
 }
 @Test void otherAccountsAndRoleCannotExposeAnotherPersonsIntents()throws Exception {
  var ids=accepted(2);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");
  assertEquals(0,recent(otherToken,"BUYER",1,5).get("total").asLong());assertEquals(0,recent(buyerToken,"SELLER",1,5).get("total").asLong());
  var response=check(sorted(otherToken,"BUYER","RECENT_MESSAGE",1,5).param("userId",""+buyer),200);assertEquals(0,response.get("total").asLong());
 }
 @Test void statusFilterCombinesWithSortBeforePaging()throws Exception {
  var ids=accepted(3);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");
  long pending=check(create(product,buyerToken,7),201).at("/intent/id").asLong();
  var r=check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,2).param("status","ACCEPTED"),200);
  assertEquals(List.of(ids.get(0),ids.get(2)),rowIds(r));assertEquals(3,r.get("total").asLong());assertEquals(4,r.at("/summary/total").asLong());
  assertEquals(List.of(pending),rowIds(check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,5).param("status","PENDING"),200)));
 }
 @Test void unreadFilterCombinesWithSortWithoutMultiplyingTotals()throws Exception {
  var ids=accepted(4);long first=timed(ids.get(0),sellerToken,1,"2026-10-02 10:00:00");timed(ids.get(0),sellerToken,2,"2026-10-04 10:00:00");
  timed(ids.get(1),sellerToken,3,"2026-10-03 10:00:00");check(mark(ids.get(0),first,buyerToken),200);
  var r=check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,1).param("unreadOnly","true").param("status","ACCEPTED"),200);
  assertEquals(List.of(ids.get(0)),rowIds(r));assertEquals(2,r.get("total").asLong());assertEquals(2,r.get("totalPages").asInt());assertEquals(4,r.at("/summary/total").asLong());
 }
 @Test void markingReadDoesNotChangeReceivedMessageOrder()throws Exception {
  var ids=accepted(2);long m=timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");var before=recent(buyerToken,"BUYER",1,5);
  check(mark(ids.get(0),m,buyerToken),200);assertEquals(before,recent(buyerToken,"BUYER",1,5));assertEquals(0,unread(ids.get(0),buyerToken));
 }
 @Test void lastPageIsClampedAfterUnreadSetShrinks()throws Exception {
  var ids=accepted(2);long a=timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00"),b=timed(ids.get(1),sellerToken,2,"2026-10-11 10:00:00");
  check(mark(ids.get(0),a,buyerToken),200);var r=check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",9,1).param("unreadOnly","true"),200);
  assertEquals(1,r.get("page").asInt());assertEquals(List.of(ids.get(1)),rowIds(r));check(mark(ids.get(1),b,buyerToken),200);
  r=check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",9,1).param("unreadOnly","true"),200);assertEquals(1,r.get("page").asInt());assertEquals(0,r.get("total").asLong());
 }
 @Test void statusSummaryAndGlobalUnreadAreIndependentOfSort()throws Exception {
  var ids=accepted(3);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");var overview=summary(buyerToken,"BUYER");
  var newest=check(sorted(buyerToken,"BUYER","NEWEST",1,2),200);var recent=recent(buyerToken,"BUYER",1,2);
  assertEquals(newest.get("summary"),recent.get("summary"));assertEquals(overview,summary(buyerToken,"BUYER"));
 }
 @Test void laterIncomingMessageReordersAndUsesTimeRatherThanMessageId()throws Exception {
  var ids=accepted(2);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");timed(ids.get(1),sellerToken,2,"2026-10-09 10:00:00");
  assertEquals(ids,rowIds(recent(buyerToken,"BUYER",1,5)));timed(ids.get(1),sellerToken,3,"2026-10-11 10:00:00");
  assertEquals(List.of(ids.get(1),ids.get(0)),rowIds(recent(buyerToken,"BUYER",1,5)));
 }
 @Test void unknownSortAndSqlFragmentsAreRejectedWithoutChangingRows()throws Exception {
  accepted(1);long before=count();for(String value:List.of("recent_message","POPULAR","i.id DESC","RECENT_MESSAGE; DROP TABLE users"," NEWEST "))check(sorted(buyerToken,"BUYER",value,1,5),400);assertEquals(before,count());
 }
 @Test void sortDoesNotBypassAuthenticationAndExistingValidation()throws Exception {
  check(get("/api/purchase-intents/mine").param("sort","RECENT_MESSAGE"),401);check(sorted("invalid","BUYER","RECENT_MESSAGE",1,5),401);
  check(sorted(buyerToken,"wrong","RECENT_MESSAGE",1,5),400);check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",0,5),400);check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,21),400);
 }
 @Test void successAndErrorsAreNoStore()throws Exception {
  for(var r:List.of(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,5),sorted(buyerToken,"BUYER","bad",1,5),sorted("invalid","BUYER","RECENT_MESSAGE",1,5)))assertEquals("no-store",mvc.perform(r).andReturn().getResponse().getHeader("Cache-Control"));
 }
 @Test void sortedQueriesNeverWriteMessagesReadCursorsOrProductAndIntentRows()throws Exception {
  var ids=accepted(2);timed(ids.get(0),sellerToken,1,"2026-10-10 10:00:00");var tables=List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads");
  var before=new ArrayList<List<Map<String,Object>>>();for(String table:tables)before.add(jdbc.queryForList("SELECT * FROM "+table));
  recent(buyerToken,"BUYER",1,5);recent(sellerToken,"SELLER",1,5);check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,5).param("unreadOnly","true"),200);
  for(int k=0;k<tables.size();k++)assertEquals(before.get(k),jdbc.queryForList("SELECT * FROM "+tables.get(k)));
 }
 @Test void responseDoesNotLeakMessageBodiesOrOtherParticipantsReadCursors()throws Exception {
  long id=opened(),m=message(id,sellerToken,1);check(mark(id,m,sellerToken),200);var r=recent(buyerToken,"BUYER",1,5);
  assertEquals("RECENT_MESSAGE",r.get("sort").asText());assertEquals(11,r.size());assertFalse(r.toString().contains("秘密正文"));assertFalse(r.toString().contains("lastReadMessageId"));assertEquals(11,r.at("/items/0").size());
 }
}
