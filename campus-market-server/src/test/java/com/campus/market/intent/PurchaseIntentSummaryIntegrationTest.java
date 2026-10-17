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

@SpringBootTest(properties={"spring.datasource.url=${LESSON57_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON57_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson57_verify\\?.*")
class PurchaseIntentSummaryIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON57_DB_URL").contains("/campus_lesson57_verify?"));
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

 void assertSummary(JsonNode p,long all,long pending,long accepted,long cancelled) {
  assertEquals(all,p.at("/summary/total").asLong());assertEquals(pending,p.at("/summary/pending").asLong());assertEquals(accepted,p.at("/summary/accepted").asLong());assertEquals(cancelled,p.at("/summary/cancelled").asLong());assertEquals(all,pending+accepted+cancelled);
  String key=switch(p.get("status").asText()){case "PENDING"->"pending";case "ACCEPTED"->"accepted";case "CANCELLED"->"cancelled";default->"total";};assertEquals(p.at("/summary/"+key).asLong(),p.get("total").asLong());
 }
 @Test void emptyRolesReturnNumericZeros()throws Exception {for(String role:List.of("BUYER","SELLER"))for(String status:List.of("","PENDING","ACCEPTED","CANCELLED")){var p=check(filtered(buyerToken,role,status,99,5),200);assertSummary(p,0,0,0,0);assertEquals(1,p.get("page").asInt());assertEquals(0,p.get("items").size());assertTrue(p.at("/summary/pending").isIntegralNumber());}}
 @Test void buyerSummaryCountsAllRowsBeyondCurrentPage()throws Exception {seedMixed();var p=check(page(buyerToken,"BUYER",1,3),200);assertSummary(p,8,4,2,2);assertEquals(3,p.get("items").size());assertEquals(8,p.get("total").asInt());}
 @Test void sellerSummaryIncludesBothBuyersAndIsRoleScoped()throws Exception {seedMixed();assertSummary(check(page(sellerToken,"SELLER",1,3),200),10,6,2,2);assertSummary(check(page(sellerToken,"BUYER",1,3),200),0,0,0,0);assertSummary(check(page(buyerToken,"SELLER",1,3),200),0,0,0,0);}
 @Test void summaryDoesNotChangeWithStatusOrClampedPage()throws Exception {seedMixed();JsonNode expected=null;for(String status:List.of("","PENDING","ACCEPTED","CANCELLED"))for(int n:List.of(1,2,999)){var p=check(filtered(buyerToken,"BUYER",status,n,2),200);assertSummary(p,8,4,2,2);if(expected==null)expected=p.get("summary");else assertEquals(expected,p.get("summary"));}}
 @Test void otherSessionAndSpoofedIdsCannotBorrowCounts()throws Exception {seedMixed();var p=check(filtered(otherToken,"BUYER","ACCEPTED",1,5).param("buyerId",""+buyer).param("sellerId",""+seller).param("userId",""+buyer),200);assertSummary(p,2,2,0,0);assertSummary(check(page(otherToken,"SELLER",1,5),200),0,0,0,0);check(get("/api/purchase-intents/mine"),401);}
 @Test void acceptingAndReplayMoveOneCountAndPreserveAllTotal()throws Exception {seedMixed();long id=ids.get(5);var a=post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken);check(a,200);assertSummary(check(filtered(buyerToken,"BUYER","PENDING",99,3),200),8,3,3,2);var sellerPage=check(filtered(sellerToken,"SELLER","ACCEPTED",1,5),200);assertSummary(sellerPage,10,5,3,2);assertTrue(check(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken),200).get("replayed").asBoolean());assertEquals(sellerPage,check(filtered(sellerToken,"SELLER","ACCEPTED",1,5),200));}
 @Test void cancellingAndReplayMoveOneCountAndPreserveHistory()throws Exception {seedMixed();long id=ids.get(5);check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200);assertSummary(check(filtered(buyerToken,"BUYER","CANCELLED",1,5),200),8,3,2,3);assertSummary(check(page(sellerToken,"SELLER",1,5),200),10,5,2,3);assertTrue(check(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken),200).get("replayed").asBoolean());assertSummary(check(page(buyerToken,"BUYER",1,5),200),8,3,2,3);assertEquals(10,count());}
 @Test void productStatusAndPriceChangesDoNotRewriteHistoricalSummary()throws Exception {seedMixed();jdbc.update("UPDATE products SET status='SOLD',price_in_cents=9999,version=version+1");assertSummary(check(page(buyerToken,"BUYER",1,5),200),8,4,2,2);assertSummary(check(page(sellerToken,"SELLER",1,5),200),10,6,2,2);}
 @Test void summaryReadsAreNoStoreAndNeverChangeRows()throws Exception {seedMixed();var before=jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id");var products=jdbc.queryForList("SELECT * FROM products ORDER BY id");for(String role:List.of("BUYER","SELLER"))for(String status:List.of("","PENDING","ACCEPTED","CANCELLED")){var r=mvc.perform(filtered(buyerToken,role,status,1,3)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));}check(filtered(buyerToken,"BUYER","ALL",1,5),400);assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intents ORDER BY id"));assertEquals(products,jdbc.queryForList("SELECT * FROM products ORDER BY id"));}
 @Test void concurrentAcceptAndCancelProduceOneTerminalCount()throws Exception {seedMixed();long id=ids.get(5);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var accept=pool.submit(()->{start.await();return mvc.perform(post("/api/purchase-intents/"+id+"/accept").header("Authorization","Bearer "+sellerToken)).andReturn().getResponse().getStatus();});var cancel=pool.submit(()->{start.await();return mvc.perform(post("/api/purchase-intents/"+id+"/cancel").header("Authorization","Bearer "+buyerToken)).andReturn().getResponse().getStatus();});start.countDown();var codes=new ArrayList<>(List.of(accept.get(10,TimeUnit.SECONDS),cancel.get(10,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);String status=jdbc.queryForObject("SELECT status FROM purchase_intents WHERE id=?",String.class,id);assertSummary(check(page(buyerToken,"BUYER",1,5),200),8,3,status.equals("ACCEPTED")?3:2,status.equals("CANCELLED")?3:2);}finally{pool.shutdownNow();}}
}
