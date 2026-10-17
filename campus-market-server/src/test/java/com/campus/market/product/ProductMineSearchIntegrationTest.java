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

@SpringBootTest(properties={"spring.datasource.url=${LESSON45_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON45_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson45_verify\\?.*")
class ProductMineSearchIntegrationTest {
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
  assertTrue(System.getenv("LESSON45_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson45_verify\\?.*"));
  jdbc.update("DELETE FROM product_publications");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String name:new String[]{"owner_45","other_45"})check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
  owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_45'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_45'",Long.class);token=login("owner_45");otherToken=login("other_45");
  for(ProductStatus state:ProductStatus.values()){
   for(int i=0;i<3;i++)jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)","数学教材"+state.ordinal()+i,100+i,owner,state.name());
   jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)","其他卖家数学教材"+state,100,other,state.name());
  }
  for(String title:new String[]{"数学100%真题","数学A_B笔记","数学!重点"})jdbc.update("INSERT INTO products(title,price_in_cents,seller_id) VALUES(?,100,?)",title,owner);
  jdbc.update("INSERT INTO products(title,description,price_in_cents,seller_id) VALUES('台灯','数学教材',100,?)",owner);
  jdbc.update("INSERT INTO products(title,price_in_cents) VALUES('历史数学教材',100)");
 }
 MockHttpServletRequestBuilder mine(String keyword,String status){return get("/api/products/mine").header("Authorization","Bearer "+token).param("keyword",keyword).param("status",status);}
 @Test void absentKeywordAndLegacyOverloadsKeepAllOwnedRows()throws Exception{
  assertEquals(13,check(get("/api/products/mine").header("Authorization","Bearer "+token),200).get("total").asLong());
  assertEquals(13,service.listMyProducts(1,50,"Bearer "+token).total());assertEquals(13,service.listMyProducts(1,50,"","Bearer "+token).total());
  assertEquals(13,repository.countBySeller(owner));assertEquals(13,repository.findPageBySeller(owner,50,0).size());assertEquals(7,repository.countBySeller(owner,ProductStatus.ON_SALE));
 }
 @Test void chineseKeywordSearchPaginatesWithMatchingTotalAndStableOrder()throws Exception{
  var a=check(mine("教材","").param("size","5"),200);var b=check(mine("教材","").param("size","5").param("page","2"),200);
  assertEquals(9,a.get("total").asLong());assertEquals(9,b.get("total").asLong());assertEquals(5,a.get("records").size());assertEquals(4,b.get("records").size());long last=Long.MAX_VALUE;
  for(var data:new JsonNode[]{a,b})for(var item:data.get("records")){assertEquals(owner,item.get("seller").get("id").asLong());assertTrue(item.get("title").asText().contains("教材"));assertTrue(item.get("id").asLong()<last);last=item.get("id").asLong();}
 }
 @Test void everyStatusCombinesWithKeywordForCountAndPage()throws Exception{
  for(ProductStatus state:ProductStatus.values()){
   var a=check(mine("教材",state.name()).param("size","2"),200);var b=check(mine("教材",state.name()).param("size","2").param("page","2"),200);
   assertEquals(3,a.get("total").asLong());assertEquals(3,b.get("total").asLong());assertEquals(2,a.get("records").size());assertEquals(1,b.get("records").size());
   for(var data:new JsonNode[]{a,b})for(var item:data.get("records"))assertEquals(state.name(),item.get("status").asText());
  }
 }
 @Test void blankTrimmedAndLengthBoundariesAreValidated()throws Exception{
  assertEquals(13,check(mine("   ",""),200).get("total").asLong());assertEquals(9,check(mine("  教材  ",""),200).get("total").asLong());
  assertEquals(0,check(mine("x".repeat(50),""),200).get("total").asLong());assertEquals("INVALID_PRODUCT",check(mine("x".repeat(51),""),400).get("code").asText());
  assertEquals(13,service.listMyProducts(1,50,"",null,"Bearer "+token).total());
 }
 @Test void likeMetacharactersAndSqlFragmentsAreLiteralText()throws Exception{
  for(String keyword:new String[]{"%","_","!"}){var data=check(mine(keyword,""),200);assertEquals(1,data.get("total").asLong());assertTrue(data.get("records").get(0).get("title").asText().contains(keyword));}
  assertEquals(0,check(mine("' OR 1=1 --",""),200).get("total").asLong());assertEquals(0,check(mine("!%",""),200).get("total").asLong());
  assertEquals(17,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
 }
 @Test void otherSellerAndLegacyOwnerlessRowsCannotLeakThroughSearch()throws Exception{
  var data=check(mine("教材","").param("sellerId",""+other).param("userId",""+other),200);assertEquals(9,data.get("total").asLong());for(var item:data.get("records"))assertEquals(owner,item.get("seller").get("id").asLong());
  var foreign=check(get("/api/products/mine").header("Authorization","Bearer "+otherToken).param("keyword","教材").param("status","SOLD").param("sellerId",""+owner),200);assertEquals(1,foreign.get("total").asLong());assertEquals(other,foreign.get("records").get(0).get("seller").get("id").asLong());
 }
 @Test void missingAndExpiredSessionsCannotSearch()throws Exception{
  check(get("/api/products/mine").param("keyword","教材"),401);jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");check(mine("教材",""),401);
 }
 @Test void searchOnlyMatchesTitleAndEmptyOrOutOfRangePagesHaveCorrectTotals()throws Exception{
  assertEquals(0,check(mine("不存在","ON_SALE"),200).get("total").asLong());assertEquals(1,check(mine("台灯","ON_SALE"),200).get("total").asLong());
  var data=check(mine("教材","SOLD").param("page","10").param("size","2"),200);assertEquals(3,data.get("total").asLong());assertEquals(0,data.get("records").size());
  check(mine("教材","SOLD").param("page","0"),400);check(mine("教材","bad"),400);
 }
 @Test void statusChangeKeepsSearchCountsConsistent()throws Exception{
  long id=jdbc.queryForObject("SELECT MAX(id) FROM products WHERE seller_id=? AND status='ON_SALE' AND title LIKE '数学教材%'",Long.class,owner);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"OFF_SHELF\",\"version\":0}"),200);
  assertEquals(2,check(mine("教材","ON_SALE"),200).get("total").asLong());assertEquals(4,check(mine("教材","OFF_SHELF"),200).get("total").asLong());assertEquals(9,check(mine("教材",""),200).get("total").asLong());
 }
 @Test void searchReadsPreserveRowsAndDisableCaching()throws Exception{
  var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");for(String keyword:new String[]{"教材","%","","不存在"})check(mine(keyword,""),200);assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));
  assertEquals("no-store",mvc.perform(mine("教材","SOLD")).andReturn().getResponse().getHeader("Cache-Control"));
 }
}
