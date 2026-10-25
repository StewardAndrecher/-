package com.campus.market.notification;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.notifications.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class NotificationOverviewIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson93_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson93_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    final JsonMapper json=JsonMapper.builder().build();
    String admin,user;
    @BeforeEach void fixtures(){
        for(String table:List.of("user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'notice_admin','private','管理员'),(102,'notice_user','private','普通用户')");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(101,'ADMIN')");
        admin="Bearer "+sessions.issueToken(new UserResponse(101,"notice_admin","管理员")).accessToken();
        user="Bearer "+sessions.issueToken(new UserResponse(102,"notice_user","普通用户")).accessToken();
    }
    MockHttpServletResponse response(String token)throws Exception {
        var request=get("/api/admin/notifications/overview");
        if(token!=null)request.header("Authorization",token);
        return mvc.perform(request).andReturn().getResponse();
    }
    JsonNode overview()throws Exception {
        var r=response(admin);assertEquals(200,r.getStatus(),r.getContentAsString());
        assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());
    }
    String task(int attempts,int age,boolean published,boolean delivered,int leaseSeconds){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notification_outbox(event_id,source_key,recipient_id,category,resource_id,title,body,attempts,created_at,published_at,delivered_at,lease_token,lease_until) VALUES(?,?,102,'ORDER',201,'PRIVATE_TITLE','PRIVATE_BODY',?,CURRENT_TIMESTAMP(6)-INTERVAL ? SECOND,IF(?,CURRENT_TIMESTAMP(6),NULL),IF(?,CURRENT_TIMESTAMP(6),NULL),?,CURRENT_TIMESTAMP(6)+INTERVAL ? SECOND)",id,"TEST:"+id.toUpperCase(Locale.ROOT),attempts,age,published,delivered,id,leaseSeconds);
        return id;
    }
    @Test void anonymousGets401()throws Exception {assertEquals(401,response(null).getStatus());}
    @Test void invalidSessionGets401()throws Exception {assertEquals(401,response("Bearer invalid").getStatus());}
    @Test void ordinaryAccountGets403()throws Exception {assertEquals(403,response(user).getStatus());}
    @Test void emptyTableHasExplicitZerosAndDisabledConfiguration()throws Exception {
        var n=overview();assertFalse(n.path("deliveryEnabled").asBoolean());
        for(String key:List.of("pending","unconfirmed","awaitingDelivery","retrying","leased","delivered","oldestPendingSeconds","maxPendingAttempts"))assertEquals(0,n.path(key).asLong(),key);
        assertFalse(n.path("sampledAt").asString().isBlank());
    }
    @Test void mixedStatesPartitionPendingAndAllowSubsetOverlap()throws Exception {
        task(0,120,false,false,-1);task(1,90,true,false,-1);task(3,60,false,false,30);task(8,600,true,true,30);task(1,30,false,false,-1);
        var n=overview();assertEquals(4,n.path("pending").asLong());assertEquals(3,n.path("unconfirmed").asLong());assertEquals(1,n.path("awaitingDelivery").asLong());
        assertEquals(1,n.path("retrying").asLong());assertEquals(1,n.path("leased").asLong());assertEquals(1,n.path("delivered").asLong());assertEquals(3,n.path("maxPendingAttempts").asLong());
        long age=n.path("oldestPendingSeconds").asLong();assertTrue(age>=120&&age<130);
    }
    @Test void completedOldTaskCannotInflatePendingAgeOrAttempts()throws Exception {
        task(100,10000,true,true,30);var n=overview();assertEquals(1,n.path("delivered").asLong());assertEquals(0,n.path("oldestPendingSeconds").asLong());assertEquals(0,n.path("maxPendingAttempts").asLong());assertEquals(0,n.path("leased").asLong());
    }
    @Test void futureCreationTimeIsClampedToZero()throws Exception {task(0,-100,false,false,-1);assertEquals(0,overview().path("oldestPendingSeconds").asLong());}
    @Test void expiredLeaseIsNotAnActiveWorker()throws Exception {task(2,10,false,false,-10);var n=overview();assertEquals(0,n.path("leased").asLong());assertEquals(1,n.path("retrying").asLong());}
    @Test void publishedRetryStillBelongsToAwaitingDelivery()throws Exception {task(4,10,true,false,30);var n=overview();assertEquals(1,n.path("awaitingDelivery").asLong());assertEquals(0,n.path("unconfirmed").asLong());assertEquals(1,n.path("retrying").asLong());assertEquals(1,n.path("leased").asLong());}
    @Test void samplingDoesNotClaimRepublishOrModifyTasks()throws Exception {
        String id=task(3,90,true,false,30);var before=jdbc.queryForMap("SELECT * FROM notification_outbox WHERE event_id=?",id);overview();overview();assertEquals(before,jdbc.queryForMap("SELECT * FROM notification_outbox WHERE event_id=?",id));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications",Long.class));
    }
    @Test void responseHasOnlyAggregateFields()throws Exception {
        task(1,10,false,false,-1);var n=overview();assertEquals(Set.of("deliveryEnabled","pending","unconfirmed","awaitingDelivery","retrying","leased","delivered","oldestPendingSeconds","maxPendingAttempts","sampledAt"),new HashSet<>(n.propertyNames()));
        assertFalse(n.toString().contains("PRIVATE_"));assertFalse(n.toString().contains("notice_user"));
    }
    @Test void revokedAdminSessionLosesAccessImmediately()throws Exception {overview();jdbc.update("DELETE FROM user_role_grants WHERE user_id=101");assertEquals(403,response(admin).getStatus());}
    @Test void loggedOutAdminSessionCannotRead()throws Exception {assertEquals(204,mvc.perform(post("/api/users/logout").header("Authorization",admin)).andReturn().getResponse().getStatus());assertEquals(401,response(admin).getStatus());}
    @Test void enabledFlagIsConfigurationNotBrokerProbe(){task(0,0,false,false,-1);var n=new NotificationOverviewService(jdbc,true).overview();assertTrue(n.deliveryEnabled());assertEquals(1,n.pending());}
    @Test void databaseFailureReturnsErrorInsteadOfFalseEmptySuccess()throws Exception {
        jdbc.execute("RENAME TABLE notification_outbox TO notification_outbox_private_unavailable");
        try {var r=response(admin);assertEquals(500,r.getStatus());var n=json.readTree(r.getContentAsByteArray());assertEquals("SERVER_ERROR",n.path("code").asString());assertFalse(n.toString().contains("SQL"));}
        finally {jdbc.execute("RENAME TABLE notification_outbox_private_unavailable TO notification_outbox");}
    }
}
