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

@SpringBootTest(properties={"spring.datasource.url=${LESSON50_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON50_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson50_verify\\?.*")
class DraftBackupQueryIntegrationTest {
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
  assertTrue(System.getenv("LESSON50_DB_URL").contains("/campus_lesson50_verify?"));
  jdbc.update("DELETE FROM product_draft_backups");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String n:List.of("owner_50","other_50")) check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!","nickname",n))),201);
  token=login("owner_50");otherToken=login("other_50");owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_50'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_50'",Long.class);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }

 String id(int i) { return new UUID(0,i).toString(); }
 void seed(int n)throws Exception { for(int i=0;i<n;i++) check(save(id(i),1,"云端台灯"+i),201);jdbc.update("UPDATE product_draft_backups SET updated_at='2026-01-01 00:00:00' WHERE user_id=?",owner); }
 MockHttpServletRequestBuilder page(int page,int size,String word) { return get(PATH+"/page").param("page",""+page).param("size",""+size).param("keyword",word).header("Authorization","Bearer "+token); }
 @Test void stablePagesHaveNoDuplicatesAtEqualTimes()throws Exception {
  seed(7);var a=check(page(1,3,""),200);var b=check(page(2,3,""),200);var c=check(page(3,3,""),200);
  assertEquals(7,a.get("total").asLong());assertEquals(3,a.get("totalPages").asInt());assertEquals(3,a.get("items").size());assertEquals(1,c.get("items").size());
  var found=new HashSet<String>();for(var p:List.of(a,b,c))for(var row:p.get("items"))assertTrue(found.add(row.get("draftId").asText()));assertEquals(7,found.size());assertEquals(id(0),a.at("/items/0/draftId").asText());assertEquals(id(3),b.at("/items/0/draftId").asText());
 }
 @Test void trimAndTitleOnlySearchShareCountCondition()throws Exception {
  check(save(id(1),1,"数学教材"),201);check(save(id(2),1,"数学笔记"),201);check(save(id(3),1,"台灯"),201);
  var p=check(page(1,1,"  数学  "),200);assertEquals("数学",p.get("keyword").asText());assertEquals(2,p.get("total").asInt());assertEquals(2,p.get("totalPages").asInt());assertEquals(0,check(page(1,3,"未完成的输入"),200).get("total").asInt());
 }
 @Test void wildcardEscapeAndQuotesAreLiteral()throws Exception {
  String[] titles={"100%教材","item_台灯","bang!书","x\\y电线","' OR 1=1 --","普通商品"};for(int i=0;i<titles.length;i++)check(save(id(i),1,titles[i]),201);
  for(int i=0;i<titles.length;i++){var p=check(page(1,3,titles[i]),200);assertEquals(1,p.get("total").asInt());assertEquals(titles[i],p.at("/items/0/content/title").asText());}
  assertEquals(1,check(page(1,3,"%"),200).get("total").asInt());assertEquals(1,check(page(1,3,"_"),200).get("total").asInt());assertEquals(1,check(page(1,3,"!"),200).get("total").asInt());
 }
 @Test void emptyAndOutOfRangePagesClampSafely()throws Exception {
  var empty=check(page(999999,3,""),200);assertEquals(1,empty.get("page").asInt());assertEquals(1,empty.get("totalPages").asInt());assertEquals(0,empty.get("items").size());
  seed(4);var last=check(page(1000000,3,""),200);assertEquals(2,last.get("page").asInt());assertEquals(1,last.get("items").size());var none=check(page(20,3,"找不到"),200);assertEquals(1,none.get("page").asInt());assertEquals(0,none.get("total").asInt());
 }
 @Test void invalidQueryIs400()throws Exception {
  for(int n:new int[]{0,-1,1000001})check(page(n,3,""),400);for(int n:new int[]{0,-1,11})check(page(1,n,""),400);check(page(1,3,"x".repeat(51)),400);
  check(get(PATH+"/page").param("page","abc").header("Authorization","Bearer "+token),400);check(get(PATH+"/page").param("size","1.5").header("Authorization","Bearer "+token),400);
 }
 @Test void ownershipAndAuthApplyToPageAndDetail()throws Exception {
  seed(4);check(get(PATH+"/page"),401);check(get(PATH+"/"+id(0)),401);
  var p=check(get(PATH+"/page").param("userId",""+owner).header("Authorization","Bearer "+otherToken),200);assertEquals(0,p.get("total").asInt());
  check(get(PATH+"/"+id(0)).header("Authorization","Bearer "+otherToken),404);check(get(PATH+"/not-an-id").header("Authorization","Bearer "+token),400);
 }
 @Test void deletingLastItemOnLastPageReturnsPreviousPage()throws Exception {
  seed(4);var p=check(page(2,3,""),200);String last=p.at("/items/0/draftId").asText();check(delete(PATH+"/"+last).param("version","0").header("Authorization","Bearer "+token),204);
  p=check(page(2,3,""),200);assertEquals(1,p.get("page").asInt());assertEquals(3,p.get("total").asInt());assertEquals(3,p.get("items").size());
 }
 @Test void detailIsIndependentOfSearchAndPagination()throws Exception {
  seed(7);assertEquals(0,check(page(1,3,"不匹配"),200).get("total").asInt());var row=check(get(PATH+"/"+id(6)).param("keyword","不匹配").header("Authorization","Bearer "+token),200);assertEquals(id(6),row.get("draftId").asText());assertEquals(0,row.get("version").asInt());
  check(get(PATH+"/"+id(99)).header("Authorization","Bearer "+token),404);
 }
 @Test void readsAreNoStoreAndLegacyListStaysCompatible()throws Exception {
  seed(5);var before=jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id");
  for(var req:List.of(page(1,3,"台灯"),get(PATH+"/"+id(0)).header("Authorization","Bearer "+token))){var r=mvc.perform(req).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));}
  assertEquals(5,check(list(token),200).size());assertEquals(before,jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
 }
 @Test void updatingBackupMovesItAheadOfOlderPageItems()throws Exception {
  seed(4);check(save(id(3),2,"更新后的教材"),200);var p=check(page(1,3,""),200);assertEquals(id(3),p.at("/items/0/draftId").asText());assertEquals(1,p.at("/items/0/version").asInt());assertEquals(1,check(page(1,3,"教材"),200).get("total").asInt());
 }
}
