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
class NotificationBatchReadIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson95_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson95_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}

    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    @Autowired NotificationOutbox outbox;@Autowired PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String admin,user,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'batch_admin','unused','管理员'),(102,'batch_owner','unused','收件人'),(103,'batch_other','unused','其他用户')");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(101,'ADMIN')");
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
    @Test void anonymousBatchRequiresSession()throws Exception {assertEquals(401,batch(List.of(1L),null).getStatus());}
    @Test void twoOwnNoticesAreMarkedAndOthersRemainUnread()throws Exception {long a=notice(102),b=notice(102),c=notice(102),foreign=notice(103);var n=success(batch(List.of(b,a),user));assertEquals(2,n.path("records").size());assertEquals(a,n.path("records").get(0).path("id").asLong());assertNotNull(at(a));assertNotNull(at(b));assertNull(at(c));assertNull(at(foreign));assertEquals(1,unread(102));}
    @Test void twentyIdsAreAcceptedAndTwentyOneRejected()throws Exception {var ids=new ArrayList<Long>();for(int i=0;i<21;i++)ids.add(notice(102));assertEquals(400,batch(ids,user).getStatus());assertEquals(21,unread(102));success(batch(ids.subList(0,20),user));assertEquals(1,unread(102));}
    @Test void missingOrEmptyIdsAreRejected()throws Exception {for(String body:List.of("{}","{\"ids\":null}","{\"ids\":[]}","{\"ids\":1}"))assertEquals(400,batch(body,user).getStatus(),body);}
    @Test void nonIntegerJsonIdsAreRejected()throws Exception {for(String value:List.of("\"1\"","1.5","true","{}","[]"))assertEquals(400,batch("{\"ids\":["+value+"]}",user).getStatus(),value);}
    @Test void invalidAndUnsafeIntegerIdsAreRejected()throws Exception {for(String value:List.of("null","0","-1","9007199254740992","9223372036854775808"))assertEquals(400,batch("{\"ids\":["+value+"]}",user).getStatus(),value);}
    @Test void duplicateIdsAreRejectedBeforeUpdates()throws Exception {long a=notice(102);assertEquals(400,batch(List.of(a,a),user).getStatus());assertNull(at(a));}
    @Test void mixedRecipientBatchRollsBackWithoutPartialRead()throws Exception {long a=notice(102),b=notice(103);assertEquals(404,batch(List.of(a,b),user).getStatus());assertNull(at(a));assertNull(at(b));}
    @Test void nonexistentIdDoesNotMarkOwnNotice()throws Exception {long a=notice(102);assertEquals(404,batch(List.of(a,999999L),user).getStatus());assertNull(at(a));}
    @Test void administratorCannotOverrideRecipientOwnership()throws Exception {long a=notice(102);assertEquals(404,batch(List.of(a),admin).getStatus());assertNull(at(a));}
    @Test void repeatedBatchAndSingleReadPreserveFirstReadTime()throws Exception {long a=notice(102);success(batch(List.of(a),user));String first=at(a);success(batch(List.of(a),user));assertEquals(first,at(a));assertEquals(200,mvc.perform(post("/api/notifications/"+a+"/read").header("Authorization",user)).andReturn().getResponse().getStatus());assertEquals(first,at(a));}
    @Test void preexistingReadTimestampIsPreservedInMixedBatch()throws Exception {long a=notice(102),b=notice(102);success(batch(List.of(a),user));String first=at(a);success(batch(List.of(b,a),user));assertEquals(first,at(a));assertNotNull(at(b));}
    @Test void notificationDeliveredAfterPageSnapshotStaysUnread()throws Exception {long a=notice(102),b=notice(102);var snapshot=List.of(a,b);long later=notice(102);success(batch(snapshot,user));assertNull(at(later));assertEquals(1,unread(102));}
    @Test void updateFailureRollsBackEveryNotice()throws Exception {long a=notice(102),b=notice(102);jdbc.execute("CREATE TRIGGER private_batch_failure BEFORE UPDATE ON user_notifications FOR EACH ROW BEGIN IF NEW.id="+b+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private batch failure'; END IF; END");try{assertEquals(500,batch(List.of(a,b),user).getStatus());assertNull(at(a));assertNull(at(b));}finally{jdbc.execute("DROP TRIGGER private_batch_failure");}}
    @Test void overlappingBatchesAreIdempotent()throws Exception {long a=notice(102),b=notice(102),c=notice(102);var pool=Executors.newFixedThreadPool(2);try{var x=pool.submit(()->batch(List.of(b,a),user).getStatus());var y=pool.submit(()->batch(List.of(c,b),user).getStatus());assertEquals(200,x.get(10,TimeUnit.SECONDS));assertEquals(200,y.get(10,TimeUnit.SECONDS));assertEquals(0,unread(102));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
    @Test void batchResponseContainsOnlyPublicNoticeFields()throws Exception {long a=notice(102);var n=success(batch(List.of(a),user)).path("records").get(0);assertFalse(n.path("readAt").isNull());for(String key:List.of("recipientId","eventId","sourceKey","leaseToken"))assertFalse(n.has(key));}
}
