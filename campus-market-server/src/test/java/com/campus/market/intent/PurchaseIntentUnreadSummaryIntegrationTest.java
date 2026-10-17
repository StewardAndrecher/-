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

@SpringBootTest(properties={"spring.datasource.url=${LESSON64_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON64_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson64_verify\\?.*")
class PurchaseIntentUnreadSummaryIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON64_DB_URL").contains("/campus_lesson64_verify?"));
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
 @Test void noIntentsReturnsExplicitZeros()throws Exception {counts(buyerToken,"BUYER",0,0);counts(sellerToken,"SELLER",0,0);}
 @Test void emptyConversationsAreNotUnreadIntents()throws Exception {opened();another(sellerToken,seller,0);counts(buyerToken,"BUYER",0,0);}
 @Test void multipleMessagesInOneIntentCountOneIntent()throws Exception {long id=opened();for(int i=1;i<=3;i++)message(id,sellerToken,i);counts(buyerToken,"BUYER",3,1);}
 @Test void aggregationIncludesMultipleIntentsAndEmptyOnes()throws Exception {long id=opened(),next=another(sellerToken,seller,0);another(sellerToken,seller,0);message(id,sellerToken,1);message(id,sellerToken,2);message(next,sellerToken,3);counts(buyerToken,"BUYER",3,2);}
 @Test void ownMessagesDoNotContribute()throws Exception {long id=opened();message(id,buyerToken,1);message(id,buyerToken,2);counts(buyerToken,"BUYER",0,0);counts(sellerToken,"SELLER",2,1);}
 @Test void withdrawnMessagesAreExcludedFromBothTotals()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(withdraw(id,m,sellerToken),200);counts(buyerToken,"BUYER",0,0);message(id,sellerToken,2);counts(buyerToken,"BUYER",1,1);}
 @Test void partialCursorCountsOnlyLaterMessages()throws Exception {long id=opened(),m=message(id,sellerToken,1);message(id,sellerToken,2);check(mark(id,m,buyerToken),200);counts(buyerToken,"BUYER",1,1);}
 @Test void markingOneIntentReadDoesNotClearOthers()throws Exception {long id=opened(),next=another(sellerToken,seller,0),m=message(id,sellerToken,1);message(next,sellerToken,2);counts(buyerToken,"BUYER",2,2);check(mark(id,m,buyerToken),200);counts(buyerToken,"BUYER",1,1);}
 @Test void rolesAndPersonalCursorsRemainIndependent()throws Exception {long id=opened(),m=message(id,sellerToken,1);message(id,buyerToken,2);check(mark(id,m,buyerToken),200);counts(buyerToken,"BUYER",0,0);counts(sellerToken,"SELLER",1,1);long reverse=another(buyerToken,buyer,0);message(reverse,sellerToken,3);counts(buyerToken,"SELLER",1,1);counts(buyerToken,"BUYER",0,0);}
 @Test void otherAccountsSeeOnlyTheirOwnTotals()throws Exception {long id=opened();message(id,sellerToken,1);counts(otherToken,"BUYER",0,0);counts(otherToken,"SELLER",0,0);check(create(product,otherToken,7),201);counts(otherToken,"BUYER",0,0);}
 @Test void pagingStatusAndUnreadFiltersDoNotNarrowOverview()throws Exception {long id=opened();message(id,sellerToken,1);message(id,sellerToken,2);long pending=seed("待沟通",1000,seller,"ON_SALE",0);check(create(pending,buyerToken,0),201);var expected=summary(buyerToken,"BUYER");for(String status:List.of("","PENDING","ACCEPTED","CANCELLED")){check(get("/api/purchase-intents/mine").header("Authorization","Bearer "+buyerToken).param("status",status).param("page","2").param("size","1").param("unreadOnly","true"),200);assertEquals(expected,summary(buyerToken,"BUYER"));}}
 @Test void emptyFilteredPageCanCoexistWithNonzeroOverview()throws Exception {long id=opened();message(id,sellerToken,1);var page=check(get("/api/purchase-intents/mine").header("Authorization","Bearer "+buyerToken).param("status","PENDING").param("unreadOnly","true"),200);assertEquals(0,page.get("total").asLong());counts(buyerToken,"BUYER",1,1);}
 @Test void queryingOverviewDoesNotChangeAnyBusinessOrReadRows()throws Exception {long id=opened();message(id,sellerToken,1);var before=new ArrayList<List<Map<String,Object>>>();for(String t:List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads"))before.add(jdbc.queryForList("SELECT * FROM "+t));summary(buyerToken,"BUYER");summary(sellerToken,"SELLER");int i=0;for(String t:List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads"))assertEquals(before.get(i++),jdbc.queryForList("SELECT * FROM "+t));}
 @Test void invalidRoleIsRejected()throws Exception {for(String role:List.of("buyer","ALL","SELLER OR 1=1"))check(overview(buyerToken,role),400);}
 @Test void anonymousAndInvalidTokenAreRejected()throws Exception {check(get("/api/purchase-intents/message-unread-summary"),401);check(overview("invalid","BUYER"),401);}
 @Test void defaultRoleAndIgnoredUserIdCannotImpersonateAnotherUser()throws Exception {long id=opened();message(id,sellerToken,1);assertEquals(summary(buyerToken,"BUYER"),check(get("/api/purchase-intents/message-unread-summary").header("Authorization","Bearer "+buyerToken),200));var r=check(overview(otherToken,"BUYER").param("userId",""+buyer),200);assertEquals(0,r.get("totalUnreadMessages").asLong());}
 @Test void responseExposesOnlyRoleAndCounts()throws Exception {long id=opened();message(id,sellerToken,1);var r=summary(buyerToken,"BUYER");assertEquals(3,r.size());assertFalse(r.toString().contains("秘密正文"));assertFalse(r.has("lastReadMessageId"));assertFalse(r.has("items"));}
 @SuppressWarnings({"unchecked","rawtypes"})
 @Test void repositoryUsesOneAggregateQuery()throws Exception {long id=opened();message(id,sellerToken,1);var spy=org.mockito.Mockito.spy(jdbc);var repository=new PurchaseIntentUnreadRepository(spy);var r=repository.summary(buyer,PurchaseIntentRole.BUYER);assertEquals(1,r.totalUnreadMessages());org.mockito.Mockito.verify(spy,org.mockito.Mockito.times(1)).queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));}
 @Test void allOverviewResponsesAreNoStore()throws Exception {for(var q:List.of(overview(buyerToken,"BUYER"),overview(buyerToken,"bad"),overview("invalid","BUYER")))assertEquals("no-store",mvc.perform(q).andReturn().getResponse().getHeader("Cache-Control"));}
 @Test void newMessageAfterReadReappearsAndSoldProductKeepsHistory()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(mark(id,m,buyerToken),200);counts(buyerToken,"BUYER",0,0);message(id,sellerToken,2);counts(buyerToken,"BUYER",1,1);message(id,buyerToken,3);counts(buyerToken,"BUYER",1,1);jdbc.update("UPDATE products SET status='SOLD',version=version+1 WHERE id=?",product);counts(buyerToken,"BUYER",1,1);}
}
