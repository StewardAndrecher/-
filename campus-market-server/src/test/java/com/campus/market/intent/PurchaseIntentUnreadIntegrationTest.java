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

@SpringBootTest(properties={"spring.datasource.url=${LESSON62_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON62_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson62_verify\\?.*")
class PurchaseIntentUnreadIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON62_DB_URL").contains("/campus_lesson62_verify?"));
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
 @Test void emptyBatchReturnsZeroAndDoesNotCreateProgress()throws Exception {var r=batch(buyerToken,"BUYER");assertEquals(0,r.get("items").size());assertEquals(0,r.get("pageUnreadMessages").asLong());assertEquals(0,r.get("pageUnreadIntents").asLong());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM purchase_intent_message_reads",Long.class));}
 @Test void absentCursorCountsOtherMessagesAcrossMultipleIntents()throws Exception {long id=opened(),next=another(sellerToken,seller,0);message(id,sellerToken,1);message(id,sellerToken,2);message(next,sellerToken,3);var r=batch(buyerToken,"BUYER",id,next);assertEquals(2,r.at("/items/0/unreadCount").asLong());assertEquals(1,r.at("/items/1/unreadCount").asLong());assertEquals(3,r.get("pageUnreadMessages").asLong());assertEquals(2,r.get("pageUnreadIntents").asLong());}
 @Test void ownAndEmptyMessagesCountZero()throws Exception {long id=opened(),empty=another(sellerToken,seller,0);message(id,buyerToken,1);var r=batch(buyerToken,"BUYER",id,empty);assertEquals(2,r.get("items").size());assertEquals(0,r.get("pageUnreadMessages").asLong());assertEquals(0,r.get("pageUnreadIntents").asLong());}
 @Test void withdrawnMessagesAreExcluded()throws Exception {long id=opened(),m=message(id,sellerToken,1);check(withdraw(id,m,sellerToken),200);message(id,sellerToken,2);var r=batch(buyerToken,"BUYER",id);assertEquals(1,r.at("/items/0/unreadCount").asLong());}
 @Test void cursorFiltersOlderMessagesIndependently()throws Exception {long id=opened(),a=message(id,sellerToken,1);message(id,buyerToken,2);message(id,sellerToken,3);check(mark(id,a,buyerToken),200);assertEquals(1,batch(buyerToken,"BUYER",id).get("pageUnreadMessages").asLong());assertEquals(1,batch(sellerToken,"SELLER",id).get("pageUnreadMessages").asLong());}
 @Test void malformedDuplicateAndUnsafeIdsAreRejected()throws Exception {for(String ids:List.of("0","-1","1.5","01","1,1","1,","1,,2"," 1","9007199254740992","999999999999999999999","1 OR 1=1","1);DROP TABLE users;--"))check(batch(buyerToken,"BUYER",ids),400);}
 @Test void invalidRolesAreRejected()throws Exception {for(String role:List.of("buyer","OWNER","SELLER OR 1=1","ALL"))check(batch(buyerToken,role,""),400);}
 @Test void authenticationIsRequiredEvenForEmptyBatch()throws Exception {check(get("/api/purchase-intents/message-unread"),401);check(batch("invalid","BUYER",""),401);}
 @Test void thirdPartyCannotReadBatch()throws Exception {long id=opened();message(id,sellerToken,1);check(batch(otherToken,"BUYER",""+id),404);check(batch(otherToken,"SELLER",""+id),404);}
 @Test void participantMustMatchSelectedRole()throws Exception {long id=opened();check(batch(buyerToken,"SELLER",""+id),404);check(batch(sellerToken,"BUYER",""+id),404);batch(sellerToken,"SELLER",id);batch(buyerToken,"BUYER",id);}
 @Test void mixedAllowedAndMissingOrForeignIdsFailWholeBatch()throws Exception {long id=opened();long otherId=check(create(product,otherToken,7),201).at("/intent/id").asLong();for(String ids:List.of(id+",9007199254740991",id+","+otherId))assertEquals("PURCHASE_INTENT_NOT_FOUND",check(batch(buyerToken,"BUYER",ids),404).get("code").asText());}
 @Test void onlyRequestedIdsAndRoleAreCounted()throws Exception {long id=opened(),next=another(sellerToken,seller,0),reverse=another(buyerToken,buyer,0);message(id,sellerToken,1);message(next,sellerToken,2);message(reverse,sellerToken,3);var r=batch(buyerToken,"BUYER",id);assertEquals(1,r.get("items").size());assertEquals(1,r.get("pageUnreadMessages").asLong());assertEquals(1,batch(buyerToken,"SELLER",reverse).get("pageUnreadMessages").asLong());}
 @Test void resultOrderMatchesInputRatherThanDatabaseOrder()throws Exception {long id=opened(),next=another(sellerToken,seller,0);message(id,sellerToken,1);var r=batch(buyerToken,"BUYER",next,id);assertEquals(next,r.at("/items/0/intentId").asLong());assertEquals(id,r.at("/items/1/intentId").asLong());assertEquals(0,r.at("/items/0/unreadCount").asLong());}
 @Test void twentyIntentIdsAreSupported()throws Exception {var ids=new ArrayList<String>();for(int i=0;i<20;i++){long p=seed("批次教材"+i,1000,seller,"ON_SALE",0);ids.add(""+check(create(p,buyerToken,0),201).at("/intent/id").asLong());}var r=check(batch(buyerToken,"BUYER",String.join(",",ids)),200);assertEquals(20,r.get("items").size());assertEquals(0,r.get("pageUnreadMessages").asLong());}
 @Test void oversizedBatchIsRejected()throws Exception {var ids=new ArrayList<String>();for(int i=1;i<=21;i++)ids.add(""+i);check(batch(buyerToken,"BUYER",String.join(",",ids)),400);check(batch(buyerToken,"BUYER","1".repeat(340)),400);}
 @Test void queriesNeverMutateAnyBusinessRowsOrReadProgress()throws Exception {long id=opened();message(id,sellerToken,1);var before=new ArrayList<List<Map<String,Object>>>();for(String t:List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads"))before.add(jdbc.queryForList("SELECT * FROM "+t));batch(buyerToken,"BUYER",id);batch(sellerToken,"SELLER",id);int i=0;for(String t:List.of("products","purchase_intents","purchase_intent_messages","purchase_intent_message_reads"))assertEquals(before.get(i++),jdbc.queryForList("SELECT * FROM "+t));}
 @Test void batchExposesOnlyCountsAndScope()throws Exception {long id=opened();message(id,sellerToken,1);var r=batch(buyerToken,"BUYER",id);assertEquals(4,r.size());assertEquals(2,r.at("/items/0").size());assertFalse(r.toString().contains("秘密正文"));assertFalse(r.has("lastReadMessageId"));}
 @Test void batchMatchesDetailedReadStateAfterMarkAndWithdrawal()throws Exception {long id=opened(),m=message(id,sellerToken,1);message(id,sellerToken,2);check(mark(id,m,buyerToken),200);assertEquals(unread(id,buyerToken),batch(buyerToken,"BUYER",id).at("/items/0/unreadCount").asLong());check(withdraw(id,m,sellerToken),200);assertEquals(unread(id,buyerToken),batch(buyerToken,"BUYER",id).get("pageUnreadMessages").asLong());}
 @SuppressWarnings({"unchecked","rawtypes"})
 @Test void repositoryUsesOneBoundQueryForNonemptyBatch()throws Exception {long id=opened(),next=another(sellerToken,seller,0);var spy=org.mockito.Mockito.spy(jdbc);var repository=new PurchaseIntentUnreadRepository(spy);assertEquals(2,repository.counts(buyer,PurchaseIntentRole.BUYER,List.of(next,id)).size());org.mockito.Mockito.verify(spy,org.mockito.Mockito.times(1)).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));org.mockito.Mockito.clearInvocations(spy);assertTrue(repository.counts(buyer,PurchaseIntentRole.BUYER,List.of()).isEmpty());org.mockito.Mockito.verifyNoInteractions(spy);}
 @Test void successAndBusinessErrorsAreNoStore()throws Exception {long id=opened();for(var r:List.of(batch(buyerToken,"BUYER",""+id),batch(otherToken,"BUYER",""+id),batch(buyerToken,"BUYER","0"),batch("invalid","BUYER","")))assertEquals("no-store",mvc.perform(r).andReturn().getResponse().getHeader("Cache-Control"));}
}
