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

@SpringBootTest(properties={"spring.datasource.url=${LESSON56_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON56_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson56_verify\\?.*")
class PurchaseIntentStatusFilterIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON56_DB_URL").contains("/campus_lesson56_verify?"));
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
 @Test void omittedAndEmptyStatusKeepAllResults()throws Exception { seedMixed();var omitted=check(page(buyerToken,"BUYER",1,20),200);var empty=check(filtered(buyerToken,"BUYER","",1,20),200);assertEquals(omitted,empty);assertEquals("",omitted.get("status").asText());assertEquals(8,omitted.get("total").asInt()); }
 @Test void eachBuyerStatusHasCorrectRowsAndCount()throws Exception { seedMixed();for(String status:List.of("PENDING","ACCEPTED","CANCELLED")){var p=check(filtered(buyerToken,"BUYER",status,1,20),200);assertEquals(status.equals("PENDING")?4:2,p.get("total").asInt());assertEquals(status,p.get("status").asText());for(var row:p.get("items")){assertEquals(status,row.get("status").asText());assertEquals(buyer,row.at("/buyer/id").asLong());}} }
 @Test void sellerStatusCombinesWithSessionRole()throws Exception { seedMixed();for(String status:List.of("PENDING","ACCEPTED","CANCELLED")){var p=check(filtered(sellerToken,"SELLER",status,1,20),200);assertEquals(status.equals("PENDING")?6:2,p.get("total").asInt());for(var row:p.get("items")){assertEquals(seller,row.at("/seller/id").asLong());assertEquals(status,row.get("status").asText());}}assertEquals(0,check(filtered(sellerToken,"BUYER","PENDING",1,5),200).get("total").asInt()); }
 @Test void invalidValuesAndSqlFragmentsRejectedWithoutWrites()throws Exception { seedMixed();var before=jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id");for(String status:List.of("ALL","pending"," PENDING ","SOLD","PENDING' OR 1=1 --","status; DROP TABLE users"))assertEquals("INVALID_PURCHASE_INTENT",check(filtered(buyerToken,"BUYER",status,1,5),400).get("code").asText());assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id")); }
 @Test void filteredPaginationHasStableIdTieBreakAndNoDuplicates()throws Exception { seedMixed();jdbc.update("UPDATE purchase_intents SET created_at='2026-01-01 00:00:00'");var found=new ArrayList<Long>();for(int n=1;n<=2;n++){var p=check(filtered(buyerToken,"BUYER","PENDING",n,2),200);assertEquals(4,p.get("total").asInt());assertEquals(2,p.get("totalPages").asInt());found.addAll(rowIds(p));}var expected=new ArrayList<>(List.of(ids.get(0),ids.get(1),ids.get(2),ids.get(5)));expected.sort(Comparator.reverseOrder());assertEquals(expected,found);assertEquals(4,new HashSet<>(found).size()); }
 @Test void filteredLastPageClampsAndEmptyResultsAreOneOfOne()throws Exception { seedMixed();var p=check(filtered(buyerToken,"BUYER","PENDING",99,3),200);assertEquals(2,p.get("page").asInt());assertEquals(1,p.get("items").size());var empty=check(filtered(otherToken,"BUYER","ACCEPTED",99,3),200);assertEquals(0,empty.get("total").asInt());assertEquals(1,empty.get("page").asInt());assertEquals(1,empty.get("totalPages").asInt());assertEquals("ACCEPTED",empty.get("status").asText()); }
 @Test void anonymousAndSpoofedOwnerCannotReadOthersFilteredRows()throws Exception { seedMixed();check(get("/api/purchase-intents/mine").param("status","ACCEPTED"),401);check(get("/api/purchase-intents/mine").param("status","INVALID"),401);var p=check(filtered(otherToken,"BUYER","ACCEPTED",1,5).param("buyerId",""+buyer).param("userId",""+buyer),200);assertEquals(0,p.get("total").asInt());assertEquals(0,check(filtered(otherToken,"SELLER","PENDING",1,5).param("sellerId",""+seller),200).get("total").asInt()); }
 @Test void everyRoleAndStatusQueryIsReadOnly()throws Exception { seedMixed();var intents=jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id");var products=jdbc.queryForList("SELECT * FROM products ORDER BY id");for(String t:List.of(buyerToken,sellerToken,otherToken))for(String role:List.of("BUYER","SELLER"))for(String status:List.of("","PENDING","ACCEPTED","CANCELLED"))check(filtered(t,role,status,1,3),200);assertEquals(intents,jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id"));assertEquals(products,jdbc.queryForList("SELECT * FROM products ORDER BY id")); }
 @Test void acceptanceMovesCountsAndClampsFormerLastPendingPage()throws Exception { seedMixed();assertEquals(2,check(filtered(buyerToken,"BUYER","PENDING",2,3),200).get("page").asInt());check(post("/api/purchase-intents/"+ids.get(5)+"/accept").header("Authorization","Bearer "+sellerToken),200);var p=check(filtered(buyerToken,"BUYER","PENDING",2,3),200);assertEquals(3,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals(3,check(filtered(buyerToken,"BUYER","ACCEPTED",1,5),200).get("total").asInt());assertEquals(8,check(page(buyerToken,"BUYER",1,20),200).get("total").asInt()); }
 @Test void cancellationMovesRowToCancelledHistory()throws Exception { seedMixed();long id=ids.get(5);check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);assertEquals(3,check(filtered(buyerToken,"BUYER","PENDING",1,5),200).get("total").asInt());var p=check(filtered(buyerToken,"BUYER","CANCELLED",1,5),200);assertEquals(3,p.get("total").asInt());assertTrue(rowIds(p).contains(id));assertEquals(10,count()); }
 @Test void enumAndPageMetadataAreExplicitAndStrict()throws Exception { assertEquals(PurchaseIntentStatusFilter.ALL,PurchaseIntentStatusFilter.parse(null));assertEquals(PurchaseIntentStatusFilter.ALL,PurchaseIntentStatusFilter.parse(""));assertEquals("ACCEPTED",PurchaseIntentStatusFilter.parse("ACCEPTED").value());assertThrows(PurchaseIntentException.class,()->PurchaseIntentStatusFilter.parse("ALL"));seedMixed();var p=check(filtered(buyerToken,"BUYER","ACCEPTED",99,1),200);assertEquals("BUYER",p.get("role").asText());assertEquals("ACCEPTED",p.get("status").asText());assertEquals(1,p.get("size").asInt());assertEquals(2,p.get("page").asInt());assertEquals(2,p.get("totalPages").asInt()); }
 @Test void queryNoStoreAndWritesRemainCompatible()throws Exception { seedMixed();var r=mvc.perform(filtered(buyerToken,"BUYER","ACCEPTED",1,5)).andReturn().getResponse();assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-store",mvc.perform(filtered(buyerToken,"BUYER","INVALID",1,5)).andReturn().getResponse().getHeader("Cache-Control"));var replay=check(post("/api/purchase-intents/"+ids.get(3)+"/accept").header("Authorization","Bearer "+sellerToken),200);assertTrue(replay.get("replayed").asBoolean());assertEquals("ACCEPTED",replay.at("/intent/status").asText()); }
}
