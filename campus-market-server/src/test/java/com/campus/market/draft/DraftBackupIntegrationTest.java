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

@SpringBootTest(properties={"spring.datasource.url=${LESSON49_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON49_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson49_verify\\?.*")
class DraftBackupIntegrationTest {
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
  assertTrue(System.getenv("LESSON49_DB_URL").contains("/campus_lesson49_verify?"));
  jdbc.update("DELETE FROM product_draft_backups");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  for(String n:List.of("owner_49","other_49")) check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!","nickname",n))),201);
  token=login("owner_49");otherToken=login("other_49");owner=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_49'",Long.class);other=jdbc.queryForObject("SELECT id FROM users WHERE username='other_49'",Long.class);
 }
 String login(String n)throws Exception { return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",n,"password","Campus2026!"))),200).get("accessToken").asText(); }
 @Test void createListAndPartialPriceArePreserved()throws Exception {
  assertEquals(0,check(list(token),200).size());var a=check(save(ID,1,"  草稿标题  "),201);assertEquals("12.",a.at("/content/priceText").asText());assertEquals("  草稿标题  ",a.at("/content/title").asText());assertEquals(0,a.get("version").asLong());assertEquals(1,check(list(token),200).size());
 }
 @Test void replayHasSameVersionAndTimestamp()throws Exception { var a=check(save(ID,1,"台灯"),201);assertEquals(a,check(save(ID,1,"台灯"),200));assertEquals(1,check(list(token),200).size()); }
 @Test void newerRevisionUpdatesAndOlderOrChangedSameRevisionConflicts()throws Exception {
  check(save(ID,2,"第一版"),201);var a=check(save(ID,3,"第二版"),200);assertEquals(1,a.get("version").asLong());check(save(ID,2,"旧输入"),409);check(save(ID,3,"同版不同"),409);assertEquals(a,check(list(token),200).get(0));
 }
 @Test void accountsArePrivateAndSameIdIsIndependent()throws Exception {
  check(save(ID,1,"本人"),201);assertEquals(0,check(list(otherToken).param("userId",""+owner),200).size());
  check(delete(PATH+"/"+ID).param("version","0").header("Authorization","Bearer "+otherToken),404);
  check(put(PATH+"/"+ID).header("Authorization","Bearer "+otherToken).contentType("application/json").content(body(1,"他人")),201);assertEquals("本人",check(list(token),200).get(0).at("/content/title").asText());
 }
 @Test void authenticationRequiredForEveryOperation()throws Exception {
  check(get(PATH),401);check(put(PATH+"/"+ID).contentType("application/json").content(body(1,"测试")),401);check(delete(PATH+"/"+ID).param("version","0"),401);
  jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");check(list(token),401);
 }
 @Test void tenLimitAllowsUpdatesAndReplay()throws Exception {
  for(int i=0;i<10;i++) check(save(new UUID(0,i).toString(),1,"记录"+i),201);
  assertEquals("DRAFT_LIMIT",check(save(ID,1,"第11份"),409).get("code").asText());check(save(new UUID(0,0).toString(),2,"更新"),200);check(save(new UUID(0,0).toString(),2,"更新"),200);assertEquals(10,check(list(token),200).size());
 }
 @Test void concurrentCreatesCannotExceedLimit()throws Exception {
  for(int i=0;i<9;i++) check(save(new UUID(0,i).toString(),1,"已有"),201);
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {
   var a=pool.submit(()->{start.await();return mvc.perform(save(new UUID(0,20).toString(),1,"并发甲")).andReturn().getResponse().getStatus();});
   var b=pool.submit(()->{start.await();return mvc.perform(save(new UUID(0,21).toString(),1,"并发乙")).andReturn().getResponse().getStatus();});start.countDown();
   var statuses=new HashSet<>(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));assertEquals(Set.of(201,409),statuses);assertEquals(10,check(list(token),200).size());
  } finally { pool.shutdownNow(); }
 }
 @Test void deleteRequiresCurrentVersionAndKeepsBusinessRows()throws Exception {
  check(save(ID,1,"草稿"),201);check(save(ID,2,"新草稿"),200);check(delete(PATH+"/"+ID).param("version","0").header("Authorization","Bearer "+token),409);assertEquals(1,check(list(token),200).size());
  check(delete(PATH+"/"+ID).param("version","1").header("Authorization","Bearer "+token),204);assertEquals(0,check(list(token),200).size());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product_publications",Integer.class));
 }
 @Test void invalidInputIsRejectedWithoutTruncation()throws Exception {
  check(save("invalid",1,"测试"),400);check(save(ID,0,"测试"),400);check(save(ID,9007199254740992L,"测试"),400);check(save(ID,1,"x".repeat(51)),400);
  for(String json:List.of("{}","{\"sourceRevision\":1}",body(1,"测试").replace("12.","abc"),body(1,"测试").replace("OTHER","UNKNOWN"),mapper.writeValueAsString(Map.of("sourceRevision",1,"content",Map.of("title","","description","","priceText","","coverImageId","","categoryCode","OTHER"))))) check(put(PATH+"/"+ID).header("Authorization","Bearer "+token).contentType("application/json").content(json),400);
  assertEquals(0,check(list(token),200).size());
 }
 @Test void missingAndForeignCoverCannotBeBackedUp()throws Exception {
  String image="00000000-0000-4000-8000-000000000099";String json=body(1,"封面").replace("\"coverImageId\":\"\"","\"coverImageId\":\""+image+"\"");
  check(put(PATH+"/"+ID).header("Authorization","Bearer "+token).contentType("application/json").content(json),400);
  jdbc.update("INSERT INTO product_images(id,owner_id,width,height) VALUES(?,?,1,1)",image,other);check(put(PATH+"/"+ID).header("Authorization","Bearer "+token).contentType("application/json").content(json),403);
  jdbc.update("UPDATE product_images SET owner_id=? WHERE id=?",owner,image);check(put(PATH+"/"+ID).header("Authorization","Bearer "+token).contentType("application/json").content(json),201);
 }
 @Test void noStoreAndWhitelistedFields()throws Exception {
  check(save(ID,1,"' OR 1=1 --"),201);var r=mvc.perform(list(token)).andReturn().getResponse();assertEquals("no-store",r.getHeader("Cache-Control"));var row=mapper.readTree(r.getContentAsString(StandardCharsets.UTF_8)).get(0);assertEquals(5,row.size());assertFalse(row.has("userId"));assertEquals("' OR 1=1 --",row.at("/content/title").asText());
 }
}
