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

@SpringBootTest(properties={"spring.datasource.url=${LESSON66_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON66_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson66_verify\\?.*")
class PurchaseIntentReceivedTimeIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON66_DB_URL").contains("/campus_lesson66_verify?"));
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

 Map<Long,String> times(JsonNode page) {
  var rows=page.get("receivedMessageTimes");assertNotNull(rows);assertTrue(rows.isArray());assertEquals(page.get("items").size(),rows.size());
  var result=new LinkedHashMap<Long,String>();for(int k=0;k<rows.size();k++){
   var row=rows.get(k);assertEquals(2,row.size());assertEquals(page.at("/items/"+k+"/id").asLong(),row.get("intentId").asLong());
   assertFalse(result.containsKey(row.get("intentId").asLong()));result.put(row.get("intentId").asLong(),row.get("latestReceivedAt").isNull()?null:row.get("latestReceivedAt").asText());
  }return result;
 }
 JsonNode mine(String token,String role)throws Exception{return check(page(token,role,1,5),200);}
 @Test void emptyPageReturnsExplicitEmptyArray()throws Exception {var r=mine(buyerToken,"BUYER");assertEquals(0,r.get("total").asLong());assertTrue(times(r).isEmpty());}
 @Test void intentWithoutMessagesHasExplicitNull()throws Exception {long id=opened();var r=mine(buyerToken,"BUYER");assertTrue(times(r).containsKey(id));assertNull(times(r).get(id));}
 @Test void ownMessagesAloneDoNotSupplyReceivedTime()throws Exception {long id=opened();timed(id,buyerToken,1,"2026-10-10 10:00:01");assertNull(times(mine(buyerToken,"BUYER")).get(id));assertEquals("2026-10-10T10:00:01",times(mine(sellerToken,"SELLER")).get(id));}
 @Test void maxReceivedTimeIgnoresOwnLaterMessagesAndMessageIdOrder()throws Exception {long id=opened();timed(id,sellerToken,1,"2026-10-10 12:00:01");timed(id,buyerToken,2,"2026-10-11 12:00:01");timed(id,sellerToken,3,"2026-10-09 12:00:01");assertEquals("2026-10-10T12:00:01",times(mine(buyerToken,"BUYER")).get(id));}
 @Test void bothParticipantsGetTheirOwnReceivedPerspective()throws Exception {long id=opened();timed(id,sellerToken,1,"2026-10-10 12:00:01");timed(id,buyerToken,2,"2026-10-11 12:00:01");assertEquals("2026-10-10T12:00:01",times(mine(buyerToken,"BUYER")).get(id));assertEquals("2026-10-11T12:00:01",times(mine(sellerToken,"SELLER")).get(id));}
 @Test void withdrawalOfLatestFallsBackToPreviousTime()throws Exception {long id=opened();timed(id,sellerToken,1,"2026-10-10 12:00:01");long m=timed(id,sellerToken,2,"2026-10-11 12:00:01");assertEquals("2026-10-11T12:00:01",times(mine(buyerToken,"BUYER")).get(id));check(withdraw(id,m,sellerToken),200);assertEquals("2026-10-10T12:00:01",times(mine(buyerToken,"BUYER")).get(id));}
 @Test void allReceivedMessagesWithdrawnReturnsNullEvenWithOwnMessage()throws Exception {long id=opened(),m=timed(id,sellerToken,1,"2026-10-10 12:00:01");message(id,buyerToken,2);check(withdraw(id,m,sellerToken),200);assertNull(times(mine(buyerToken,"BUYER")).get(id));}
 @Test void markingReadChangesUnreadCountButRetainsReceivedTime()throws Exception {long id=opened(),m=timed(id,sellerToken,1,"2026-10-10 12:00:01");var before=times(mine(buyerToken,"BUYER"));check(mark(id,m,buyerToken),200);assertEquals(0,unread(id,buyerToken));assertEquals(before,times(mine(buyerToken,"BUYER")));}
 @Test void metadataIncludesOnlyCurrentPageInExactlyItsOrder()throws Exception {var ids=accepted(8);for(int k=0;k<8;k++)timed(ids.get(k),sellerToken,k+1,"2026-10-10 12:00:01");var a=check(page(buyerToken,"BUYER",1,5),200);var b=check(page(buyerToken,"BUYER",2,5),200);assertEquals(5,times(a).size());assertEquals(3,times(b).size());var overlap=new HashSet<>(times(a).keySet());overlap.retainAll(times(b).keySet());assertTrue(overlap.isEmpty());}
 @Test void statusAndUnreadFiltersReturnOnlyMatchingMetadata()throws Exception {var ids=accepted(3);timed(ids.get(0),sellerToken,1,"2026-10-10 12:00:01");var r=check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,5).param("status","ACCEPTED").param("unreadOnly","true"),200);assertEquals(Set.of(ids.get(0)),times(r).keySet());var empty=check(sorted(buyerToken,"BUYER","RECENT_MESSAGE",1,5).param("status","PENDING").param("unreadOnly","true"),200);assertTrue(times(empty).isEmpty());assertEquals(3,empty.at("/summary/total").asLong());}
 @Test void newestAndRecentUseSameTimesAndRecentOrderMatchesThem()throws Exception {var ids=accepted(3);timed(ids.get(0),sellerToken,1,"2026-10-11 12:00:01");timed(ids.get(1),sellerToken,2,"2026-10-10 12:00:01");var newest=mine(buyerToken,"BUYER");var recent=recent(buyerToken,"BUYER",1,5);assertEquals(times(newest),times(recent));assertEquals(List.of(ids.get(0),ids.get(1),ids.get(2)),rowIds(recent));}
 @Test void otherAccountOrRoleCannotReceiveSomeoneElsesMetadata()throws Exception {long id=opened();message(id,sellerToken,1);assertTrue(times(mine(otherToken,"BUYER")).isEmpty());assertTrue(times(mine(buyerToken,"SELLER")).isEmpty());check(create(product,otherToken,7),201);var otherPage=check(page(otherToken,"BUYER",1,5).param("userId",""+buyer),200);assertEquals(1,times(otherPage).size());assertFalse(times(otherPage).containsKey(id));assertNull(times(otherPage).values().iterator().next());}
 @Test void timestampPreservesMicrosecondsAndAllowsWholeMinutes()throws Exception {long id=opened();long m=timed(id,sellerToken,1,"2026-10-10 12:00:01.123456");assertEquals("2026-10-10T12:00:01.123456",times(mine(buyerToken,"BUYER")).get(id));jdbc.update("UPDATE purchase_intent_messages SET created_at='2026-10-10 12:00:00' WHERE id=?",m);assertEquals("2026-10-10T12:00",times(mine(buyerToken,"BUYER")).get(id));}
 @Test void pendingAndCancelledHistoryStillGetNullMetadataWithoutMessages()throws Exception {long id=check(create(product,buyerToken,7),201).at("/intent/id").asLong();assertNull(times(mine(buyerToken,"BUYER")).get(id));check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);assertNull(times(mine(buyerToken,"BUYER")).get(id));}
 @Test void queriesLeaveProductsIntentsMessagesAndCursorsUnchanged()throws Exception {long id=opened();message(id,sellerToken,1);var tables=List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads");var before=new ArrayList<List<Map<String,Object>>>();for(String table:tables)before.add(jdbc.queryForList("SELECT * FROM "+table));mine(buyerToken,"BUYER");recent(sellerToken,"SELLER",1,5);for(int k=0;k<tables.size();k++)assertEquals(before.get(k),jdbc.queryForList("SELECT * FROM "+tables.get(k)));}
 @Test void authInvalidArgumentsAndNoStoreStillApply()throws Exception {for(var q:List.of(get("/api/purchase-intents/mine"),page("invalid","BUYER",1,5),page(buyerToken,"bad",1,5),sorted(buyerToken,"BUYER","bad",1,5),page(buyerToken,"BUYER",1,5))){var r=mvc.perform(q).andReturn().getResponse();assertEquals("no-store",r.getHeader("Cache-Control"));assertTrue(Set.of(200,400,401).contains(r.getStatus()));}check(page("invalid","BUYER",1,5),401);check(page(buyerToken,"bad",1,5),400);}
 @Test void metadataExposesOnlyIdAndTimeWithoutBodiesOrReadingProgress()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(mark(id,m,sellerToken),200);var r=mine(buyerToken,"BUYER");assertEquals(11,r.size());times(r);assertFalse(r.toString().contains("秘密正文"));assertFalse(r.toString().contains("lastReadMessageId"));assertFalse(r.get("receivedMessageTimes").toString().contains("senderId"));}
 @Test void equalTimesAndReadHistoryStayConsistentWithStableSort()throws Exception {var ids=accepted(3);for(int k=0;k<3;k++){long m=timed(ids.get(k),sellerToken,k+1,"2026-10-10 12:00:01");check(mark(ids.get(k),m,buyerToken),200);}var r=recent(buyerToken,"BUYER",1,5);assertEquals(List.of(ids.get(2),ids.get(1),ids.get(0)),rowIds(r));assertTrue(times(r).values().stream().allMatch("2026-10-10T12:00:01"::equals));}
 @SuppressWarnings({"unchecked","rawtypes"})
 @Test void currentPageMetadataUsesOneBoundAggregateQueryAndEmptyPageUsesNone()throws Exception {var ids=accepted(3);message(ids.get(0),sellerToken,1);var rows=new PurchaseIntentRepository(jdbc).page(buyer,PurchaseIntentRole.BUYER,PurchaseIntentStatusFilter.ALL,5,0,false,PurchaseIntentSort.NEWEST);var spy=org.mockito.Mockito.spy(jdbc);var repository=new PurchaseIntentRepository(spy);assertEquals(3,repository.receivedTimes(buyer,PurchaseIntentRole.BUYER,rows).size());org.mockito.Mockito.verify(spy,org.mockito.Mockito.times(1)).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));org.mockito.Mockito.clearInvocations(spy);assertEquals(List.of(),repository.receivedTimes(buyer,PurchaseIntentRole.BUYER,List.of()));org.mockito.Mockito.verifyNoInteractions(spy);}
 @Test void newIncomingMessageAfterReadUpdatesTimeAndSoldProductsKeepHistory()throws Exception {long id=opened(),m=timed(id,sellerToken,1,"2026-10-10 12:00:01");check(mark(id,m,buyerToken),200);timed(id,sellerToken,2,"2026-10-11 12:00:01");jdbc.update("UPDATE products SET status='SOLD',version=version+1 WHERE id=?",product);assertEquals("2026-10-11T12:00:01",times(mine(buyerToken,"BUYER")).get(id));assertEquals(1,unread(id,buyerToken));}
}
