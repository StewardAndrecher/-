package com.campus.market.user;

import org.junit.jupiter.api.*;
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

@SpringBootTest(properties={"spring.datasource.url=${LESSON28_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON28_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*")
class UserProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository repository;
    final JsonMapper json=JsonMapper.builder().build();
    String token,otherToken;long id,otherId;
    JsonNode check(MockHttpServletRequestBuilder req,int status)throws Exception {
        var r=mvc.perform(req).andReturn().getResponse();
        assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));
        return r.getContentAsByteArray().length==0?null:json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode login(String name)throws Exception {
        return check(post("/api/users/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password","Campus2026!"))),200);
    }
    @BeforeEach void fixtures()throws Exception {
        assertTrue(System.getenv("LESSON28_DB_URL").contains("/campus_lesson28_verify?"));
        for(String table:List.of("product_comments","product_favorites","products","product_images","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        for(String name:List.of("profile_01","profile_02"))check(post("/api/users/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname","OriginalName"))),201);
        var first=login("profile_01");token=first.get("accessToken").asText();id=first.get("user").get("id").asLong();
        var second=login("profile_02");otherToken=second.get("accessToken").asText();otherId=second.get("user").get("id").asLong();
    }
    MockHttpServletRequestBuilder change(String name,String expected,String actor) {
        var body=new HashMap<String,Object>();body.put("nickname",name);body.put("expectedNickname",expected);
        return put("/api/users/me/nickname").contentType("application/json")
                .header("Authorization","Bearer "+actor).content(json.writeValueAsString(body));
    }
    String nickname(long user){return jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,user);}
    @Test void currentProfileAndUpdatedResponseExposeOnlyPublicFields()throws Exception {
        assertEquals(3,check(get("/api/users/me").header("Authorization","Bearer "+token),200).size());
        var r=mvc.perform(change("新的昵称","OriginalName",token)).andReturn().getResponse();
        assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));
        var body=json.readTree(r.getContentAsString(StandardCharsets.UTF_8));assertEquals(3,body.size());
        assertEquals(id,body.get("id").asLong());assertEquals("profile_01",body.get("username").asText());
        assertFalse(body.has("passwordHash"));assertFalse(body.has("accessToken"));
    }
    @Test void updateRequiresAuthentication()throws Exception {
        check(put("/api/users/me/nickname").contentType("application/json").content("{\"nickname\":\"新昵称\",\"expectedNickname\":\"OriginalName\"}"),401);
        check(change("新昵称","OriginalName","bad"),401);assertEquals("OriginalName",nickname(id));
    }
    @Test void expiredAndRevokedTokensAreRejected()throws Exception {
        jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE user_id=?",id);
        check(change("过期修改","OriginalName",token),401);
        check(post("/api/users/logout").header("Authorization","Bearer "+otherToken),204);
        check(change("撤销修改","OriginalName",otherToken),401);assertEquals("OriginalName",nickname(otherId));
    }
    @Test void trimPreservesCredentialsAndCreationTime()throws Exception {
        var before=jdbc.queryForMap("SELECT id,username,password_hash,created_at FROM users WHERE id=?",id);
        assertEquals("校园同学",check(change("  校园同学  ","OriginalName",token),200).get("nickname").asText());
        assertEquals(before,jdbc.queryForMap("SELECT id,username,password_hash,created_at FROM users WHERE id=?",id));
    }
    @Test void sameNicknameDoesNotChangeRow()throws Exception {
        var before=jdbc.queryForMap("SELECT * FROM users WHERE id=?",id);check(change("OriginalName","OriginalName",token),200);
        assertEquals(before,jdbc.queryForMap("SELECT * FROM users WHERE id=?",id));
    }
    @Test void expectedNicknameIsRequired()throws Exception {
        check(change("新昵称",null,token),400);check(change("新昵称","",token),400);assertEquals("OriginalName",nickname(id));
    }
    @Test void staleDraftReturns409WithoutOverwriting()throws Exception {
        check(change("先更新","OriginalName",token),200);var r=mvc.perform(change("旧页面覆盖","OriginalName",token)).andReturn().getResponse();
        assertEquals(409,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));
        assertEquals("USER_PROFILE_CONFLICT",json.readTree(r.getContentAsString()).get("code").asText());assertEquals("先更新",nickname(id));
    }
    @Test void caseOnlyUpdateAndExpectedComparisonAreExact()throws Exception {
        check(change("新昵称","originalname",token),409);check(change("originalname","OriginalName",token),200);
        assertEquals("originalname",nickname(id));check(change("旧草稿","OriginalName",token),409);
    }
    @Test void unicodeLimitCountsCodePoints()throws Exception {
        String thirty="😀".repeat(30);assertEquals(thirty,check(change(thirty,"OriginalName",token),200).get("nickname").asText());
        check(change("😀".repeat(31),thirty,token),400);assertEquals(thirty,nickname(id));
    }
    @Test void invalidNicknamesAreRejected()throws Exception {
        for(String bad:Arrays.asList(null," ","a".repeat(31),"甲\n乙","甲\t乙","甲\u007f乙"))check(change(bad,"OriginalName",token),400);
        assertEquals("OriginalName",nickname(id));
    }
    @Test void spoofedIdentityAndCredentialsAreIgnored()throws Exception {
        var before=jdbc.queryForMap("SELECT username,password_hash FROM users WHERE id=?",id);
        var body=Map.of("nickname","自己的新昵称","expectedNickname","OriginalName","id",otherId,"userId",otherId,"username","spoof_name","password","Changed2026!");
        var result=check(put("/api/users/me/nickname").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)),200);
        assertEquals(id,result.get("id").asLong());assertEquals("OriginalName",nickname(otherId));
        assertEquals(before,jdbc.queryForMap("SELECT username,password_hash FROM users WHERE id=?",id));
        check(put("/api/users/"+otherId+"/nickname").header("Authorization","Bearer "+token).contentType("application/json").content("{}"),404);
    }
    @Test void existingSessionAndNewLoginReadLatestName()throws Exception {
        check(change("新的公开名字","OriginalName",token),200);
        assertEquals("新的公开名字",check(get("/api/users/me").header("Authorization","Bearer "+token),200).get("nickname").asText());
        assertEquals("新的公开名字",login("profile_01").get("user").get("nickname").asText());
    }
    @Test void joinedNamesRefreshWithoutChangingBusinessTables()throws Exception {
        long product=check(post("/api/products").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"title\":\"资料联动教材\",\"priceInCents\":1200}"),201).get("id").asLong();
        check(put("/api/favorites/"+product).header("Authorization","Bearer "+token),200);
        check(post("/api/products/"+product+"/comments").header("Authorization","Bearer "+token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("content","资料联动留言","requestId",UUID.randomUUID().toString()))),201);
        var snapshots=new HashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("products","product_images","product_favorites","product_comments"))snapshots.put(table,jdbc.queryForList("SELECT * FROM "+table));
        check(change("联动后的昵称","OriginalName",token),200);
        assertEquals("联动后的昵称",check(get("/api/products/"+product),200).get("seller").get("nickname").asText());
        assertEquals("联动后的昵称",check(get("/api/products/mine").header("Authorization","Bearer "+token),200).get("records").get(0).get("seller").get("nickname").asText());
        assertEquals("联动后的昵称",check(get("/api/favorites").header("Authorization","Bearer "+token),200).get("records").get(0).get("product").get("seller").get("nickname").asText());
        assertEquals("联动后的昵称",check(get("/api/products/"+product+"/comments"),200).get("records").get(0).get("author").get("nickname").asText());
        for(var e:snapshots.entrySet())assertEquals(e.getValue(),jdbc.queryForList("SELECT * FROM "+e.getKey()));
    }
    @Test void repositoryUsesBinaryComparison() {
        assertEquals(0,repository.updateNickname(id,"错误覆盖","originalname"));assertEquals("OriginalName",nickname(id));
        assertEquals(1,repository.updateNickname(id,"正确更新","OriginalName"));
    }
    @Test void concurrentEditsHaveOneWinner()throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Integer>>();for(String name:List.of("并发昵称甲","并发昵称乙"))futures.add(pool.submit(()->{
                start.await();return mvc.perform(change(name,"OriginalName",token)).andReturn().getResponse().getStatus();
            }));start.countDown();var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(10,TimeUnit.SECONDS));
            Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertTrue(List.of("并发昵称甲","并发昵称乙").contains(nickname(id)));
        } finally {pool.shutdownNow();}
    }
    @Test void registrationSharesValidation()throws Exception {
        check(post("/api/users/register").contentType("application/json").content(json.writeValueAsString(Map.of("username","profile_bad","password","Campus2026!","nickname","甲\n乙"))),400);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
    }
}
