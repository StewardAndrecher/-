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

@SpringBootTest(properties={"spring.datasource.url=${LESSON52_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON52_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson52_verify\\?.*")
class DraftBackupSortIntegrationTest {
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
  assertTrue(System.getenv("LESSON52_DB_URL").contains("/campus_lesson52_verify?"));
  jdbc.update("DELETE FROM product_draft_backups");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String n:List.of("owner_52","other_52")) check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!","nickname",n))),201);
  token=login("owner_52");otherToken=login("other_52");owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_52'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_52'",Long.class);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }



 String id(int i) { return new UUID(0,i).toString(); }
 final String[] titles={"D数学教材","A数学教材","C数学教材","B数学教材","A数码平板","E数学教材","A数学教材"};
 void seed()throws Exception {
  int[] days={4,1,3,2,7,5,6};for(int i=0;i<titles.length;i++){
   String json=body(1,titles[i]).replace("\"categoryCode\":\"OTHER\"","\"categoryCode\":\""+(i==4?"DIGITAL":"BOOK")+"\"");check(save(id(i),1,titles[i]).content(json),201);
   jdbc.update("UPDATE product_draft_backups SET updated_at=? WHERE user_id=? AND draft_id=?","2026-01-0"+days[i]+" 00:00:00",owner,id(i));
  }
 }
 MockHttpServletRequestBuilder page(int n,int size,String word,String category,String sort) { return get(PATH+"/page").param("page",""+n).param("size",""+size).param("keyword",word).param("category",category).param("sort",sort).header("Authorization","Bearer "+token); }
 List<String> ids(JsonNode p) { var ids=new ArrayList<String>();for(var row:p.get("items"))ids.add(row.get("draftId").asText());return ids; }
 @Test void missingSortDefaultsToUpdatedDesc()throws Exception {
  seed();var p=check(get(PATH+"/page").param("size","10").header("Authorization","Bearer "+token),200);assertEquals("UPDATED_DESC",p.get("sort").asText());assertEquals(ids(p),ids(check(page(1,10,"","","UPDATED_DESC"),200)));assertEquals(List.of(id(4),id(6),id(5),id(0),id(2),id(3),id(1)),ids(p));
 }
 @Test void earliestSortOrdersAscendingByUpdateTime()throws Exception { seed();assertEquals(List.of(id(1),id(3),id(2),id(0),id(5),id(6),id(4)),ids(check(page(1,10,"","","UPDATED_ASC"),200))); }
 @Test void titleSortUsesBinaryCharacterOrderAndIdTieBreak()throws Exception {
  seed();var expected=new ArrayList<Integer>();for(int i=0;i<titles.length;i++)expected.add(i);expected.sort(Comparator.comparing((Integer i)->titles[i]).thenComparing(i->id(i)));assertEquals(expected.stream().map(this::id).toList(),ids(check(page(1,10,"","","TITLE_ASC"),200)));
 }
 @Test void equalTimesAndTitlesHaveStableUniquePagesForEverySort()throws Exception {
  seed();jdbc.update("UPDATE product_draft_backups SET updated_at='2026-01-01 00:00:00',title='相同标题' WHERE user_id=?",owner);
  for(String sort:List.of("UPDATED_DESC","UPDATED_ASC","TITLE_ASC")){var seen=new ArrayList<String>();for(int n=1;n<=3;n++)seen.addAll(ids(check(page(n,3,"","",sort),200)));assertEquals(java.util.stream.IntStream.range(0,7).mapToObj(this::id).toList(),seen);assertEquals(7,new HashSet<>(seen).size());}
 }
 @Test void sortComposesWithCategoryKeywordAndPagination()throws Exception {
  seed();var p=check(page(2,3,"数学","BOOK","UPDATED_DESC"),200);assertEquals(6,p.get("total").asInt());assertEquals(2,p.get("totalPages").asInt());assertEquals(List.of(id(2),id(3),id(1)),ids(p));assertEquals("BOOK",p.get("category").asText());assertEquals("数学",p.get("keyword").asText());assertEquals(List.of(id(2),id(0),id(5)),ids(check(page(2,3,"数学","BOOK","TITLE_ASC"),200)));
 }
 @Test void invalidSortAndSqlFragmentsAreRejectedWithoutWrites()throws Exception {
  seed();var before=jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id");for(String sort:List.of("updated_desc","PRICE_ASC"," UPDATED_ASC ","title; DROP TABLE users","title DESC","UNKNOWN"))assertEquals("INVALID_DRAFT_QUERY",check(page(1,3,"","",sort),400).get("code").asText());assertEquals(before,jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id"));
 }
 @Test void authOwnerAndNoStoreRemainEnforced()throws Exception {
  seed();check(get(PATH+"/page").param("sort","TITLE_ASC"),401);var r=mvc.perform(get(PATH+"/page").param("sort","TITLE_ASC").param("userId",""+owner).header("Authorization","Bearer "+otherToken)).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));var p=mapper.readTree(r.getContentAsString(StandardCharsets.UTF_8));assertEquals(0,p.get("total").asInt());assertEquals("TITLE_ASC",p.get("sort").asText());
 }
 @Test void backupUpdateChangesRecentOrderButNotTitleOrderOrCount()throws Exception {
  seed();var before=ids(check(page(1,10,"","","TITLE_ASC"),200));String json=body(2,titles[1]).replace("\"categoryCode\":\"OTHER\"","\"categoryCode\":\"BOOK\"");check(save(id(1),2,titles[1]).content(json),200);var p=check(page(1,3,"","","UPDATED_DESC"),200);assertEquals(id(1),p.at("/items/0/draftId").asText());assertEquals(7,p.get("total").asInt());assertEquals(before,ids(check(page(1,10,"","","TITLE_ASC"),200)));assertEquals(7,check(list(token),200).size());
 }
 @Test void emptyResultsAndPageClampEchoRequestedSort()throws Exception {
  seed();for(String sort:List.of("UPDATED_DESC","UPDATED_ASC","TITLE_ASC")){var p=check(page(99,3,"不存在","BOOK",sort),200);assertEquals(0,p.get("total").asInt());assertEquals(1,p.get("page").asInt());assertEquals(1,p.get("totalPages").asInt());assertEquals(sort,p.get("sort").asText());}
 }
 @Test void sortParsingAndAllReadModesAreReadOnly()throws Exception {
  assertEquals(DraftBackupSort.UPDATED_DESC,DraftBackupSort.parse(null));assertEquals(DraftBackupSort.UPDATED_DESC,DraftBackupSort.parse(""));assertThrows(DraftBackupException.class,()->DraftBackupSort.parse("invalid"));seed();var before=jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id");for(String sort:List.of("UPDATED_DESC","UPDATED_ASC","TITLE_ASC"))check(page(1,3,"","",sort),200);assertEquals(before,jdbc.queryForList("SELECT * FROM product_draft_backups ORDER BY user_id,draft_id"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
 }
}
