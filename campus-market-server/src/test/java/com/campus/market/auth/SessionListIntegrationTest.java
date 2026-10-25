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
class SessionListIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson116_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson116_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired SessionListService listing;@Autowired SessionService sessions;@Autowired SessionManagementService management;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
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
    MockHttpServletResponse page(String token,String... parameters)throws Exception {
        var request=get("/api/users/me/sessions/active");if(token!=null)request.header("Authorization",token);
        for(int i=0;i<parameters.length;i+=2)request.param(parameters[i],parameters[i+1]);
        return mvc.perform(request).andReturn().getResponse();
    }
    JsonNode listed(String token,String... parameters)throws Exception {var r=page(token,parameters);assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-cache",r.getHeader("Pragma"));return json.readTree(r.getContentAsByteArray());}
    @Test void anonymousCannotList(){assertThrows(LoginRequiredException.class,()->listing.list(null,Map.of()));}
    @Test void anonymousInvalidQueryStillReturns401()throws Exception {assertEquals(401,page(null,"userId","102","page","0").getStatus());}
    @Test void fabricatedAndMalformedTokensCannotList()throws Exception {for(String t:List.of("Bearer invalid","Basic x","Bearer "+"A".repeat(43)))assertEquals(401,page(t).getStatus());}
    @Test void revokedCurrentCannotList()throws Exception {sessions.logout(a);assertEquals(401,page(a).getStatus());assertEquals(2,listed(b).path("total").asLong());}
    @Test void expiredCurrentCannotList()throws Exception {stamp(a,"expires_at","CURRENT_TIMESTAMP-INTERVAL 1 SECOND");assertEquals(401,page(a).getStatus());}
    @Test void exactExpiryBoundaryIsNotActive()throws Exception {stamp(a,"expires_at","CURRENT_TIMESTAMP");assertEquals(401,page(a).getStatus());}
    @Test void defaultPageOnlyContainsCurrentAccountsSessions()throws Exception {var n=listed(a);assertEquals(101,n.path("userId").asLong());assertEquals(3,n.path("total").asLong());assertEquals(1,n.path("page").asInt());assertEquals(10,n.path("size").asInt());assertEquals(3,n.path("items").size());assertEquals(1,n.path("totalPages").asInt());}
    @Test void currentMarkerOccursExactlyOnceAcrossAllPages()throws Exception {int current=0;for(int p=1;p<=3;p++)for(var item:listed(a,"page",String.valueOf(p),"size","1").path("items"))if(item.path("current").asBoolean())current++;assertEquals(1,current);}
    @Test void otherUserCannotSeeFirstUsersHistory()throws Exception {var n=listed(other);assertEquals(102,n.path("userId").asLong());assertEquals(1,n.path("total").asLong());assertTrue(n.path("items").get(0).path("current").asBoolean());}
    @Test void revokedAndExpiredRowsAreExcluded()throws Exception {stamp(b,"expires_at","CURRENT_TIMESTAMP-INTERVAL 1 SECOND");sessions.logout(c);var n=listed(a);assertEquals(1,n.path("total").asLong());assertEquals(1,n.path("items").size());assertTrue(n.path("items").get(0).path("current").asBoolean());}
    @Test void exactMinimalResponseShapeExposesNoStableSessionIdentifier()throws Exception {var n=listed(a);assertEquals(Set.of("userId","items","total","page","size","totalPages","sampledAt"),new HashSet<>(n.propertyNames()));for(var item:n.path("items"))assertEquals(Set.of("current","createdAt","expiresAt"),new HashSet<>(item.propertyNames()));}
    @Test void tokensHashesPasswordAndOtherIdentityNeverAppear()throws Exception {String text=listed(a).toString();for(String secret:List.of(a.substring(7),b.substring(7),c.substring(7),other.substring(7),hash(a),hash(b),hash(other),"PRIVATE_HASH","token_hash","tokenHash","accessToken","session_bob"))assertFalse(text.contains(secret),secret);}
    @Test void clientIdentityAndUnknownParametersAreRejected()throws Exception {for(String name:List.of("userId","role","token","status","sort","sampledAt","Page")){var r=page(a,name,"102");assertEquals(400,r.getStatus());assertEquals("INVALID_SESSION_QUERY",json.readTree(r.getContentAsByteArray()).path("code").asString());assertEquals("no-store",r.getHeader("Cache-Control"));}}
    @Test void duplicatedQueryParametersAreRejected()throws Exception {for(String key:List.of("page","size"))assertEquals(400,page(a,key,"1",key,"2").getStatus());}
    @Test void pageRequiresCanonicalBoundedPositiveInteger()throws Exception {for(String value:List.of("","0","-1","01","+1"," 1","1 ","1.0","1e2","1000001","999999999999999999999","1 OR 1=1"))assertEquals(400,page(a,"page",value).getStatus(),value);}
    @Test void sizeRequiresCanonicalBoundedPositiveInteger()throws Exception {for(String value:List.of("","0","-1","01","+1"," 1","1.0","21","999999999999999999999"))assertEquals(400,page(a,"size",value).getStatus(),value);}
    @Test void maximumRequestedPageContractsToLastPage()throws Exception {var n=listed(a,"page","1000000","size","2");assertEquals(2,n.path("page").asInt());assertEquals(2,n.path("totalPages").asInt());assertEquals(1,n.path("items").size());}
    @Test void maximumPageSizeIsSupportedWithoutExtraRows()throws Exception {var n=listed(a,"size","20");assertEquals(20,n.path("size").asInt());assertEquals(3,n.path("items").size());}
    @Test void creationTimesAreDescendingAndSamplingTimeBoundsEveryEntry()throws Exception {stamp(a,"created_at","CURRENT_TIMESTAMP-INTERVAL 1 HOUR");stamp(b,"created_at","CURRENT_TIMESTAMP-INTERVAL 2 HOUR");stamp(c,"created_at","CURRENT_TIMESTAMP-INTERVAL 3 HOUR");var n=listed(a);assertTrue(n.path("items").get(0).path("current").asBoolean());var sample=java.time.LocalDateTime.parse(n.path("sampledAt").asString());java.time.LocalDateTime previous=null;for(var row:n.path("items")){var at=java.time.LocalDateTime.parse(row.path("createdAt").asString());assertFalse(at.isAfter(sample));assertTrue(java.time.LocalDateTime.parse(row.path("expiresAt").asString()).isAfter(sample));if(previous!=null)assertFalse(at.isAfter(previous));previous=at;}}
    @Test void equalCreationTimesHaveStableServerSideTieOrdering()throws Exception {for(String t:List.of(a,b,c))stamp(t,"created_at","CURRENT_TIMESTAMP-INTERVAL 1 HOUR");stamp(a,"expires_at","CURRENT_TIMESTAMP+INTERVAL 31 MINUTE");stamp(b,"expires_at","CURRENT_TIMESTAMP+INTERVAL 32 MINUTE");stamp(c,"expires_at","CURRENT_TIMESTAMP+INTERVAL 33 MINUTE");var expected=jdbc.queryForList("SELECT expires_at FROM login_sessions WHERE user_id=101 ORDER BY created_at DESC,token_hash DESC",Timestamp.class);var actual=new ArrayList<String>();for(int p=1;p<=3;p++)actual.add(listed(a,"page",String.valueOf(p),"size","1").path("items").get(0).path("expiresAt").asString());assertEquals(expected.stream().map(t->t.toLocalDateTime().toString()).toList(),actual);}
    @Test void repeatedListingDoesNotChangeRowsOrAccountProfiles()throws Exception {var rows=jdbc.queryForList("SELECT * FROM login_sessions ORDER BY token_hash");var users=jdbc.queryForList("SELECT * FROM users ORDER BY id");listed(a);listed(a,"page","2","size","1");assertEquals(rows,jdbc.queryForList("SELECT * FROM login_sessions ORDER BY token_hash"));assertEquals(users,jdbc.queryForList("SELECT * FROM users ORDER BY id"));}
    @Test void freshPageReflectsRevokeOthersWithoutChangingCurrent()throws Exception {assertEquals(3,listed(a).path("total").asLong());management.revokeOthers(a);var n=listed(a,"page","3","size","1");assertEquals(1,n.path("total").asLong());assertEquals(1,n.path("page").asInt());assertTrue(n.path("items").get(0).path("current").asBoolean());}
    @Test void newLoginAppearsOnNextIndependentRequest()throws Exception {assertEquals(3,listed(a).path("total").asLong());issue(101);assertEquals(4,listed(a).path("total").asLong());}
    @Test void readSnapshotDoesNotMixConcurrentNewLoginIntoExistingTransaction()throws Exception {var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(3,listing.list(a,Map.of()).total());try{assertNotNull(pool.submit(()->issue(101)).get(5,java.util.concurrent.TimeUnit.SECONDS));}catch(Exception e){throw new RuntimeException(e);}assertEquals(3,listing.list(a,Map.of()).total());});assertEquals(4,listed(a).path("total").asLong());}finally{pool.shutdownNow();}}
    @Test void databaseFailureIs500RatherThanFalseEmptyList()throws Exception {jdbc.execute("RENAME TABLE login_sessions TO login_sessions_private_unavailable");try{var r=page(a);assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));assertFalse(r.getContentAsString().contains("token_hash"));}finally{jdbc.execute("RENAME TABLE login_sessions_private_unavailable TO login_sessions");}}
    @Test void corruptFutureCreationTimeIsNotPublishedAsValidHistory()throws Exception {stamp(b,"created_at","CURRENT_TIMESTAMP+INTERVAL 1 HOUR");assertEquals(500,page(a).getStatus());}
    @Test void bearerCookieCannotReplaceAuthorizationHeader()throws Exception {var r=mvc.perform(get("/api/users/me/sessions/active").header("Cookie","Authorization="+a)).andReturn().getResponse();assertEquals(401,r.getStatus());}
}
