package com.campus.market.draft;

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

@SpringBootTest(properties={"spring.datasource.url=${LESSON51_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON51_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson51_verify\\?.*")
class DraftBackupCategoryIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 final JsonMapper mapper=JsonMapper.builder().build();
 String token,otherToken;long owner,other;
 static final String PATH="/api/product-draft-backups",ID="00000000-0000-4000-8000-000000000049";
 Map<String,Object> content(String title) { return Map.of("title",title,"description","未完成的输入","priceText","12.","coverImageId","","categoryCode","OTHER"); }
 String body(long rev,String title) { return mapper.writeValueAsString(Map.of("sourceRevision",rev,"content",content(title))); }
 MockHttpServletRequestBuilder save(String id,long rev,String title) { return put(PATH+"/"+id).header("Authorization","Bearer "+token).contentType("application/json").content(body(rev,title)); }
 JsonNode check(MockHttpServletRequestBuilder r,int status)throws Exception {
  var response=mvc.perform(r).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString(StandardCharsets.UTF_8));
  String text=response.getContentAsString(StandardCharsets.UTF_8);return text.isEmpty()?null:mapper.readTree(text);
 }
 MockHttpServletRequestBuilder list(String t) { return get(PATH).header("Authorization","Bearer "+t); }
 @BeforeEach void setup()throws Exception {
  assertTrue(System.getenv("LESSON51_DB_URL").contains("/campus_lesson51_verify?"));
  jdbc.update("DELETE FROM product_draft_backups");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String n:List.of("owner_51","other_51")) check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!","nickname",n))),201);
  token=login("owner_51");otherToken=login("other_51");owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_51'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_51'",Long.class);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }


 String id(int i) { return new UUID(0,i).toString(); }
 void create(int i,String title,String category)throws Exception { check(save(id(i),1,title).content(body(1,title).replace("\"categoryCode\":\"OTHER\"","\"categoryCode\":\""+category+"\"")),201); }
 MockHttpServletRequestBuilder page(int n,int size,String word,String category) { return get(PATH+"/page").param("page",""+n).param("size",""+size).param("keyword",word).param("category",category).header("Authorization","Bearer "+token); }
 void bookFixtures()throws Exception {
  for(int i=0;i<4;i++)create(i,"数学教材"+i,"BOOK");create(4,"数学平板","DIGITAL");create(5,"数学计算器","DIGITAL");create(6,"英语教材","BOOK");
  jdbc.update("UPDATE product_draft_backups SET updated_at='2026-01-01 00:00:00'");
 }
 @Test void everyCatalogCategoryFiltersExactly()throws Exception {
  var codes=List.of("UNCLASSIFIED","BOOK","DIGITAL","DAILY","SPORT","OTHER");for(int i=0;i<codes.size();i++)create(i,"分类草稿"+i,codes.get(i));
  for(String code:codes){var p=check(page(1,3,"",code),200);assertEquals(code,p.get("category").asText());assertEquals(1,p.get("total").asInt());assertEquals(code,p.at("/items/0/content/categoryCode").asText());}
 }
 @Test void categoryAndKeywordApplyTogetherToCountAndPage()throws Exception {
  bookFixtures();var p=check(page(2,3," 数学 ","BOOK"),200);assertEquals(4,p.get("total").asInt());assertEquals(2,p.get("page").asInt());assertEquals(2,p.get("totalPages").asInt());assertEquals(1,p.get("items").size());assertEquals(id(3),p.at("/items/0/draftId").asText());assertEquals("数学",p.get("keyword").asText());
 }
 @Test void missingOrEmptyCategoryKeepsLegacyAllBehavior()throws Exception {
  bookFixtures();assertEquals(7,check(page(1,3,"",""),200).get("total").asInt());var p=check(get(PATH+"/page").header("Authorization","Bearer "+token),200);assertEquals("",p.get("category").asText());assertEquals(7,p.get("total").asInt());assertEquals(7,check(list(token),200).size());
 }
 @Test void invalidCategoryIs400AndNeverSilentlyIgnored()throws Exception {
  bookFixtures();for(String value:List.of("book","INVALID"," BOOK "," ","BOOK' OR 1=1 --","%"))assertEquals("INVALID_DRAFT_QUERY",check(page(1,3,"",value),400).get("code").asText());assertEquals(7,check(list(token),200).size());
 }
 @Test void authAndOwnerScopeStillApplyToFilteredPages()throws Exception {
  bookFixtures();check(get(PATH+"/page").param("category","BOOK"),401);var p=check(get(PATH+"/page").param("category","BOOK").param("userId",""+owner).header("Authorization","Bearer "+otherToken),200);assertEquals(0,p.get("total").asInt());assertEquals("BOOK",p.get("category").asText());
 }
 @Test void changingCategoryProducesOwnPageCountAndClamps()throws Exception {
  bookFixtures();var p=check(page(2,3,"数学","DIGITAL"),200);assertEquals(2,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals(1,p.get("totalPages").asInt());for(var r:p.get("items"))assertEquals("DIGITAL",r.at("/content/categoryCode").asText());
 }
 @Test void unknownCombinationIsEmptyPageOne()throws Exception {
  bookFixtures();var p=check(page(20,3,"英语","DIGITAL"),200);assertEquals(0,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals(1,p.get("totalPages").asInt());assertEquals(0,p.get("items").size());
 }
 @Test void literalWildcardStillWorksInsideCategory()throws Exception {
  create(0,"100%数学_教材!","BOOK");create(1,"100%数学_平板!","DIGITAL");create(2,"普通数学教材","BOOK");for(String word:List.of("%","_","!")){var p=check(page(1,3,word,"BOOK"),200);assertEquals(1,p.get("total").asInt());assertEquals(id(0),p.at("/items/0/draftId").asText());}
 }
 @Test void categoryChangeOnAnotherDeviceShrinksLastPage()throws Exception {
  bookFixtures();String json=body(2,"数学教材3").replace("\"categoryCode\":\"OTHER\"","\"categoryCode\":\"DIGITAL\"");check(save(id(3),2,"数学教材3").content(json),200);var p=check(page(2,3,"数学","BOOK"),200);assertEquals(3,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals(3,p.get("items").size());assertEquals(3,check(page(1,3,"数学","DIGITAL"),200).get("total").asInt());
 }
 @Test void filterReadsAreNoStoreAndDoNotRewriteDraftContent()throws Exception {
  bookFixtures();var before=jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id");var r=mvc.perform(page(1,3,"数学","BOOK")).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));
  var detail=check(get(PATH+"/"+id(4)).header("Authorization","Bearer "+token),200);assertEquals("DIGITAL",detail.at("/content/categoryCode").asText());assertEquals(before,jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
 }
}
