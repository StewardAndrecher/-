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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.notifications.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class NotificationTargetIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson110_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson110_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}

    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    @Autowired NotificationOutbox outbox;@Autowired PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String admin,user,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'batch_admin','unused','管理员'),(102,'batch_owner','unused','收件人'),(103,'batch_other','unused','其他用户')");

        admin=token(101,"batch_admin");user=token(102,"batch_owner");other=token(103,"batch_other");
    }
    String token(long id,String name){return "Bearer "+sessions.issueToken(new UserResponse(id,name,name)).accessToken();}
    long notice(long recipient){
        String source="TEST:"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        new TransactionTemplate(transactions).executeWithoutResult(tx->outbox.enqueue(source,recipient,"ORDER",1,"批量通知","私有批量已读验收"));
        String event=jdbc.queryForObject("SELECT event_id FROM notification_outbox WHERE source_key=?",String.class,source);outbox.deliver(event);
        return jdbc.queryForObject("SELECT id FROM user_notifications WHERE event_id=?",Long.class,event);
    }
    MockHttpServletResponse batch(String body,String auth)throws Exception {
        var request=post("/api/notifications/read-batch").contentType("application/json").content(body);
        if(auth!=null)request.header("Authorization",auth);
        return mvc.perform(request).andReturn().getResponse();
    }
    MockHttpServletResponse batch(List<Long> ids,String auth)throws Exception {return batch(json.writeValueAsString(Map.of("ids",ids)),auth);}
    JsonNode success(MockHttpServletResponse response)throws Exception {assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));return json.readTree(response.getContentAsByteArray());}
    long unread(long id){return jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE recipient_id=? AND read_at IS NULL",Long.class,id);}
    String at(long id){return jdbc.queryForObject("SELECT read_at FROM user_notifications WHERE id=?",String.class,id);}

    @Autowired NotificationService notices;
    long notice(String source,long recipient,String category,long resource){
        new TransactionTemplate(transactions).executeWithoutResult(tx->outbox.enqueue(source,recipient,category,resource,"任意标题","https://invalid.example/任意正文"));
        String event=jdbc.queryForObject("SELECT event_id FROM notification_outbox WHERE source_key=? AND recipient_id=?",String.class,source,recipient);outbox.deliver(event);
        return jdbc.queryForObject("SELECT id FROM user_notifications WHERE event_id=?",Long.class,event);
    }
    JsonNode page(String auth)throws Exception{return success(mvc.perform(get("/api/notifications").header("Authorization",auth)).andReturn().getResponse());}
    JsonNode read(long id,String auth)throws Exception{return success(mvc.perform(post("/api/notifications/"+id+"/read").header("Authorization",auth)).andReturn().getResponse());}
    String target(long id){return notices.page(1,20,"ALL",user).records().stream().filter(n->n.id()==id).findFirst().orElseThrow().target();}
    @Test void reviewSourceOpensReviews(){long id=notice("ORDER_REVIEW:7:103",102,"ORDER",7);assertEquals("ORDER_REVIEWS",target(id));}
    @Test void ownIssueSourceOpensOwnIssue(){long id=notice("ORDER_ISSUE:7:102:RESOLVED",102,"ORDER",7);assertEquals("OWN_ORDER_ISSUE",target(id));}
    @Test void ordinaryOrderProgressKeepsDetail(){long id=notice("ORDER:7:3",102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}
    @Test void reportTargetRemainsReporterDetail(){long id=notice("REPORT:7:RESOLVED",102,"REPORT",7);assertEquals("REPORT_DETAIL",target(id));}
    @Test void moderationTargetRemainsProductModeration(){long id=notice("MODERATION:7",102,"MODERATION",7);assertEquals("PRODUCT_MODERATION",target(id));}
    @Test void titleAndBodyCannotChooseADestination(){long id=notice("ORDER:7:3",102,"ORDER",7);jdbc.update("UPDATE user_notifications SET title='收到交易评价',body='/pages/admin/orders' WHERE id=?",id);assertEquals("ORDER_DETAIL",target(id));}
    @Test void reviewResourceMismatchKeepsGenericDetail(){long id=notice("ORDER_REVIEW:8:103",102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}
    @Test void issueResourceMismatchKeepsGenericDetail(){long id=notice("ORDER_ISSUE:8:102:RESOLVED",102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}
    @Test void issueRecipientMismatchCannotClaimOwnResult(){long id=notice("ORDER_ISSUE:7:103:RESOLVED",102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}
    @Test void selfReviewSourceIsNotAReceivedReview(){long id=notice("ORDER_REVIEW:7:102",102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}
    @Test void legacyUnknownOrderSourcesRemainCompatible(){long id=notice("LEGACY_ORDER:7",102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}
    @Test void malformedReviewAuthorsNeverChangeDestination(){for(String suffix:List.of("0","0103","103:EXTRA","9007199254740992","99999999999999999999")){long id=notice("ORDER_REVIEW:7:"+suffix,102,"ORDER",7);assertEquals("ORDER_DETAIL",target(id));}}
    @Test void sourceCategoryCannotCrossItsAllowedBusinessDomain(){long id=notice("ORDER_REVIEW:7:103",102,"REPORT",7);assertEquals("REPORT_DETAIL",target(id));}
    @Test void listSingleReadAndRepeatedReadKeepSameTarget()throws Exception{long id=notice("ORDER_REVIEW:7:103",102,"ORDER",7);assertEquals("ORDER_REVIEWS",page(user).at("/records/0/target").asString());var first=read(id,user);assertEquals("ORDER_REVIEWS",first.path("target").asString());assertEquals(first,read(id,user));}
    @Test void mixedBatchKeepsEachOriginalTarget()throws Exception{long a=notice("ORDER_REVIEW:7:103",102,"ORDER",7),b=notice("ORDER_ISSUE:8:102:RESOLVED",102,"ORDER",8);var n=success(batch(List.of(a,b),user));assertEquals("ORDER_REVIEWS",n.at("/records/0/target").asString());assertEquals("OWN_ORDER_ISSUE",n.at("/records/1/target").asString());}
    @Test void responseDoesNotExposePrivateSourceOrActor()throws Exception{notice("ORDER_REVIEW:7:103",102,"ORDER",7);var n=page(user).at("/records/0");assertEquals(Set.of("id","category","resourceId","target","title","body","occurredAt","createdAt","readAt"),new HashSet<>(n.propertyNames()));for(String key:List.of("sourceKey","recipientId","reviewerId","eventId","url"))assertFalse(n.has(key));assertFalse(n.toString().contains("ORDER_REVIEW:7:103"));}
    @Test void otherUserCannotListOrReadTarget()throws Exception{long id=notice("ORDER_ISSUE:7:102:RESOLVED",102,"ORDER",7);assertEquals(0,page(other).path("total").asLong());assertEquals(404,mvc.perform(post("/api/notifications/"+id+"/read").header("Authorization",other)).andReturn().getResponse().getStatus());}
    @Test void anonymousAndRevokedSessionCannotReadTargets()throws Exception{long id=notice("ORDER_REVIEW:7:103",102,"ORDER",7);assertEquals(401,mvc.perform(get("/api/notifications")).andReturn().getResponse().getStatus());mvc.perform(post("/api/users/logout").header("Authorization",user));assertEquals(401,mvc.perform(post("/api/notifications/"+id+"/read").header("Authorization",user)).andReturn().getResponse().getStatus());}
    @Test void listingTargetsDoesNotRewriteInboxOrSource()throws Exception{long id=notice("ORDER_REVIEW:7:103",102,"ORDER",7);var before=jdbc.queryForList("SELECT * FROM notification_outbox");var inbox=jdbc.queryForMap("SELECT * FROM user_notifications WHERE id=?",id);page(user);page(user);assertEquals(before,jdbc.queryForList("SELECT * FROM notification_outbox"));assertEquals(inbox,jdbc.queryForMap("SELECT * FROM user_notifications WHERE id=?",id));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notifications' AND column_name='target'",Long.class));}
    @Test void singleReadDoesNotAcquireAnOutboxRowLock()throws Exception{long id=notice("ORDER_REVIEW:7:103",102,"ORDER",7);assertNoSourceLock(id,false);}
    @Test void batchReadDoesNotAcquireAnOutboxRowLock()throws Exception{long id=notice("ORDER_ISSUE:7:102:RESOLVED",102,"ORDER",7);assertNoSourceLock(id,true);}
    void assertNoSourceLock(long id,boolean batch)throws Exception{
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=DriverManager.getConnection(url,"root","")){
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.executeQuery("SELECT event_id FROM notification_outbox FOR UPDATE").close();}
            try{
                var done=pool.submit(()->{if(batch)return notices.readBatch(new NotificationModels.ReadBatch(List.of(id)),user).records().get(0).target();return notices.read(id,user).target();});
                assertEquals(batch?"OWN_ORDER_ISSUE":"ORDER_REVIEWS",done.get(3,TimeUnit.SECONDS));
            }finally{connection.rollback();}
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
