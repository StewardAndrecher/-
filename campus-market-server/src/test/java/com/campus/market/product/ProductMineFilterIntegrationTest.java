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

@SpringBootTest(properties={"spring.datasource.url=${LESSON44_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON44_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson44_verify\\?.*")
class ProductMineFilterIntegrationTest {
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
  assertTrue(System.getenv("LESSON44_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson44_verify\\?.*"));
  jdbc.update("DELETE FROM product_publications");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String name:new String[]{"owner_44","other_44"})check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
  owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_44'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_44'",Long.class);token=login("owner_44");otherToken=login("other_44");
  for(ProductStatus state:ProductStatus.values()){
   for(int i=0;i<3;i++)jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)",state.name()+i,100+i,owner,state.name());
   jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)","其他卖家"+state,100,other,state.name());
  }
  jdbc.update("INSERT INTO products(title,price_in_cents) VALUES('历史无主商品',100)");
 }
 MockHttpServletRequestBuilder mine(String status){return get("/api/products/mine").header("Authorization","Bearer "+token).param("status",status).param("size","2");}
 @Test void omittedAndEmptyFilterReturnAllOwnedStatesOnly()throws Exception{
  for(var request:new MockHttpServletRequestBuilder[]{get("/api/products/mine").header("Authorization","Bearer "+token),mine("")}){
   var data=check(request,200);assertEquals(9,data.get("total").asLong());for(var p:data.get("records"))assertEquals(owner,p.get("seller").get("id").asLong());
  }
 }
 @Test void everyStateHasMatchingCountAndStablePaginatedRecords()throws Exception{
  for(ProductStatus state:ProductStatus.values()){
   var first=check(mine(state.name()),200);var second=check(mine(state.name()).param("page","2"),200);
   assertEquals(3,first.get("total").asLong());assertEquals(3,second.get("total").asLong());assertEquals(2,first.get("records").size());assertEquals(1,second.get("records").size());
   long previous=Long.MAX_VALUE;for(var page:new JsonNode[]{first,second})for(var item:page.get("records")){assertEquals(state.name(),item.get("status").asText());assertEquals(owner,item.get("seller").get("id").asLong());assertTrue(item.get("id").asLong()<previous);previous=item.get("id").asLong();}
  }
 }
 @Test void invalidFiltersAreRejectedIncludingSqlFragmentsAndWhitespace()throws Exception{
  for(String state:new String[]{"ALL","sold"," SOLD","ON_SALE ","ON_SALE,SOLD","SOLD' OR 1=1 --","DELETED"})assertEquals("INVALID_PRODUCT",check(mine(state),400).get("code").asText());
  assertEquals(13,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
 }
 @Test void missingExpiredAndForeignSessionsCannotReadAnotherSellersRows()throws Exception{
  check(get("/api/products/mine").param("status","SOLD"),401);
  var ownOther=check(get("/api/products/mine").header("Authorization","Bearer "+otherToken).param("status","SOLD").param("sellerId",""+owner),200);assertEquals(1,ownOther.get("total").asLong());assertEquals(other,ownOther.get("records").get(0).get("seller").get("id").asLong());
  jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");check(mine("SOLD"),401);
 }
 @Test void foreignSellerParametersDoNotOverrideTokenIdentity()throws Exception{
  var data=check(mine("OFF_SHELF").param("sellerId",""+other).param("userId",""+other),200);assertEquals(3,data.get("total").asLong());for(var p:data.get("records"))assertEquals(owner,p.get("seller").get("id").asLong());
 }
 @Test void emptyAndOutOfRangePagesKeepCorrectFilteredTotal()throws Exception{
  var empty=check(mine("SOLD").param("page","9"),200);assertEquals(3,empty.get("total").asLong());assertEquals(0,empty.get("records").size());assertEquals(9,empty.get("page").asInt());
  jdbc.update("DELETE FROM products WHERE seller_id=? AND status='OFF_SHELF'",owner);var removed=check(mine("OFF_SHELF"),200);assertEquals(0,removed.get("total").asLong());assertEquals(0,removed.get("records").size());
  check(mine("SOLD").param("page","0"),400);check(get("/api/products/mine").header("Authorization","Bearer "+token).param("status","SOLD").param("size","51"),400);
 }
 @Test void changingStatusMovesRowBetweenServerFilteredTotals()throws Exception{
  long id=jdbc.queryForObject("SELECT MAX(id) FROM products WHERE seller_id=? AND status='ON_SALE'",Long.class,owner);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"OFF_SHELF\",\"version\":0}"),200);
  assertEquals(2,check(mine("ON_SALE"),200).get("total").asLong());assertEquals(4,check(mine("OFF_SHELF"),200).get("total").asLong());assertEquals(9,check(mine(""),200).get("total").asLong());
 }
 @Test void filterReadsDoNotWriteRowsAndLegacyMethodsRemainCompatible()throws Exception{
  var before=jdbc.queryForList("SELECT id,title,status,version,seller_id FROM products ORDER BY id");
  assertEquals(9,service.listMyProducts(1,50,"Bearer "+token).total());assertEquals(9,repository.countBySeller(owner));assertEquals(9,repository.findPageBySeller(owner,50,0).size());
  for(ProductStatus state:ProductStatus.values())check(mine(state.name()),200);
  assertEquals(before,jdbc.queryForList("SELECT id,title,status,version,seller_id FROM products ORDER BY id"));
  assertEquals("no-store",mvc.perform(mine("SOLD")).andReturn().getResponse().getHeader("Cache-Control"));
 }
}
