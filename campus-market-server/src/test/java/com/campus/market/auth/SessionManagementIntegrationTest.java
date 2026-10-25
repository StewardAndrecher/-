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
class SessionManagementIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson99_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson99_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired SessionService sessions;@Autowired SessionManagementService management;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired javax.sql.DataSource source;
    final JsonMapper json=JsonMapper.builder().build();String a,b,c,other;
    @BeforeEach void fixtures(){
        jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'session_alice','PRIVATE_HASH','甲'),(102,'session_bob','PRIVATE_OTHER_HASH','乙')");
        a=issue(101);b=issue(101);c=issue(101);other=issue(102);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,id==101?"session_alice":"session_bob",id==101?"甲":"乙")).accessToken();}
    String hash(String token){return sessions.tokenHash(token);}
    MockHttpServletResponse response(String token,boolean write)throws Exception {var request=write?post("/api/users/me/sessions/revoke-others"):get("/api/users/me/sessions");if(token!=null)request.header("Authorization",token);return mvc.perform(request).andReturn().getResponse();}
    JsonNode checked(String token,boolean write)throws Exception {var r=response(token,write);assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    boolean valid(String token){try{sessions.currentUser(token);return true;}catch(LoginRequiredException e){return false;}}
    void stamp(String token,String column,String expression){jdbc.update("UPDATE login_sessions SET "+column+"="+expression+" WHERE token_hash=?",hash(token));}
    @Test void anonymousSummaryIs401()throws Exception {assertEquals(401,response(null,false).getStatus());}
    @Test void anonymousRevocationIs401()throws Exception {assertEquals(401,response(null,true).getStatus());}
    @Test void malformedTokensCannotReadOrWrite()throws Exception {for(String token:List.of("Bearer invalid","Basic x","Bearer "+"A".repeat(43)))for(boolean write:List.of(false,true))assertEquals(401,response(token,write).getStatus());}
    @Test void revokedCurrentCannotReadOrWrite()throws Exception {sessions.logout(a);assertEquals(401,response(a,false).getStatus());assertEquals(401,response(a,true).getStatus());assertTrue(valid(b));}
    @Test void expiredCurrentCannotReadOrWrite()throws Exception {stamp(a,"expires_at","CURRENT_TIMESTAMP-INTERVAL 1 SECOND");assertEquals(401,response(a,false).getStatus());assertEquals(401,response(a,true).getStatus());}
    @Test void summaryCountsCurrentAndOwnOtherSessions()throws Exception {var n=checked(a,false);assertEquals(101,n.path("userId").asLong());assertEquals(3,n.path("activeSessions").asLong());assertEquals(2,n.path("otherActiveSessions").asLong());assertFalse(n.path("currentCreatedAt").asString().isBlank());}
    @Test void expiredAndRevokedOthersDoNotCount()throws Exception {stamp(b,"expires_at","CURRENT_TIMESTAMP-INTERVAL 1 SECOND");sessions.logout(c);var n=checked(a,false);assertEquals(1,n.path("activeSessions").asLong());assertEquals(0,n.path("otherActiveSessions").asLong());}
    @Test void anotherAccountSummaryOnlyCountsItsOwnSession()throws Exception {var n=checked(other,false);assertEquals(102,n.path("userId").asLong());assertEquals(1,n.path("activeSessions").asLong());}
    @Test void responsesUseExactMinimalShapes()throws Exception {assertEquals(Set.of("userId","activeSessions","otherActiveSessions","currentCreatedAt","currentExpiresAt","sampledAt"),new HashSet<>(checked(a,false).propertyNames()));assertEquals(Set.of("userId","revokedSessions","processedAt"),new HashSet<>(checked(a,true).propertyNames()));}
    @Test void responsesNeverExposeTokensOrHashes()throws Exception {var text=checked(a,false).toString()+checked(a,true);for(String secret:List.of(a.substring(7),b.substring(7),hash(a),hash(b),"PRIVATE_HASH","token_hash","accessToken"))assertFalse(text.contains(secret));}
    @Test void summaryIsReadOnly()throws Exception {var before=jdbc.queryForList("SELECT * FROM login_sessions ORDER BY token_hash");checked(a,false);checked(a,false);assertEquals(before,jdbc.queryForList("SELECT * FROM login_sessions ORDER BY token_hash"));}
    @Test void revokeOthersPreservesCurrentAndOtherAccount()throws Exception {var n=checked(a,true);assertEquals(2,n.path("revokedSessions").asLong());assertTrue(valid(a));assertFalse(valid(b));assertFalse(valid(c));assertTrue(valid(other));assertEquals(1,checked(a,false).path("activeSessions").asLong());}
    @Test void repeatRevocationReturnsZeroAndPreservesFirstTime()throws Exception {checked(a,true);var before=jdbc.queryForList("SELECT * FROM login_sessions ORDER BY token_hash");assertEquals(0,checked(a,true).path("revokedSessions").asLong());assertEquals(before,jdbc.queryForList("SELECT * FROM login_sessions ORDER BY token_hash"));}
    @Test void expiredAndAlreadyRevokedRowsAreNotModified()throws Exception {stamp(b,"expires_at","CURRENT_TIMESTAMP-INTERVAL 1 SECOND");stamp(c,"revoked_at","CURRENT_TIMESTAMP-INTERVAL 60 SECOND");var before=jdbc.queryForList("SELECT * FROM login_sessions WHERE token_hash IN (?,?) ORDER BY token_hash",hash(b),hash(c));assertEquals(0,checked(a,true).path("revokedSessions").asLong());assertEquals(before,jdbc.queryForList("SELECT * FROM login_sessions WHERE token_hash IN (?,?) ORDER BY token_hash",hash(b),hash(c)));}
    @Test void suppliedUserIdCannotChangeOwnershipScope()throws Exception {var r=mvc.perform(post("/api/users/me/sessions/revoke-others").header("Authorization",a).contentType("application/json").content("{\"userId\":102}")).andReturn().getResponse();assertEquals(200,r.getStatus());assertFalse(valid(b));assertTrue(valid(other));}
    @Test void accountProfilesAndPasswordHashesDoNotChange()throws Exception {var before=jdbc.queryForList("SELECT * FROM users ORDER BY id");checked(a,true);assertEquals(before,jdbc.queryForList("SELECT * FROM users ORDER BY id"));}
    @Test void wholeTransactionCanRollbackRevocations(){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{assertEquals(2,management.revokeOthers(a).revokedSessions());tx.setRollbackOnly();});assertTrue(valid(a));assertTrue(valid(b));assertTrue(valid(c));}
    @Test void writeFailureRollsBackAndDoesNotRevokeCurrent()throws Exception {jdbc.execute("CREATE TRIGGER private_revoke_failure BEFORE UPDATE ON login_sessions FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private test failure'");try{assertEquals(500,response(a,true).getStatus());assertTrue(valid(a));assertTrue(valid(b));assertTrue(valid(c));}finally{jdbc.execute("DROP TRIGGER private_revoke_failure");}}
    @Test void successfulNewLoginAfterRevocationRemainsValid()throws Exception {checked(a,true);String later=issue(101);assertTrue(valid(later));assertTrue(valid(a));assertEquals(2,checked(a,false).path("activeSessions").asLong());}
    @Test void sessionIssuedBeforeProcessingIsIncluded()throws Exception {String early=issue(101);assertEquals(3,checked(a,true).path("revokedSessions").asLong());assertFalse(valid(early));}
    @Test void concurrentRevokeCallsAllowOnlyOneCurrentSession()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{java.util.concurrent.Callable<Integer> one=()->{start.await();try{management.revokeOthers(a);return 200;}catch(LoginRequiredException e){return 401;}};java.util.concurrent.Callable<Integer> two=()->{start.await();try{management.revokeOthers(b);return 200;}catch(LoginRequiredException e){return 401;}};
            var x=pool.submit(one);var y=pool.submit(two);start.countDown();assertEquals(Set.of(200,401),Set.of(x.get(5,java.util.concurrent.TimeUnit.SECONDS),y.get(5,java.util.concurrent.TimeUnit.SECONDS)));assertEquals(1,(valid(a)?1:0)+(valid(b)?1:0));assertFalse(valid(c));assertTrue(valid(other));
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentIssuanceAndRevocationHaveConsistentOrdering()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{var rev=pool.submit(()->{start.await();return management.revokeOthers(a);});var login=pool.submit(()->{start.await();return issue(101);});start.countDown();long changed=rev.get(5,java.util.concurrent.TimeUnit.SECONDS).revokedSessions();String newToken=login.get(5,java.util.concurrent.TimeUnit.SECONDS);assertTrue(changed==2||changed==3);assertEquals(changed==2,valid(newToken));assertTrue(valid(a));assertFalse(valid(b));assertFalse(valid(c));
        }finally{pool.shutdownNow();}
    }
    @Test void sessionIssuanceWaitsForAccountLock()throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()){connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM users WHERE id=101 FOR UPDATE")){statement.executeQuery().close();}
            var future=pool.submit(()->issue(101));assertThrows(java.util.concurrent.TimeoutException.class,()->future.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));connection.commit();assertTrue(valid(future.get(5,java.util.concurrent.TimeUnit.SECONDS)));
        }finally{pool.shutdownNow();}
    }
    @Test void currentRevocationWhileWaitingIsRechecked()throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()){connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM users WHERE id=101 FOR UPDATE")){statement.executeQuery().close();}
            var future=pool.submit(()->{try{management.revokeOthers(a);return 200;}catch(LoginRequiredException e){return 401;}});assertThrows(java.util.concurrent.TimeoutException.class,()->future.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            try(var statement=connection.prepareStatement("UPDATE login_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE token_hash=?")){statement.setString(1,hash(a));statement.executeUpdate();}connection.commit();assertEquals(401,future.get(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(valid(b));assertTrue(valid(c));
        }finally{pool.shutdownNow();}
    }
    @Test void currentExpiryWhileWaitingDoesNotRevokeOthers()throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()){connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM users WHERE id=101 FOR UPDATE")){statement.executeQuery().close();}
            var future=pool.submit(()->{try{management.revokeOthers(a);return 200;}catch(LoginRequiredException e){return 401;}});assertThrows(java.util.concurrent.TimeoutException.class,()->future.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            try(var statement=connection.prepareStatement("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE token_hash=?")){statement.setString(1,hash(a));statement.executeUpdate();}connection.commit();assertEquals(401,future.get(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(valid(b));assertTrue(valid(c));
        }finally{pool.shutdownNow();}
    }
    @Test void databaseFailureIsNotFalseEmptyStatus()throws Exception {jdbc.execute("RENAME TABLE login_sessions TO login_sessions_private_unavailable");try{var r=response(a,false);assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));}finally{jdbc.execute("RENAME TABLE login_sessions_private_unavailable TO login_sessions");}}
    @Test void revocationDoesNotIssueOrDeleteSessionRows()throws Exception {long before=jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions",Long.class);checked(a,true);assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions",Long.class));}
    @Test void expiryBoundaryIsAlreadyInvalid()throws Exception {stamp(a,"expires_at","CURRENT_TIMESTAMP");assertEquals(401,response(a,false).getStatus());assertEquals(401,response(a,true).getStatus());assertTrue(valid(b));}
}
