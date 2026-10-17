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

@SpringBootTest(properties={"spring.datasource.url=${LESSON53_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON53_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson53_verify\\?.*")
class PurchaseIntentIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String sellerToken,buyerToken,otherToken;long seller,buyer,other,product;
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
  assertEquals(status,response.getStatus(),text);return text.isEmpty()?null:mapper.readTree(text);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON53_DB_URL").contains("/campus_lesson53_verify?"));
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
 @Test void loginRequiredForBothEndpoints()throws Exception { check(post("/api/products/"+product+"/purchase-intent").contentType("application/json").content("{\"productVersion\":7}"),401);check(get("/api/purchase-intents/mine"),401);assertEquals(0,count()); }
 @Test void creationCapturesServerSnapshotWithoutChangingProduct()throws Exception {
  var before=jdbc.queryForMap("SELECT * FROM products WHERE id=?",product);var result=check(create(product,buyerToken,7),201);var row=result.get("intent");assertFalse(result.get("replayed").asBoolean());assertEquals("九成新教材",row.get("productTitleSnapshot").asText());assertEquals(3500,row.get("priceInCentsSnapshot").asLong());assertEquals(7,row.get("productVersionSnapshot").asLong());assertEquals(buyer,row.at("/buyer/id").asLong());assertEquals(seller,row.at("/seller/id").asLong());assertEquals("PENDING",row.get("status").asText());assertEquals(before,jdbc.queryForMap("SELECT * FROM products WHERE id=?",product));assertEquals(1,count());
 }
 @Test void duplicateReturnsOriginalRecord()throws Exception { var first=check(create(product,buyerToken,7),201);var replay=check(create(product,buyerToken,7),200);assertTrue(replay.get("replayed").asBoolean());assertEquals(first.get("intent"),replay.get("intent"));assertEquals(1,count()); }
 @Test void replaySurvivesPriceAndStatusChanges()throws Exception { var first=check(create(product,buyerToken,7),201);jdbc.update("UPDATE products SET title='已修改',price_in_cents=9900,status='SOLD',version=8 WHERE id=?",product);var replay=check(create(product,buyerToken,7),200);assertEquals(first.get("intent"),replay.get("intent"));assertTrue(replay.get("replayed").asBoolean()); }
 @Test void staleVersionRejectedUntilRefreshed()throws Exception { assertEquals("PRODUCT_CHANGED",check(create(product,buyerToken,6),409).get("code").asText());assertEquals(0,count());check(create(product,buyerToken,7),201); }
 @Test void ownerCannotBuyOwnProduct()throws Exception { assertEquals("SELF_PURCHASE",check(create(product,sellerToken,7),403).get("code").asText());assertEquals(0,count()); }
 @Test void ownerlessLegacyProductCannotReceiveIntents()throws Exception { long id=seed("历史商品",1000,null,"ON_SALE",0);assertEquals("INTENT_SELLER_UNAVAILABLE",check(create(id,buyerToken,0),409).get("code").asText());assertEquals(0,count()); }
 @Test void offShelfAndSoldPreventNewIntents()throws Exception { for(String status:List.of("OFF_SHELF","SOLD")){jdbc.update("UPDATE products SET status=? WHERE id=?",status,product);assertEquals("PRODUCT_UNAVAILABLE",check(create(product,buyerToken,7),409).get("code").asText());}assertEquals(0,count()); }
 @Test void nonexistentProductIsNotFound()throws Exception { check(create(9007199254740991L,buyerToken,0),404);assertEquals(0,count()); }
 @Test void invalidVersionsIdsAndBodiesDoNotWrite()throws Exception {
  for(long id:new long[]{0,-1,9007199254740992L})check(create(id,buyerToken,7),400);
  for(String body:List.of("{}","{\"productVersion\":-1}","{\"productVersion\":9007199254740992}","{\"productVersion\":\"invalid\"}","{\"productVersion\":7.9}","{\"productVersion\":\"7\"}"))check(create(product,buyerToken,7).content(body),400);assertEquals(0,count());
 }
 @Test void simultaneousDuplicatesAreOneCreationAndOneReplay()throws Exception {
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {var tasks=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();return mvc.perform(create(product,buyerToken,7)).andReturn().getResponse().getStatus();}));start.countDown();var statuses=new ArrayList<Integer>();for(var t:tasks)statuses.add(t.get(15,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,201),statuses);assertEquals(1,count());}
  finally {pool.shutdownNow();}
 }
 @Test void differentBuyersCanExpressIntentWithoutReservingProduct()throws Exception { check(create(product,buyerToken,7),201);check(create(product,otherToken,7),201);assertEquals(2,count());assertEquals("ON_SALE",jdbc.queryForObject("SELECT status FROM products WHERE id=?",String.class,product)); }
 @Test void buyerAndSellerListsAreScopedToSessionNotRequestUserId()throws Exception {
  check(create(product,buyerToken,7),201);assertEquals(1,check(page(buyerToken,"BUYER",1,5),200).get("total").asInt());assertEquals(0,check(page(buyerToken,"SELLER",1,5),200).get("total").asInt());assertEquals(1,check(page(sellerToken,"SELLER",1,5),200).get("total").asInt());assertEquals(0,check(page(otherToken,"BUYER",1,5).param("buyerId",""+buyer).param("userId",""+buyer),200).get("total").asInt());assertEquals(0,check(page(otherToken,"SELLER",1,5).param("sellerId",""+seller),200).get("total").asInt());
 }
 @Test void paginationHasStableTieBreakAndClampsLastPage()throws Exception {
  for(int i=0;i<6;i++){long id=seed("分页教材"+i,1000,seller,"ON_SALE",0);check(create(id,buyerToken,0),201);}jdbc.update("UPDATE purchase_intents SET created_at='2026-01-01 00:00:00'");var seen=new ArrayList<Long>();for(int n=1;n<=2;n++){var p=check(page(buyerToken,"BUYER",n,3),200);assertEquals(6,p.get("total").asInt());for(var row:p.get("items"))seen.add(row.get("id").asLong());}var sorted=new ArrayList<>(seen);sorted.sort(Comparator.reverseOrder());assertEquals(sorted,seen);assertEquals(6,new HashSet<>(seen).size());assertEquals(2,check(page(buyerToken,"BUYER",99,3),200).get("page").asInt());var empty=check(page(otherToken,"BUYER",99,3),200);assertEquals(1,empty.get("page").asInt());assertEquals(1,empty.get("totalPages").asInt());
 }
 @Test void invalidQueryRejectedAndAllReadsAreReadOnly()throws Exception {
  check(create(product,buyerToken,7),201);var before=jdbc.queryForList("SELECT * FROM purchase_intents");for(String role:List.of("buyer"," SELLER ","i.buyer_id; DROP TABLE users"))check(page(buyerToken,role,1,5),400);for(int n:new int[]{0,-1,1000001})check(page(buyerToken,"BUYER",n,5),400);for(int size:new int[]{0,21})check(page(buyerToken,"BUYER",1,size),400);check(page(buyerToken,"BUYER",1,5),200);check(page(sellerToken,"SELLER",1,5),200);assertEquals(before,jdbc.queryForList("SELECT * FROM purchase_intents"));assertEquals(PurchaseIntentRole.BUYER,PurchaseIntentRole.parse(null));assertEquals(PurchaseIntentRole.BUYER,PurchaseIntentRole.parse(""));
 }
 @Test void responsesAvoidCachingAndExposeOnlyPublicParticipants()throws Exception {
  var created=mvc.perform(create(product,buyerToken,7)).andReturn().getResponse();assertEquals(201,created.getStatus());assertEquals("no-store",created.getHeader("Cache-Control"));var response=mvc.perform(page(sellerToken,"SELLER",1,5)).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));var p=mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));assertEquals(2,p.at("/items/0/buyer").size());assertEquals(2,p.at("/items/0/seller").size());assertFalse(p.toString().contains("password"));assertFalse(p.toString().contains("accessToken"));assertEquals("BUYER",check(get("/api/purchase-intents/mine").header("Authorization","Bearer "+buyerToken),200).get("role").asText());
 }
}
