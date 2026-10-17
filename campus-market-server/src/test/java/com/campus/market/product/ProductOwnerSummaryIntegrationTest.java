package com.campus.market.product;

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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON47_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON47_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson47_verify\\?.*")
class ProductOwnerSummaryIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate jdbc;
 @Autowired ProductService service;
 @Autowired ProductRepository repository;
 final JsonMapper mapper=JsonMapper.builder().build();
 long owner,other;String token,otherToken;
 JsonNode check(MockHttpServletRequestBuilder r,int expected)throws Exception{
  var response=mvc.perform(r).andReturn().getResponse();assertEquals(expected,response.getStatus(),response.getContentAsString(StandardCharsets.UTF_8));
  return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
 }
 String login(String username)throws Exception{return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",username,"password","Campus2026!"))),200).get("accessToken").asText();}


 @BeforeEach void fixtures()throws Exception{
  assertTrue(System.getenv("LESSON47_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson47_verify\\?.*"));
  jdbc.update("DELETE FROM product_publications");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String name:new String[]{"owner_47","other_47"})check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
  owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_47'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_47'",Long.class);token=login("owner_47");otherToken=login("other_47");
  String[] states={"ON_SALE","ON_SALE","OFF_SHELF","SOLD","SOLD","SOLD"};long[] prices={100,200,300,400,501,999};
  for(int i=0;i<states.length;i++)jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)","概览商品"+i,prices[i],owner,states[i]);
  jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES('他人售出',100000,?,'SOLD'),('他人在售',777,?,'ON_SALE')",other,other);
  jdbc.update("INSERT INTO products(title,price_in_cents,status) VALUES('历史售出',55000,'SOLD'),('历史在售',550,'ON_SALE')");
 }
 MockHttpServletRequestBuilder summary(){return get("/api/products/mine/summary").header("Authorization","Bearer "+token);}
 void assertSummary(JsonNode d,long total,long on,long off,long sold,long money){assertEquals(total,d.get("total").asLong());assertEquals(on,d.get("onSale").asLong());assertEquals(off,d.get("offShelf").asLong());assertEquals(sold,d.get("sold").asLong());assertEquals(money,d.get("soldPriceInCents").asLong());assertEquals(total,on+off+sold);}
 @Test void mixedStatesHaveExactCountsAndSoldPriceSum()throws Exception{assertSummary(check(summary(),200),6,2,1,3,1900);}
 @Test void emptyOwnerHasZeroInsteadOfNull()throws Exception{jdbc.update("DELETE FROM products WHERE seller_id=?",owner);assertSummary(check(summary(),200),0,0,0,0,0);}
 @Test void listFiltersAndClientSellerIdCannotChangeSummaryScope()throws Exception{
  assertSummary(check(summary().param("keyword","不存在").param("status","SOLD").param("sort","invalid").param("page","9").param("sellerId",""+other).param("userId",""+other),200),6,2,1,3,1900);
 }
 @Test void otherUserAndLegacyOwnerlessProductsAreExcluded()throws Exception{
  assertSummary(check(get("/api/products/mine/summary").header("Authorization","Bearer "+otherToken).param("sellerId",""+owner),200),2,1,0,1,100000);
 }
 @Test void anonymousInvalidAndExpiredSessionsAreRejected()throws Exception{
  check(get("/api/products/mine/summary"),401);check(get("/api/products/mine/summary").header("Authorization","Bearer "+"A".repeat(43)),401);
  jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");check(summary(),401);
 }
 @Test void statusTransitionsAndRepeatedSoldCallsNeverDoubleCount()throws Exception{
  long id=jdbc.queryForObject("SELECT MIN(id) FROM products WHERE seller_id=? AND status='ON_SALE'",Long.class,owner);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"OFF_SHELF\",\"version\":0}"),200);assertSummary(check(summary(),200),6,1,2,3,1900);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"ON_SALE\",\"version\":1}"),200);assertSummary(check(summary(),200),6,2,1,3,1900);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"SOLD\",\"version\":2}"),200);assertSummary(check(summary(),200),6,1,1,4,2000);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"SOLD\",\"version\":3}"),200);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"SOLD\",\"version\":2}"),409);assertSummary(check(summary(),200),6,1,1,4,2000);
 }
 @Test void sumUsesLongCentsBeyondIntegerRange()throws Exception{
  for(int i=0;i<30;i++)jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES('高价售出',99999999,?,'SOLD')",owner);
  assertSummary(check(summary(),200),36,2,1,33,3_000_001_870L);
 }
 @Test void favoritesAndCommentsDoNotMultiplyProductCounts()throws Exception{
  long id=jdbc.queryForObject("SELECT MIN(id) FROM products WHERE seller_id=? AND status='SOLD'",Long.class,owner);
  jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(?,?),(?,?)",owner,id,other,id);
  for(long author:new long[]{owner,other})jdbc.update("INSERT INTO product_comments(product_id,author_id,content,request_id) VALUES(?,?,'重复关联行',?)",id,author,java.util.UUID.randomUUID().toString());
  assertSummary(check(summary(),200),6,2,1,3,1900);
 }
 @Test void publicationReplayDoesNotIncreaseTotalsTwice()throws Exception{
  String body=mapper.writeValueAsString(Map.of("requestId","78e38c60-f066-41c5-914c-f7cc4507ecba","product",Map.of("title","幂等概览商品","description","测试","priceInCents",4700)));
  check(post("/api/product-publications").header("Authorization","Bearer "+token).contentType("application/json").content(body),201);
  check(post("/api/product-publications").header("Authorization","Bearer "+token).contentType("application/json").content(body),200);assertSummary(check(summary(),200),7,3,1,3,1900);
 }
 @Test void readsAreNoStoreReadOnlyAndExposeOnlyAggregateFields()throws Exception{
  var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");for(int i=0;i<3;i++)check(summary(),200);assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));
  var response=mvc.perform(summary()).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));var data=mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));assertEquals(5,data.size());assertFalse(data.has("username"));assertFalse(data.has("sellerId"));
 }
}
