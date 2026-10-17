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

@SpringBootTest(properties={"spring.datasource.url=${LESSON58_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON58_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson58_verify\\?.*")
class PurchaseIntentDetailIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON58_DB_URL").contains("/campus_lesson58_verify?"));
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

 List<Long> ids=new ArrayList<>();
 void seedMixed()throws Exception {
  ids.clear();for(int i=0;i<8;i++){long p=seed("状态教材"+i,1000+i,seller,"ON_SALE",0);long id=check(create(p,buyerToken,0),201).at("/intent/id").asLong();ids.add(id);if(i==3||i==4)check(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken),200);if(i>=6)check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);if(i<2)check(create(p,otherToken,0),201);}
 }
 MockHttpServletRequestBuilder filtered(String t,String role,String status,int n,int size) { return page(t,role,n,size).param("status",status); }
 List<Long> rowIds(JsonNode p) { var list=new ArrayList<Long>();for(var r:p.get("items"))list.add(r.get("id").asLong());return list; }


 MockHttpServletRequestBuilder detail(long id,String token) {return get("/api/purchase-intents/"+id).header("Authorization","Bearer "+token);}
 @Test void buyerReadsSameCompleteIntentAsList()throws Exception {seedMixed();var list=check(page(buyerToken,"BUYER",1,20),200);long id=ids.get(0);var d=check(detail(id,buyerToken),200);assertEquals(id,d.get("id").asLong());assertEquals(buyer,d.at("/buyer/id").asLong());assertEquals(seller,d.at("/seller/id").asLong());assertEquals("PENDING",d.get("status").asText());assertTrue(d.get("cancelledAt").isNull());assertTrue(d.get("acceptedAt").isNull());JsonNode found=null;for(var row:list.get("items"))if(row.get("id").asLong()==id)found=row;assertEquals(found,d);}
 @Test void sellerReadsExactlySameDataAsBuyer()throws Exception {seedMixed();long id=ids.get(0);assertEquals(check(detail(id,buyerToken),200),check(detail(id,sellerToken),200));}
 @Test void anonymousAndExpiredSessionsCannotReadDetail()throws Exception {seedMixed();check(get("/api/purchase-intents/"+ids.get(0)),401);check(get("/api/purchase-intents/0"),401);check(detail(ids.get(0),"invalid-token"),401);check(post("/api/users/logout").header("Authorization","Bearer "+buyerToken),204);check(detail(ids.get(0),buyerToken),401);}
 @Test void unrelatedUserAndMissingIdHaveIdenticalNotFoundResponse()throws Exception {seedMixed();long privateId=ids.get(5);var denied=check(detail(privateId,otherToken),404);var absent=check(detail(9007199254740991L,otherToken),404);assertEquals(absent,denied);assertEquals("PURCHASE_INTENT_NOT_FOUND",denied.get("code").asText());assertFalse(denied.has("productTitleSnapshot"));assertFalse(denied.has("buyer"));}
 @Test void invalidIdsAreRejectedBeforeQuery()throws Exception {for(long id:List.of(0L,-1L,9007199254740992L,Long.MAX_VALUE))assertEquals("INVALID_PURCHASE_INTENT",check(detail(id,buyerToken),400).get("code").asText());check(get("/api/purchase-intents/not-a-number").header("Authorization","Bearer "+buyerToken),400);check(get("/api/purchase-intents/1.5").header("Authorization","Bearer "+buyerToken),400);}
 @Test void spoofedIdentityAndRoleDoNotGrantAccess()throws Exception {seedMixed();long id=ids.get(5);check(detail(id,otherToken).param("buyerId",""+buyer).param("sellerId",""+seller).param("userId",""+buyer).param("role","SELLER"),404);}
 @Test void originalTitlePriceAndVersionSurviveLiveProductChanges()throws Exception {seedMixed();long id=ids.get(0);var before=check(detail(id,buyerToken),200);long p=before.get("productId").asLong();jdbc.update("UPDATE products SET title='新标题',price_in_cents=9999,version=version+1,status='SOLD' WHERE id=?",p);assertEquals(before,check(detail(id,buyerToken),200));assertEquals(before,check(detail(id,sellerToken),200));}
 @Test void cancelledDetailRetainsOriginalCancelTimeAndSnapshot()throws Exception {seedMixed();long id=ids.get(5);var result=check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200).get("intent");assertEquals("CANCELLED",result.get("status").asText());assertFalse(result.get("cancelledAt").isNull());assertEquals(result,check(detail(id,buyerToken),200));assertEquals(result,check(detail(id,sellerToken),200));}
 @Test void acceptedDetailRetainsOriginalAcceptTimeAndSnapshot()throws Exception {seedMixed();long id=ids.get(5);var result=check(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken),200).get("intent");assertEquals("ACCEPTED",result.get("status").asText());assertFalse(result.get("acceptedAt").isNull());assertEquals(result,check(detail(id,buyerToken),200));assertEquals(result,check(detail(id,sellerToken),200));}
 @Test void readAndErrorResponsesAreNoStoreAndNeverWriteBusinessData()throws Exception {seedMixed();var intents=jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id");var products=jdbc.queryForList("SELECT * FROM products ORDER BY id");for(String token:List.of(buyerToken,sellerToken)){var r=mvc.perform(detail(ids.get(5),token)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));}for(var request:List.of(detail(ids.get(5),otherToken),detail(0,buyerToken),get("/api/purchase-intents/"+ids.get(5))))assertEquals("no-store",mvc.perform(request).andReturn().getResponse().getHeader("Cache-Control"));assertEquals(intents,jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id"));assertEquals(products,jdbc.queryForList("SELECT * FROM products ORDER BY id"));}
 @Test void participantsExposeOnlyIdAndCurrentNickname()throws Exception {seedMixed();long id=ids.get(0);jdbc.update("UPDATE users SET nickname='买家新昵称' WHERE id=?",buyer);var p=check(detail(id,sellerToken),200);assertEquals("买家新昵称",p.at("/buyer/nickname").asText());for(String side:List.of("buyer","seller")){assertEquals(2,p.get(side).size());assertTrue(p.get(side).has("id"));assertTrue(p.get(side).has("nickname"));}assertFalse(p.has("accessToken"));assertFalse(p.has("passwordHash"));}
 @Test void intentionIdIsDifferentFromProductIdAndMineRouteStillWorks()throws Exception {jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status,version,category_code) VALUES(900000,'单独教材','验收',3500,?,'ON_SALE',0,'BOOK')",seller);long id=check(create(900000,buyerToken,0),201).at("/intent/id").asLong();assertNotEquals(900000,id);var p=check(detail(id,buyerToken),200);assertEquals(900000,p.get("productId").asLong());check(detail(900000,buyerToken),404);assertEquals(1,check(page(buyerToken,"BUYER",1,5),200).get("total").asInt());}
}
