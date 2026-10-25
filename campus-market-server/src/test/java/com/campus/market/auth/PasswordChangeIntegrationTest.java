package com.campus.market.auth;
import com.campus.market.auth.SessionService;
import com.campus.market.user.UserResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.notifications.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class PasswordChangeIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson100_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson100_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired SessionService sessions; @Autowired PasswordChangeService passwords;
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource source;
    final JsonMapper json=JsonMapper.builder().build();
    static final String OLD="Campus2026!", NEXT="Campus2027!";
    String a,b,other,oldHash,bobHash;
    @BeforeEach void fixtures(){
        jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
        oldHash=encoder.encode(OLD);bobHash=oldHash;
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'password_alice',?,'甲'),(102,'password_bob',?,'乙')",oldHash,bobHash);
        a=issue(101);b=issue(101);other=issue(102);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,id==101?"password_alice":"password_bob",id==101?"甲":"乙")).accessToken();}
    String hash(long id){return jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id);}
    boolean valid(String token){try{sessions.currentUser(token);return true;}catch(LoginRequiredException e){return false;}}
    MockHttpServletResponse change(String token,String old,String next)throws Exception {
        var r=post("/api/users/me/password").contentType("application/json").content(json.writeValueAsString(Map.of("currentPassword",old,"newPassword",next)));
        if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();
    }
    MockHttpServletResponse login(String password)throws Exception {return mvc.perform(post("/api/users/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","password_alice","password",password)))).andReturn().getResponse();}
    void unchanged(){assertEquals(oldHash,hash(101));assertTrue(valid(a));assertTrue(valid(b));assertTrue(valid(other));}
    @Test void anonymousCannotChangePassword()throws Exception {assertEquals(401,change(null,OLD,NEXT).getStatus());unchanged();}
    @Test void forgedTokenCannotChangePassword()throws Exception {assertEquals(401,change("Bearer "+"A".repeat(43),OLD,NEXT).getStatus());unchanged();}
    @Test void revokedCurrentIsRejected()throws Exception {sessions.logout(a);assertEquals(401,change(a,OLD,NEXT).getStatus());assertEquals(oldHash,hash(101));assertTrue(valid(b));}
    @Test void expiredCurrentIsRejected()throws Exception {jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP WHERE token_hash=?",sessions.tokenHash(a));assertEquals(401,change(a,OLD,NEXT).getStatus());assertEquals(oldHash,hash(101));assertTrue(valid(b));}
    @Test void wrongCurrentReturns403AndPreservesSessions()throws Exception {var r=change(a,"Wrong2026!",NEXT);assertEquals(403,r.getStatus());assertTrue(r.getContentAsString().contains("CURRENT_PASSWORD_MISMATCH"));assertEquals("no-store",r.getHeader("Cache-Control"));unchanged();}
    @Test void successfulChangeHashesPasswordAndRevokesAllOwnSessions()throws Exception {var r=change(a,OLD,NEXT);assertEquals(200,r.getStatus());assertNotEquals(oldHash,hash(101));assertTrue(encoder.matches(NEXT,hash(101)));assertFalse(valid(a));assertFalse(valid(b));assertTrue(valid(other));assertEquals(2,json.readTree(r.getContentAsByteArray()).path("revokedSessions").asInt());}
    @Test void oldPasswordFailsAndNewPasswordCanLogin()throws Exception {assertEquals(200,change(a,OLD,NEXT).getStatus());assertEquals(401,login(OLD).getStatus());var r=login(NEXT);assertEquals(200,r.getStatus());assertTrue(valid("Bearer "+json.readTree(r.getContentAsByteArray()).path("accessToken").asString()));}
    @Test void successDoesNotChangeOtherAccountOrProfiles()throws Exception {var profiles=jdbc.queryForList("SELECT id,username,nickname FROM users ORDER BY id");change(a,OLD,NEXT);assertEquals(profiles,jdbc.queryForList("SELECT id,username,nickname FROM users ORDER BY id"));assertEquals(bobHash,hash(102));assertTrue(valid(other));}
    @Test void samePasswordIsRejected()throws Exception {assertEquals(400,change(a,OLD,OLD).getStatus());unchanged();}
    @Test void invalidNewLengthsAreRejected()throws Exception {for(String next:List.of("short","x".repeat(65)," ".repeat(8),"中".repeat(25)))assertEquals(400,change(a,OLD,next).getStatus());unchanged();}
    @Test void invalidCurrentLengthsAreRejected()throws Exception {for(String old:List.of("","x".repeat(65),"中".repeat(25)))assertEquals(400,change(a,old,NEXT).getStatus());unchanged();}
    @Test void exactUtf8LimitAndSupplementaryCharactersWork()throws Exception {String next="😀".repeat(18);assertEquals(200,change(a,OLD,next).getStatus());assertTrue(encoder.matches(next,hash(101)));}
    @Test void sixtyFourAsciiCharactersWork()throws Exception {String next="x".repeat(64);assertEquals(200,change(a,OLD,next).getStatus());assertTrue(encoder.matches(next,hash(101)));}
    @Test void missingFieldsAndNullBodyAre400()throws Exception {for(String body:List.of("{}","null","{\"currentPassword\":null,\"newPassword\":\"Campus2027!\"}","{\"currentPassword\":\"Campus2026!\"}")){assertEquals(400,mvc.perform(post("/api/users/me/password").header("Authorization",a).contentType("application/json").content(body)).andReturn().getResponse().getStatus());}unchanged();}
    @Test void malformedJsonIs400()throws Exception {assertEquals(400,mvc.perform(post("/api/users/me/password").header("Authorization",a).contentType("application/json").content("{bad")).andReturn().getResponse().getStatus());unchanged();}
    @Test void passwordsKeepLeadingAndTrailingSpaces()throws Exception {String spaced=" Campus2027! ";assertEquals(200,change(a,OLD,spaced).getStatus());assertEquals(401,login(spaced.trim()).getStatus());assertEquals(200,login(spaced).getStatus());}
    @Test void responseHasMinimalShapeAndNoCredentials()throws Exception {var r=change(a,OLD,NEXT);assertEquals("no-store",r.getHeader("Cache-Control"));var n=json.readTree(r.getContentAsByteArray());assertEquals(Set.of("userId","revokedSessions","changedAt"),new HashSet<>(n.propertyNames()));assertEquals(101,n.path("userId").asLong());assertFalse(n.path("changedAt").asString().isEmpty());for(String secret:List.of(OLD,NEXT,oldHash,hash(101),a.substring(7)))assertFalse(r.getContentAsString().contains(secret));}
    @Test void requestToStringRedactsPasswords(){var text=new PasswordChangeRequest(OLD,NEXT).toString();assertFalse(text.contains(OLD));assertFalse(text.contains(NEXT));assertTrue(text.contains("REDACTED"));}
    @Test void suppliedUserIdCannotChangeOtherAccount()throws Exception {var r=mvc.perform(post("/api/users/me/password").header("Authorization",a).contentType("application/json").content(json.writeValueAsString(Map.of("userId",102,"currentPassword",OLD,"newPassword",NEXT)))).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals(bobHash,hash(102));assertTrue(valid(other));assertTrue(encoder.matches(NEXT,hash(101)));}
    @Test void transactionRollbackRestoresHashAndAllSessions(){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{passwords.change(a,new PasswordChangeRequest(OLD,NEXT),"127.0.0.1");tx.setRollbackOnly();});unchanged();}
    @Test void sessionUpdateFailureRollsBackPasswordUpdate()throws Exception {jdbc.execute("CREATE TRIGGER private_change_failure BEFORE UPDATE ON login_sessions FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private test failure'");try{assertEquals(500,change(a,OLD,NEXT).getStatus());unchanged();}finally{jdbc.execute("DROP TRIGGER private_change_failure");}}
    @Test void passwordUpdateFailurePreservesAllSessions()throws Exception {jdbc.execute("CREATE TRIGGER private_password_failure BEFORE UPDATE ON users FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private test failure'");try{assertEquals(500,change(a,OLD,NEXT).getStatus());unchanged();}finally{jdbc.execute("DROP TRIGGER private_password_failure");}}
    @Test void replayWithOldTokenIs401()throws Exception {change(a,OLD,NEXT);assertEquals(401,change(a,OLD,"Campus2028!").getStatus());assertTrue(encoder.matches(NEXT,hash(101)));}
    @Test void onlyCurrentSessionCanBeChanged()throws Exception {sessions.logout(b);var r=change(a,OLD,NEXT);assertEquals(200,r.getStatus());assertEquals(1,json.readTree(r.getContentAsByteArray()).path("revokedSessions").asInt());assertFalse(valid(a));}
    @Test void alreadyInvalidOtherSessionsKeepTheirTimestamps()throws Exception {sessions.logout(b);var before=jdbc.queryForList("SELECT * FROM login_sessions WHERE token_hash=?",sessions.tokenHash(b));assertEquals(200,change(a,OLD,NEXT).getStatus());assertEquals(before,jdbc.queryForList("SELECT * FROM login_sessions WHERE token_hash=?",sessions.tokenHash(b)));}
    @Test void changeDoesNotInsertOrDeleteRows()throws Exception {long before=jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions",Long.class);change(a,OLD,NEXT);assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions",Long.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class));}
    @Test void currentRevokedWhileWaitingForLockIsRechecked()throws Exception {waitAndInvalidate(false);}
    @Test void currentExpiredWhileWaitingForLockIsRechecked()throws Exception {waitAndInvalidate(true);}
    void waitAndInvalidate(boolean expire)throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()){connection.setAutoCommit(false);connection.createStatement().executeQuery("SELECT id FROM users WHERE id=101 FOR UPDATE").close();var f=pool.submit(()->change(a,OLD,NEXT).getStatus());assertThrows(java.util.concurrent.TimeoutException.class,()->f.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            try(var s=connection.prepareStatement("UPDATE login_sessions SET "+(expire?"expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND":"revoked_at=CURRENT_TIMESTAMP")+" WHERE token_hash=?")){s.setString(1,sessions.tokenHash(a));s.executeUpdate();}connection.commit();assertEquals(401,f.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(oldHash,hash(101));assertTrue(valid(b));
        }finally{pool.shutdownNow();}
    }
    @Test void simultaneousChangesAllowOnlyOne()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try{var x=pool.submit(()->{gate.await();return change(a,OLD,NEXT).getStatus();});var y=pool.submit(()->{gate.await();return change(b,OLD,"Campus2028!").getStatus();});gate.countDown();assertEquals(Set.of(200,401),Set.of(x.get(7,java.util.concurrent.TimeUnit.SECONDS),y.get(7,java.util.concurrent.TimeUnit.SECONDS)));assertFalse(valid(a));assertFalse(valid(b));assertTrue(valid(other));}finally{pool.shutdownNow();}
    }
    @Test void concurrentOldPasswordLoginNeverSurvivesSuccessfulChange()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try{var x=pool.submit(()->{gate.await();return login(OLD);});var y=pool.submit(()->{gate.await();return change(a,OLD,NEXT);});gate.countDown();var r=x.get(7,java.util.concurrent.TimeUnit.SECONDS);assertEquals(200,y.get(7,java.util.concurrent.TimeUnit.SECONDS).getStatus());assertTrue(r.getStatus()==200||r.getStatus()==401);if(r.getStatus()==200)assertFalse(valid("Bearer "+json.readTree(r.getContentAsByteArray()).path("accessToken").asString()));assertEquals(401,login(OLD).getStatus());}finally{pool.shutdownNow();}
    }
    @Test void waitingLoginReadsCommittedReplacementHash()throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()){connection.setAutoCommit(false);connection.createStatement().executeQuery("SELECT id FROM users WHERE id=101 FOR UPDATE").close();var f=pool.submit(()->login(OLD).getStatus());assertThrows(java.util.concurrent.TimeoutException.class,()->f.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));try(var s=connection.prepareStatement("UPDATE users SET password_hash=? WHERE id=101")){s.setString(1,encoder.encode(NEXT));s.executeUpdate();}connection.commit();assertEquals(401,f.get(5,java.util.concurrent.TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    @Test void unpairedSurrogateNewPasswordIsRejected()throws Exception {assertEquals(400,mvc.perform(post("/api/users/me/password").header("Authorization",a).contentType("application/json").content("{\"currentPassword\":\"Campus2026!\",\"newPassword\":\"Campus2027!\\ud800\"}")).andReturn().getResponse().getStatus());unchanged();}
}
