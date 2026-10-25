package com.campus.market.admin;
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
class AdminOrderDetailIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson98_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson98_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired AdminOrderDetailService service;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    final JsonMapper json=JsonMapper.builder().build();String admin,user;long serial;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_events","order_timeout_outbox","market_orders","products","user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'trade_admin','private','管理员'),(102,'trade_seller','private','卖家'),(103,'trade_buyer','private','买家')");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(101,'ADMIN')");serial=1000;
        admin="Bearer "+sessions.issueToken(new UserResponse(101,"trade_admin","管理员")).accessToken();
        user="Bearer "+sessions.issueToken(new UserResponse(103,"trade_buyer","买家")).accessToken();
    }
    long order(String status,long price,int age){
        long id=++serial;String created="CURRENT_TIMESTAMP(6)-INTERVAL "+age+" SECOND";
        jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id) VALUES(?,'PRIVATE_TITLE','PRIVATE_BODY',201,102)",id);
        boolean paid=Set.of("PAID","DELIVERED","COMPLETED").contains(status),delivered=Set.of("DELIVERED","COMPLETED").contains(status);
        String sql="INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,cancelled_at,paid_at,closed_at,payment_kind,delivered_at,completed_at) VALUES(?,?,103,102,?,'PRIVATE_TITLE',?,0,'PRIVATE_BUYER','PRIVATE_SELLER',?,"+created+","+(status.equals("CANCELLED")?"CURRENT_TIMESTAMP(6)":"NULL")+","+(paid?"("+created+")+INTERVAL 1 SECOND":"NULL")+","+(status.equals("CLOSED_TIMEOUT")?"("+created+")+INTERVAL 900 SECOND":"NULL")+","+(paid?"'SIMULATED'":"NULL")+","+(delivered?"("+created+")+INTERVAL 2 SECOND":"NULL")+","+(status.equals("COMPLETED")?"("+created+")+INTERVAL 3 SECOND":"NULL")+")";
        jdbc.update(sql,id,id,UUID.randomUUID().toString(),price,status);return id;
    }
    String field(long id,String name){return jdbc.queryForObject("SELECT "+name+" FROM market_orders WHERE id=?",Timestamp.class,id).toLocalDateTime().toString();}
    void event(long id,int no,String action,String from,String to,String role,Long actor,String time){jdbc.update("INSERT INTO order_events(order_id,event_no,action,from_status,to_status,actor_role,actor_id,actor_nickname,occurred_at,recorded_at) VALUES(?,?,?,?,?,?,?,'PRIVATE_EVENT_ACTOR',?,?)",id,no,action,from,to,role,actor,Timestamp.valueOf(java.time.LocalDateTime.parse(time)),Timestamp.valueOf(java.time.LocalDateTime.parse(time)));}
    long fixture(String state){
        long id=order(state,3500,1200);event(id,1,"CREATED",null,"PENDING_PAYMENT","BUYER",103L,field(id,"created_at"));
        if(state.equals("CANCELLED"))event(id,2,"CANCELLED","PENDING_PAYMENT",state,"BUYER",103L,field(id,"cancelled_at"));
        else if(state.equals("CLOSED_TIMEOUT"))event(id,2,"TIMEOUT_CLOSED","PENDING_PAYMENT",state,"SYSTEM",null,field(id,"closed_at"));
        else if(!state.equals("PENDING_PAYMENT")){
            event(id,2,"SIMULATED_PAYMENT","PENDING_PAYMENT","PAID","BUYER",103L,field(id,"paid_at"));
            if(!state.equals("PAID"))event(id,3,"DELIVERED","PAID","DELIVERED","SELLER",102L,field(id,"delivered_at"));
            if(state.equals("COMPLETED"))event(id,4,"COMPLETED","DELIVERED","COMPLETED","BUYER",103L,field(id,"completed_at"));
        }return id;
    }
    MockHttpServletResponse response(String id,String token)throws Exception {var request=get("/api/admin/orders/{id}",id);if(token!=null)request.header("Authorization",token);return mvc.perform(request).andReturn().getResponse();}
    JsonNode detail(long id)throws Exception {var r=response(Long.toString(id),admin);assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    @Test void anonymousIs401()throws Exception {assertEquals(401,response("1",null).getStatus());}
    @Test void invalidSessionIs401()throws Exception {assertEquals(401,response("1","Bearer invalid").getStatus());}
    @Test void ordinaryAccountIs403EvenWhenBuyer()throws Exception {assertEquals(403,response(Long.toString(fixture("PAID")),user).getStatus());}
    @Test void invalidCanonicalOrUnsafeIdsAre400()throws Exception {for(String id:List.of("0","-1","01","1.5","1e3","9007199254740992","99999999999999999"))assertEquals(400,response(id,admin).getStatus(),id);}
    @Test void unknownOrderIs404()throws Exception {assertEquals(404,response("9007199254740991",admin).getStatus());}
    @Test void allSixStatusesHaveMatchingTerminalEvents()throws Exception {for(String state:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT")){var n=detail(fixture(state));assertEquals(state,n.path("order").path("status").asString());assertEquals(state,n.path("events").get(n.path("events").size()-1).path("toStatus").asString());}}
    @Test void completedOrderReturnsFourOrderedRolesAndTimes()throws Exception {long id=fixture("COMPLETED");var n=detail(id);assertEquals(4,n.path("events").size());assertEquals("SELLER",n.path("events").get(2).path("actorRole").asString());assertEquals(n.path("order").path("completedAt").asString(),n.path("events").get(3).path("occurredAt").asString());}
    @Test void cancellationAndTimeoutPreserveDistinctActorRoles()throws Exception {assertEquals("BUYER",detail(fixture("CANCELLED")).path("events").get(1).path("actorRole").asString());assertEquals("SYSTEM",detail(fixture("CLOSED_TIMEOUT")).path("events").get(1).path("actorRole").asString());}
    @Test void currentProductEditsDoNotAlterOrderEvidence()throws Exception {long id=fixture("PAID");jdbc.update("UPDATE products SET title='CURRENT_TITLE',price_in_cents=9999 WHERE id=?",id);var n=detail(id);assertEquals("PRIVATE_TITLE",n.path("order").path("productTitleSnapshot").asString());assertEquals(3500,n.path("order").path("priceInCentsSnapshot").asLong());}
    @Test void responseHasExactMinimalOrderAndEventFields()throws Exception {var n=detail(fixture("COMPLETED"));assertEquals(Set.of("order","events"),new HashSet<>(n.propertyNames()));assertEquals(Set.of("id","productId","productTitleSnapshot","priceInCentsSnapshot","status","createdAt","paymentDueAt","paidAt","deliveredAt","completedAt","cancelledAt","closedAt","paymentKind"),new HashSet<>(n.path("order").propertyNames()));for(var e:n.path("events"))assertEquals(Set.of("sequence","action","fromStatus","toStatus","actorRole","occurredAt","recordedAt"),new HashSet<>(e.propertyNames()));}
    @Test void participantAndActorIdentityNeverLeak()throws Exception {var text=detail(fixture("COMPLETED")).toString();for(String privateValue:List.of("PRIVATE_BUYER","PRIVATE_SELLER","PRIVATE_EVENT_ACTOR","actorId","actorNickname","buyerId","sellerId","requestId","password_hash"))assertFalse(text.contains(privateValue),privateValue);}
    @Test void wrongUnderlyingActorIdentityIsUnavailable()throws Exception {long id=fixture("PAID");jdbc.update("UPDATE order_events SET actor_id=101 WHERE order_id=? AND event_no=2",id);assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void missingHistoryIsUnavailableWithoutFabrication()throws Exception {long id=order("PAID",3500,1200);assertEquals(503,response(Long.toString(id),admin).getStatus());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_events",Long.class));}
    @Test void finalEventMustMatchCurrentOrderStatus()throws Exception {long id=fixture("COMPLETED");jdbc.update("DELETE FROM order_events WHERE order_id=? AND event_no=4",id);assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void sequenceGapIsUnavailable()throws Exception {long id=fixture("COMPLETED");jdbc.update("DELETE FROM order_events WHERE order_id=? AND event_no=2",id);assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void historySnapshotCannotBreakFollowingTransition()throws Exception {long id=fixture("PAID");jdbc.update("UPDATE order_events SET action='HISTORY_SNAPSHOT',from_status=NULL,to_status='PAID',actor_role='SYSTEM',actor_id=NULL WHERE order_id=? AND event_no=1",id);assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void stageTimestampMustMatchOrderColumn()throws Exception {long id=fixture("PAID");jdbc.update("UPDATE order_events SET occurred_at=occurred_at+INTERVAL 1 MICROSECOND,recorded_at=recorded_at+INTERVAL 1 MICROSECOND WHERE order_id=? AND event_no=2",id);assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void recordedTimeCannotMoveBackwards()throws Exception {long id=fixture("COMPLETED");jdbc.update("UPDATE order_events SET recorded_at=CURRENT_TIMESTAMP(6) WHERE order_id=? AND event_no=2",id);assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void singleHistoricalSnapshotForAnyStateRemainsExplicit()throws Exception {for(String state:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT")){long id=order(state,3500,1200);event(id,1,"HISTORY_SNAPSHOT",null,state,"SYSTEM",null,field(id,"created_at"));var n=detail(id);assertEquals(1,n.path("events").size());assertEquals("HISTORY_SNAPSHOT",n.path("events").get(0).path("action").asString());}}
    @Test void historySnapshotMayBeFollowedByValidNewCancellation()throws Exception {long id=order("CANCELLED",3500,1200);event(id,1,"HISTORY_SNAPSHOT",null,"PENDING_PAYMENT","SYSTEM",null,field(id,"created_at"));event(id,2,"CANCELLED","PENDING_PAYMENT","CANCELLED","BUYER",103L,field(id,"cancelled_at"));assertEquals(2,detail(id).path("events").size());}
    @Test void firstEventCannotPretendPaymentIsCreation()throws Exception {long id=order("PAID",3500,1200);event(id,1,"SIMULATED_PAYMENT","PENDING_PAYMENT","PAID","BUYER",103L,field(id,"paid_at"));assertEquals(503,response(Long.toString(id),admin).getStatus());}
    @Test void readingDoesNotChangeOrderEventsOrOutbox()throws Exception {long id=fixture("COMPLETED");var before=jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id);var history=jdbc.queryForList("SELECT * FROM order_events WHERE order_id=? ORDER BY event_no",id);detail(id);detail(id);assertEquals(before,jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id));assertEquals(history,jdbc.queryForList("SELECT * FROM order_events WHERE order_id=? ORDER BY event_no",id));for(String t:List.of("order_timeout_outbox","notification_outbox","user_notifications"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));}
    @Test void orderAndEventsShareRepeatableReadSnapshot()throws Exception {
        long id=fixture("PAID");var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{var outer=new org.springframework.transaction.support.TransactionTemplate(transactions);outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.executeWithoutResult(tx->{assertEquals("PAID",service.detail(Long.toString(id)).order().status());
                try{pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(inner->{jdbc.update("UPDATE market_orders SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);event(id,3,"DELIVERED","PAID","DELIVERED","SELLER",102L,field(id,"delivered_at"));})).get(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                var same=service.detail(Long.toString(id));assertEquals("PAID",same.order().status());assertEquals(2,same.events().size());
            });var fresh=detail(id);assertEquals("DELIVERED",fresh.path("order").path("status").asString());assertEquals(3,fresh.path("events").size());
        }finally{pool.shutdownNow();}
    }
    @Test void revokedAdminCannotReadNextRequest()throws Exception {long id=fixture("PAID");detail(id);jdbc.update("DELETE FROM user_role_grants WHERE user_id=101");assertEquals(403,response(Long.toString(id),admin).getStatus());}
    @Test void loggedOutAdminCannotRead()throws Exception {long id=fixture("PAID");assertEquals(204,mvc.perform(post("/api/users/logout").header("Authorization",admin)).andReturn().getResponse().getStatus());assertEquals(401,response(Long.toString(id),admin).getStatus());}
    @Test void unavailableEventTableIsErrorNotEmptyHistory()throws Exception {long id=fixture("PAID");jdbc.execute("RENAME TABLE order_events TO order_events_private_unavailable");try{var r=response(Long.toString(id),admin);assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));}finally{jdbc.execute("RENAME TABLE order_events_private_unavailable TO order_events");}}
}
