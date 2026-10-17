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

@SpringBootTest(properties={"spring.datasource.url=${LESSON46_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON46_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson46_verify\\?.*")
class ProductMineSortIntegrationTest {
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
  assertTrue(System.getenv("LESSON46_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson46_verify\\?.*"));
  jdbc.update("DELETE FROM product_publications");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String name:new String[]{"owner_46","other_46"})check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
  owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_46'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_46'",Long.class);token=login("owner_46");otherToken=login("other_46");
  for(ProductStatus state:ProductStatus.values()){
   for(int i=0;i<3;i++)jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)","数学教材"+state.ordinal()+i,100+i,owner,state.name());
   jdbc.update("INSERT INTO products(title,price_in_cents,seller_id,status) VALUES(?,?,?,?)","其他卖家数学教材"+state,100,other,state.name());
  }
  for(String title:new String[]{"数学100%真题","数学A_B笔记","数学!重点"})jdbc.update("INSERT INTO products(title,price_in_cents,seller_id) VALUES(?,100,?)",title,owner);
  jdbc.update("INSERT INTO products(title,description,price_in_cents,seller_id) VALUES('台灯','数学教材',100,?)",owner);
  jdbc.update("INSERT INTO products(title,price_in_cents) VALUES('历史数学教材',100)");
  jdbc.update("UPDATE products SET created_at='2026-01-01 00:00:00'");
  jdbc.update("UPDATE products SET created_at='2026-01-02 00:00:00' WHERE id=(SELECT target FROM (SELECT MIN(id) AS target FROM products WHERE seller_id=?) fixture)",owner);
 }
 MockHttpServletRequestBuilder mine(String keyword,String status){return get("/api/products/mine").header("Authorization","Bearer "+token).param("keyword",keyword).param("status",status);}

 MockHttpServletRequestBuilder sorted(String keyword,String status,String sort){return mine(keyword,status).param("sort",sort);}
 java.util.List<Long> expected(String keyword,String status,String sort){
  var rows=jdbc.queryForList("SELECT id,title,price_in_cents,created_at,status FROM products WHERE seller_id=?",owner);
  java.util.Comparator<java.util.Map<String,Object>> tie=java.util.Comparator.comparingLong(r->((Number)r.get("id")).longValue());
  java.util.Comparator<java.util.Map<String,Object>> price=java.util.Comparator.comparingLong(r->((Number)r.get("price_in_cents")).longValue());
  java.util.Comparator<java.util.Map<String,Object>> time=java.util.Comparator.comparing(r->r.get("created_at").toString());
  var order=(sort.equals("PRICE_ASC")?price:sort.equals("PRICE_DESC")?price.reversed():time.reversed()).thenComparing(tie.reversed());
  return rows.stream().filter(r->r.get("title").toString().contains(keyword)&&(status.isEmpty()||status.equals(r.get("status")))).sorted(order).map(r->((Number)r.get("id")).longValue()).toList();
 }
 java.util.List<Long> ids(JsonNode data){var result=new java.util.ArrayList<Long>();for(var row:data.get("records"))result.add(row.get("id").asLong());return result;}
 @Test void defaultEmptyAndLegacyCallsKeepNewestOrder()throws Exception{
  var all=expected("","","NEWEST");
  assertEquals(all.subList(0,10),ids(check(get("/api/products/mine").header("Authorization","Bearer "+token),200)));
  assertEquals(all.subList(0,10),ids(check(sorted("","",""),200)));
  assertEquals(all,service.listMyProducts(1,50,"Bearer "+token).records().stream().map(ProductResponse::id).toList());
  assertEquals(all,service.listMyProducts(1,50,"","Bearer "+token).records().stream().map(ProductResponse::id).toList());
  assertEquals(all,service.listMyProducts(1,50,"","","Bearer "+token).records().stream().map(ProductResponse::id).toList());
  assertEquals(all,repository.findPageBySeller(owner,50,0).stream().map(ProductResponse::id).toList());
  assertEquals(all,repository.findPageBySeller(owner,null,"",50,0).stream().map(ProductResponse::id).toList());
 }
 @Test void ascendingAcrossAllPagesUsesStableSamePriceTies()throws Exception{
  var result=new java.util.ArrayList<Long>();for(int page=1;page<=7;page++){var data=check(sorted("","","PRICE_ASC").param("size","2").param("page",""+page),200);assertEquals(13,data.get("total").asLong());result.addAll(ids(data));}
  assertEquals(expected("","","PRICE_ASC"),result);assertEquals(13,new java.util.HashSet<>(result).size());
 }
 @Test void descendingAcrossAllPagesUsesStableSamePriceTies()throws Exception{
  var result=new java.util.ArrayList<Long>();for(int page=1;page<=7;page++)result.addAll(ids(check(sorted("","","PRICE_DESC").param("size","2").param("page",""+page),200)));
  assertEquals(expected("","","PRICE_DESC"),result);assertEquals(13,new java.util.HashSet<>(result).size());
 }
 @Test void newestUsesCreationTimeBeforeIdAndKeepsSameTimeTies()throws Exception{
  var result=ids(check(sorted("","","NEWEST").param("size","50"),200));long oldestId=jdbc.queryForObject("SELECT MIN(id) FROM products WHERE seller_id=?",Long.class,owner);
  assertEquals(oldestId,result.get(0));assertEquals(expected("","","NEWEST"),result);
 }
 @Test void allThreeSortsCombineWithEveryStateAndKeyword()throws Exception{
  for(String sort:new String[]{"NEWEST","PRICE_ASC","PRICE_DESC"})for(String status:new String[]{"ON_SALE","OFF_SHELF","SOLD"}){
   var data=check(sorted("教材",status,sort).param("size","50"),200);assertEquals(3,data.get("total").asLong());assertEquals(expected("教材",status,sort),ids(data));for(var item:data.get("records"))assertEquals(owner,item.get("seller").get("id").asLong());
  }
 }
 @Test void invalidSortsAndSqlFragmentsAreRejectedWithoutWrites()throws Exception{
  var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");
  for(String sort:new String[]{"price_asc"," PRICE_ASC","PRICE_DESC ","MOST_FAVORITED","p.id DESC","PRICE_ASC; DROP TABLE products","NEWEST,PRICE_ASC"})assertEquals("INVALID_PRODUCT",check(sorted("","",sort),400).get("code").asText());
  assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));assertEquals(ProductOwnerSort.NEWEST,ProductOwnerSort.parse(null));
 }
 @Test void sortedReadsKeepSellerIsolationAndRequireLiveSession()throws Exception{
  var data=check(sorted("教材","SOLD","PRICE_ASC").param("sellerId",""+other),200);assertEquals(expected("教材","SOLD","PRICE_ASC"),ids(data));
  var foreign=check(get("/api/products/mine").header("Authorization","Bearer "+otherToken).param("sort","PRICE_DESC").param("keyword","教材").param("sellerId",""+owner),200);assertEquals(3,foreign.get("total").asLong());for(var row:foreign.get("records"))assertEquals(other,row.get("seller").get("id").asLong());
  check(get("/api/products/mine").param("sort","PRICE_ASC"),401);jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");check(sorted("","","PRICE_DESC"),401);
 }
 @Test void emptyOutOfRangeAndInvalidPagesKeepCorrectTotal()throws Exception{
  for(String sort:new String[]{"NEWEST","PRICE_ASC","PRICE_DESC"}){
   var data=check(sorted("教材","SOLD",sort).param("size","2").param("page","9"),200);assertEquals(3,data.get("total").asLong());assertEquals(0,data.get("records").size());
   var empty=check(sorted("不存在","",sort),200);assertEquals(0,empty.get("total").asLong());assertEquals(0,empty.get("records").size());
  }
  check(sorted("","","PRICE_ASC").param("page","0"),400);check(sorted("","","PRICE_DESC").param("size","51"),400);
 }
 @Test void statusChangeRequeriesAllCriteriaInCorrectPriceOrder()throws Exception{
  long id=jdbc.queryForObject("SELECT MAX(id) FROM products WHERE seller_id=? AND status='ON_SALE' AND title LIKE '数学教材%'",Long.class,owner);
  check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"OFF_SHELF\",\"version\":0}"),200);
  for(String sort:new String[]{"PRICE_ASC","PRICE_DESC"}){var data=check(sorted("教材","ON_SALE",sort),200);assertEquals(2,data.get("total").asLong());assertEquals(expected("教材","ON_SALE",sort),ids(data));assertEquals(4,check(sorted("教材","OFF_SHELF",sort),200).get("total").asLong());}
 }
 @Test void sortedReadsAreReadOnlyNoStoreAndPublicCatalogStillHasFavorites()throws Exception{
  var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");for(String sort:new String[]{"NEWEST","PRICE_ASC","PRICE_DESC"})check(sorted("教材","",sort),200);assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));
  assertEquals("no-store",mvc.perform(sorted("教材","SOLD","PRICE_ASC")).andReturn().getResponse().getHeader("Cache-Control"));
  assertEquals(4,ProductSort.catalog().size());assertTrue(ProductSort.catalog().stream().anyMatch(o->o.code().equals("MOST_FAVORITED")));
 }
}
