package com.campus.market.order;
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
class OrderTaskIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson109_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson109_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    final JsonMapper json=JsonMapper.builder().build();String admin,user,seller;long serial;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_reviews","order_events","order_timeout_outbox","market_orders","products","user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'trade_admin','private','管理员'),(102,'trade_seller','private','卖家'),(103,'trade_buyer','private','买家')");
        serial=1000;
        admin="Bearer "+sessions.issueToken(new UserResponse(101,"trade_admin","管理员")).accessToken();
        user="Bearer "+sessions.issueToken(new UserResponse(103,"trade_buyer","买家")).accessToken();
        seller="Bearer "+sessions.issueToken(new UserResponse(102,"trade_seller","卖家")).accessToken();
    }
    MockHttpServletResponse response(String token)throws Exception {
        var req=get("/api/orders/tasks/overview");if(token!=null)req.header("Authorization",token);
        return mvc.perform(req).andReturn().getResponse();
    }
    JsonNode overview()throws Exception {var r=response(user);assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    long order(String status,long price,int age){
        long id=++serial;String created="CURRENT_TIMESTAMP(6)-INTERVAL "+age+" SECOND";
        jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id) VALUES(?,'PRIVATE_TITLE','PRIVATE_BODY',201,102)",id);
        boolean paid=Set.of("PAID","DELIVERED","COMPLETED").contains(status),delivered=Set.of("DELIVERED","COMPLETED").contains(status);
        String sql="INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,cancelled_at,paid_at,closed_at,payment_kind,delivered_at,completed_at) VALUES(?,?,103,102,?,'PRIVATE_TITLE',?,0,'PRIVATE_BUYER','PRIVATE_SELLER',?,"+created+","+(status.equals("CANCELLED")?"CURRENT_TIMESTAMP(6)":"NULL")+","+(paid?"("+created+")+INTERVAL 1 SECOND":"NULL")+","+(status.equals("CLOSED_TIMEOUT")?"("+created+")+INTERVAL 900 SECOND":"NULL")+","+(paid?"'SIMULATED'":"NULL")+","+(delivered?"("+created+")+INTERVAL 2 SECOND":"NULL")+","+(status.equals("COMPLETED")?"("+created+")+INTERVAL 3 SECOND":"NULL")+")";
        jdbc.update(sql,id,id,UUID.randomUUID().toString(),price,status);return id;
    }

    void review(long id,long who){jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating,content) VALUES(?,?,5,'PRIVATE_REVIEW')",id,who);}
    long count(JsonNode n,String path){return n.at(path).asLong();}
    @Test void anonymousIs401()throws Exception{assertEquals(401,response(null).getStatus());}
    @Test void invalidTokenIs401()throws Exception{assertEquals(401,response("Bearer invalid").getStatus());}
    @Test void revokedSessionIs401()throws Exception{assertEquals(204,mvc.perform(post("/api/users/logout").header("Authorization",user)).andReturn().getResponse().getStatus());assertEquals(401,response(user).getStatus());}
    @Test void ordinaryUserNeedsNoAdminRole()throws Exception{assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_role_grants",Long.class));assertEquals(200,response(user).getStatus());}
    @Test void emptyOwnOrdersProduceExplicitZeroCounts()throws Exception{var n=overview();assertEquals(103,n.path("userId").asLong());for(var group:List.of("buyer","seller"))for(var value:n.path(group))assertEquals(0,value.asLong());assertFalse(n.path("sampledAt").asString().isBlank());}
    @Test void buyerPendingSplitsPayableAndOverdue()throws Exception{order("PENDING_PAYMENT",100,60);order("PENDING_PAYMENT",100,1200);var b=overview().path("buyer");assertEquals(2,b.path("pendingPayment").asLong());assertEquals(1,b.path("payable").asLong());assertEquals(1,b.path("overduePendingPayment").asLong());}
    @Test void duePendingRemainsPendingAndCannotBePayable()throws Exception{long id=order("PENDING_PAYMENT",100,900);var n=overview();assertEquals(1,count(n,"/buyer/overduePendingPayment"));assertEquals(0,count(n,"/buyer/payable"));assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM market_orders WHERE id=?",String.class,id));}
    @Test @org.springframework.transaction.annotation.Transactional
    void oneMicrosecondBeforeAtAndAfterDueUsesSameDatabaseClock()throws Exception{
        jdbc.execute("SET timestamp=1792555200.123456");
        try{
            for(int delta:List.of(-1,0,1)){long id=order("PENDING_PAYMENT",100,900);jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 15 MINUTE+INTERVAL ? MICROSECOND WHERE id=?",delta,id);}
            var n=overview();assertEquals(3,count(n,"/buyer/pendingPayment"));assertEquals(1,count(n,"/buyer/payable"));assertEquals(2,count(n,"/buyer/overduePendingPayment"));
        }finally{jdbc.execute("SET timestamp=0");}
    }
    @Test void onlyBuyerDeliveredIsToConfirm()throws Exception{for(String state:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(state,100,1200);var n=overview();assertEquals(1,count(n,"/buyer/toConfirm"));assertEquals(0,count(n,"/seller/toDeliver"));assertEquals(1,count(n,"/buyer/toReview"));}
    @Test void onlySellerPaidIsToDeliver()throws Exception{for(String state:List.of("PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(state,100,1200);var n=json.readTree(response(seller).getContentAsByteArray());assertEquals(1,count(n,"/seller/toDeliver"));assertEquals(0,count(n,"/buyer/toConfirm"));assertEquals(1,count(n,"/seller/toReview"));}
    @Test void sameAccountHasIndependentBuyerAndSellerTasks()throws Exception{order("DELIVERED",100,60);long id=order("PAID",100,60);jdbc.update("UPDATE market_orders SET buyer_id=101,seller_id=103 WHERE id=?",id);var n=overview();assertEquals(1,count(n,"/buyer/toConfirm"));assertEquals(1,count(n,"/seller/toDeliver"));}
    @Test void unrelatedUserCannotSeeAnyTasks()throws Exception{order("DELIVERED",100,60);order("PAID",100,60);order("COMPLETED",100,60);var n=json.readTree(response(admin).getContentAsByteArray());for(var group:List.of("buyer","seller"))for(var value:n.path(group))assertEquals(0,value.asLong());}
    @Test void peerReviewDoesNotRemoveOwnReviewTask()throws Exception{long id=order("COMPLETED",100,60);review(id,102);assertEquals(1,count(overview(),"/buyer/toReview"));assertEquals(0,count(json.readTree(response(seller).getContentAsByteArray()),"/seller/toReview"));}
    @Test void ownReviewRemovesOnlyOwnTask()throws Exception{long id=order("COMPLETED",100,60);review(id,103);assertEquals(0,count(overview(),"/buyer/toReview"));assertEquals(1,count(json.readTree(response(seller).getContentAsByteArray()),"/seller/toReview"));}
    @Test void bothReviewsCannotMultiplyTasksOrOtherOrders()throws Exception{long id=order("COMPLETED",100,60);review(id,103);review(id,102);order("COMPLETED",100,60);assertEquals(1,count(overview(),"/buyer/toReview"));}
    @Test void onlyCompletedOrdersCanBeReviewTasks()throws Exception{for(String s:List.of("PENDING_PAYMENT","PAID","DELIVERED","CANCELLED","CLOSED_TIMEOUT"))order(s,100,1200);assertEquals(0,count(overview(),"/buyer/toReview"));assertEquals(0,count(json.readTree(response(seller).getContentAsByteArray()),"/seller/toReview"));}
    @Test void spoofedUserRoleAndTimeParametersAreIgnored()throws Exception{order("PENDING_PAYMENT",100,60);var r=mvc.perform(get("/api/orders/tasks/overview").header("Authorization",admin).param("userId","103").param("role","BUYER").param("now","9999-01-01")).andReturn().getResponse();assertEquals(200,r.getStatus());var n=json.readTree(r.getContentAsByteArray());assertEquals(101,n.path("userId").asLong());assertEquals(0,count(n,"/buyer/pendingPayment"));}
    @Test void responseIsOnlyOwnMinimalAggregates()throws Exception{long id=order("COMPLETED",100,60);review(id,102);var n=overview();assertEquals(Set.of("userId","sampledAt","buyer","seller"),new HashSet<>(n.propertyNames()));assertEquals(Set.of("pendingPayment","payable","overduePendingPayment","toConfirm","toReview"),new HashSet<>(n.path("buyer").propertyNames()));assertEquals(Set.of("toDeliver","toReview"),new HashSet<>(n.path("seller").propertyNames()));assertFalse(n.toString().contains("PRIVATE"));assertFalse(n.toString().contains("orderId"));assertFalse(n.toString().contains("rating"));}
    @Test void readingDoesNotWriteOrdersEventsReviewsOrNotifications()throws Exception{long id=order("PENDING_PAYMENT",100,1200);var before=jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id);overview();overview();assertEquals(before,jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id));for(String t:List.of("order_events","order_timeout_outbox","order_reviews","notification_outbox","user_notifications"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));}
    @Test void stateProgressChangesTheNextSnapshot()throws Exception{long id=order("DELIVERED",100,60);assertEquals(1,count(overview(),"/buyer/toConfirm"));jdbc.update("UPDATE market_orders SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);var n=overview();assertEquals(0,count(n,"/buyer/toConfirm"));assertEquals(1,count(n,"/buyer/toReview"));}
    @Test void cancelledAndClosedOrdersAreNeverActionable()throws Exception{order("CANCELLED",100,1200);order("CLOSED_TIMEOUT",100,1200);var n=overview();for(var group:List.of("buyer","seller"))for(var value:n.path(group))assertEquals(0,value.asLong());}
    @Test void databaseFailureIsNotFalseZeroSuccess()throws Exception{jdbc.execute("RENAME TABLE market_orders TO market_orders_private_unavailable");try{var r=response(user);assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));}finally{jdbc.execute("RENAME TABLE market_orders_private_unavailable TO market_orders");}}
    @Test void safeIntegerLimitsAreExact(){assertEquals(9007199254740991L,OrderTaskService.number(new BigDecimal("9007199254740991")));assertEquals(0,OrderTaskService.number(BigDecimal.ZERO));}
    @Test void unsafeFractionsNullAndOverflowBecome503(){for(String n:List.of("-1","1.5","9007199254740992","9223372036854775808"))assertEquals(503,assertThrows(OrderException.class,()->OrderTaskService.number(new BigDecimal(n))).status());assertEquals(503,assertThrows(OrderException.class,()->OrderTaskService.number(null)).status());}
}
