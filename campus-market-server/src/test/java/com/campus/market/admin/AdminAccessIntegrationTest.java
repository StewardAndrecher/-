package com.campus.market.admin;

import com.campus.market.auth.SessionService;
import com.campus.market.user.UserResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@Import(AdminAccessIntegrationTest.FutureAdminController.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class AdminAccessIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson89_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson89_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    final JsonMapper json=JsonMapper.builder().build();String alice,bob;
    @BeforeEach void users(){
        jdbc.update("DELETE FROM user_role_audit");jdbc.update("DELETE FROM user_role_grants");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'role_alice','not-used','甲'),(102,'role_bob','not-used','乙')");
        alice="Bearer "+sessions.issueToken(new UserResponse(101,"role_alice","甲")).accessToken();
        bob="Bearer "+sessions.issueToken(new UserResponse(102,"role_bob","乙")).accessToken();
    }
    @RestController static class FutureAdminController {
        @GetMapping("/api/admin/future/probe") Map<String,String> future(){return Map.of("result","guarded");}
    }
    Map<String,String> environment(){return Map.of("DB_URL",url,"DB_USERNAME","root","DB_PASSWORD","");}
    AdminRoleTool.Result check(String username)throws Exception {return AdminRoleTool.execute(new String[]{"check",schema,username},environment());}
    AdminRoleTool.Result change(String action,String username)throws Exception {return AdminRoleTool.execute(new String[]{action,schema,username,check(username).fingerprint(),"私有权限验收"},environment());}
    int status(String path,String authorization)throws Exception {var request=get(path);if(authorization!=null)request.header("Authorization",authorization);return mvc.perform(request).andReturn().getResponse().getStatus();}
    JsonNode read(String path,String token)throws Exception {var response=mvc.perform(get(path).header("Authorization",token)).andReturn().getResponse();assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));return json.readTree(response.getContentAsByteArray());}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    @Test void anonymousAdminRequestReturns401WithBearerChallenge()throws Exception {var response=mvc.perform(get("/api/admin/me")).andReturn().getResponse();assertEquals(401,response.getStatus());assertEquals("Bearer",response.getHeader("WWW-Authenticate"));assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("LOGIN_REQUIRED",json.readTree(response.getContentAsByteArray()).path("code").asString());}
    @Test void ordinaryUserGets403AndCannotDiscoverOtherUsers()throws Exception {var response=mvc.perform(get("/api/admin/me").header("Authorization",alice)).andReturn().getResponse();assertEquals(403,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("ADMIN_REQUIRED",json.readTree(response.getContentAsByteArray()).path("code").asString());assertEquals(403,status("/api/admin/me",bob));}
    @Test void grantedAccountCanEnterWithItsExistingToken()throws Exception {assertEquals(403,status("/api/admin/me",alice));change("grant","role_alice");var data=read("/api/admin/me",alice);assertEquals(101,data.path("user").path("id").asLong());assertEquals("ADMIN",data.path("role").asString());assertFalse(data.toString().contains("password"));assertFalse(data.toString().contains("token"));}
    @Test void revocationRejectsTheVeryNextRequestWithSameToken()throws Exception {change("grant","role_alice");assertEquals(200,status("/api/admin/me",alice));change("revoke","role_alice");assertEquals(403,status("/api/admin/me",alice));assertEquals(200,status("/api/users/me",alice));}
    @Test void grantDoesNotAuthorizeAnotherAccount()throws Exception {change("grant","role_alice");assertEquals(403,status("/api/admin/me",bob));}
    @Test void expiredAdministratorSessionIsRejected()throws Exception {change("grant","role_alice");jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE user_id=101");assertEquals(401,status("/api/admin/me",alice));}
    @Test void revokedAdministratorSessionIsRejected()throws Exception {change("grant","role_alice");sessions.logout(alice);assertEquals(401,status("/api/admin/me",alice));}
    @Test void malformedBearerCannotUseAdminIdentity()throws Exception {change("grant","role_alice");for(String token:List.of("Bearer admin","Basic abc","Bearer "+"a".repeat(43)))assertEquals(401,status("/api/admin/me",token));}
    @Test void roleHeadersAndClientUserIdsCannotElevate()throws Exception {var response=mvc.perform(get("/api/admin/me").header("Authorization",alice).header("X-Role","ADMIN").header("X-User-Id","102").param("role","ADMIN").param("userId","102")).andReturn().getResponse();assertEquals(403,response.getStatus());assertEquals(0,count("user_role_grants"));}
    @Test void ordinaryPermissionResponseIsBoundToCurrentSession()throws Exception {var data=read("/api/users/me/permissions?userId=102",alice);assertEquals(101,data.path("userId").asLong());assertEquals("[\"USER\"]",data.path("roles").toString());assertTrue(data.path("permissions").isEmpty());}
    @Test void adminPermissionsDisappearAfterRevocation()throws Exception {change("grant","role_alice");var granted=read("/api/users/me/permissions",alice);assertEquals("[\"USER\",\"ADMIN\"]",granted.path("roles").toString());assertEquals("[\"ADMIN_ACCESS\"]",granted.path("permissions").toString());change("revoke","role_alice");assertTrue(read("/api/users/me/permissions",alice).path("permissions").isEmpty());}
    @Test void permissionsRequireLogin()throws Exception {assertEquals(401,status("/api/users/me/permissions",null));}
    @Test void futureAdminRouteInheritsGuardWithoutControllerCheck()throws Exception {assertEquals(401,status("/api/admin/future/probe",null));assertEquals(403,status("/api/admin/future/probe",alice));change("grant","role_alice");assertEquals(200,status("/api/admin/future/probe",alice));}
    @Test void localCorsPreflightDoesNotRequireBearer()throws Exception {var response=mvc.perform(options("/api/admin/me").header("Origin","http://localhost:5173").header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","authorization")).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("http://localhost:5173",response.getHeader("Access-Control-Allow-Origin"));}
    @Test void foreignOriginIsRejectedEvenForAdministrator()throws Exception {change("grant","role_alice");assertEquals(403,mvc.perform(get("/api/admin/me").header("Authorization",alice).header("Origin","https://foreign.example")).andReturn().getResponse().getStatus());}
    @Test void publicProductFeedDoesNotRequireAdminRole()throws Exception {assertEquals(200,status("/api/products/feed",null));assertEquals(200,status("/api/products",null));}
    @Test void registrationCannotChooseAdministratorRole()throws Exception {var response=mvc.perform(post("/api/users/register").contentType("application/json").content("{\"username\":\"role_new\",\"password\":\"Campus2026!\",\"nickname\":\"新用户\",\"role\":\"ADMIN\",\"permissions\":[\"ADMIN_ACCESS\"]}")).andReturn().getResponse();assertEquals(201,response.getStatus());assertEquals(0,count("user_role_grants"));}
    @Test void noHttpRoleGrantEndpointExists()throws Exception {change("grant","role_alice");int status=mvc.perform(post("/api/admin/users/102/roles").header("Authorization",alice).contentType("application/json").content("{\"role\":\"ADMIN\"}")).andReturn().getResponse().getStatus();assertEquals(404,status);assertEquals(1,count("user_role_grants"));}
    @Test void checkHasNoGrantOrAuditSideEffect()throws Exception {var result=check("role_alice");assertEquals(101,result.userId());assertFalse(result.admin());assertFalse(result.changed());assertTrue(result.fingerprint().matches("[0-9a-f]{64}"));assertEquals(0,count("user_role_grants"));assertEquals(0,count("user_role_audit"));}
    @Test void grantAndRevokeWriteAtomicAuditWithReason()throws Exception {assertTrue(change("grant","role_alice").changed());assertTrue(change("revoke","role_alice").changed());assertEquals(0,count("user_role_grants"));assertEquals(List.of("GRANT","REVOKE"),jdbc.queryForList("SELECT action FROM user_role_audit ORDER BY id",String.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_role_audit WHERE target_user_id=101 AND target_username='role_alice' AND operator_source='LOCAL_OPERATOR' AND reason='私有权限验收'",Integer.class));}
    @Test void alreadyGrantedOrRevokedIsNoOpWithoutDuplicateAudit()throws Exception {assertFalse(change("revoke","role_alice").changed());assertTrue(change("grant","role_alice").changed());assertFalse(change("grant","role_alice").changed());assertEquals(1,count("user_role_audit"));}
    @Test void staleFingerprintCannotMutateChangedRole()throws Exception {String fingerprint=check("role_alice").fingerprint();change("grant","role_alice");assertThrows(AdminRoleTool.Refused.class,()->AdminRoleTool.execute(new String[]{"revoke",schema,"role_alice",fingerprint,"过期检查"},environment()));assertEquals(1,count("user_role_grants"));}
    @Test void fingerprintCannotBeReusedForAnotherUser()throws Exception {String fingerprint=check("role_alice").fingerprint();assertThrows(AdminRoleTool.Refused.class,()->AdminRoleTool.execute(new String[]{"grant",schema,"role_bob",fingerprint,"目标校验"},environment()));assertEquals(0,count("user_role_grants"));}
    @Test void missingAccountIsNeverCreatedByTool()throws Exception {assertThrows(AdminRoleTool.Refused.class,()->check("role_missing"));assertEquals(2,count("users"));}
    @Test void remoteHostSchemaMismatchAndSqlInjectionAreRefused()throws Exception {for(var env:List.of(Map.of("DB_URL",url.replace("127.0.0.1","remote.example")),Map.of("DB_URL",url.replace(schema,"other_schema"))))assertThrows(AdminRoleTool.Refused.class,()->AdminRoleTool.execute(new String[]{"check",schema,"role_alice"},env));assertThrows(AdminRoleTool.Refused.class,()->check("role_alice' OR 1=1"));assertEquals(0,count("user_role_grants"));}
    @Test void invalidCommandReasonAndFingerprintAreRefused()throws Exception {for(var args:List.of(new String[]{"grant",schema,"role_alice"},new String[]{"delete",schema,"role_alice"},new String[]{"grant",schema,"role_alice","bad","理由"},new String[]{"grant",schema,"role_alice",check("role_alice").fingerprint()," "},new String[]{"grant",schema,"role_alice",check("role_alice").fingerprint(),"x".repeat(201)}))assertThrows(AdminRoleTool.Refused.class,()->AdminRoleTool.execute(args,environment()));assertEquals(0,count("user_role_audit"));}
    @Test void failedAuditInsertRollsBackRoleGrant()throws Exception {jdbc.execute("RENAME TABLE user_role_audit TO user_role_audit_saved");try{assertThrows(SQLException.class,()->change("grant","role_alice"));assertEquals(0,count("user_role_grants"));}finally{jdbc.execute("RENAME TABLE user_role_audit_saved TO user_role_audit");}assertEquals(0,count("user_role_audit"));}
    @Test void concurrentToolsCannotDuplicateGrantOrAudit()throws Exception {String fingerprint=check("role_alice").fingerprint();ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> action=()->{try{return AdminRoleTool.execute(new String[]{"grant",schema,"role_alice",fingerprint,"并发授权"},environment()).changed();}catch(AdminRoleTool.Refused stale){return false;}};var a=pool.submit(action);var b=pool.submit(action);assertTrue(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS));assertEquals(1,count("user_role_grants"));assertEquals(1,count("user_role_audit"));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
    @Test void databaseConstraintsRejectInvalidRoleAndMissingUser(){assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(101,'SUPERUSER')"));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(99999,'ADMIN')"));assertEquals(0,count("user_role_grants"));}
    @Test void unavailableRoleStorageFailsClosedInsteadOfGranting()throws Exception {change("grant","role_alice");jdbc.execute("RENAME TABLE user_role_grants TO user_role_grants_saved");try{assertEquals(500,status("/api/admin/me",alice));}finally{jdbc.execute("RENAME TABLE user_role_grants_saved TO user_role_grants");}}
    @Test void overviewRequiresAdministratorAndExposesOnlyAggregateCounts()throws Exception {assertEquals(401,status("/api/admin/overview",null));assertEquals(403,status("/api/admin/overview",alice));change("grant","role_alice");var data=read("/api/admin/overview",alice);assertEquals(4,data.size());assertEquals(2,data.path("users").asLong());assertEquals(0,data.path("products").asLong());assertEquals(0,data.path("onSaleProducts").asLong());assertEquals(0,data.path("orders").asLong());}
    @Test void overviewIsDeniedAfterRoleRevocation()throws Exception {change("grant","role_alice");assertEquals(200,status("/api/admin/overview",alice));change("revoke","role_alice");assertEquals(403,status("/api/admin/overview",alice));}

}
