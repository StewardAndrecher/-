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
class AdminOrderOverviewIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson96_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson96_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    final JsonMapper json=JsonMapper.builder().build();String admin,user;long serial;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_events","order_timeout_outbox","market_orders","products","user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'trade_admin','private','管理员'),(102,'trade_seller','private','卖家'),(103,'trade_buyer','private','买家')");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(101,'ADMIN')");serial=1000;
        admin="Bearer "+sessions.issueToken(new UserResponse(101,"trade_admin","管理员")).accessToken();
        user="Bearer "+sessions.issueToken(new UserResponse(103,"trade_buyer","买家")).accessToken();
    }
    MockHttpServletResponse response(String token)throws Exception {
        var req=get("/api/admin/orders/overview");if(token!=null)req.header("Authorization",token);
        return mvc.perform(req).andReturn().getResponse();
    }
    JsonNode overview()throws Exception {var r=response(admin);assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    long order(String status,long price,int age){
        long id=++serial;String created="CURRENT_TIMESTAMP(6)-INTERVAL "+age+" SECOND";
        jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id) VALUES(?,'PRIVATE_TITLE','PRIVATE_BODY',201,102)",id);
        boolean paid=Set.of("PAID","DELIVERED","COMPLETED").contains(status),delivered=Set.of("DELIVERED","COMPLETED").contains(status);
        String sql="INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,cancelled_at,paid_at,closed_at,payment_kind,delivered_at,completed_at) VALUES(?,?,103,102,?,'PRIVATE_TITLE',?,0,'PRIVATE_BUYER','PRIVATE_SELLER',?,"+created+","+(status.equals("CANCELLED")?"CURRENT_TIMESTAMP(6)":"NULL")+","+(paid?"("+created+")+INTERVAL 1 SECOND":"NULL")+","+(status.equals("CLOSED_TIMEOUT")?"("+created+")+INTERVAL 900 SECOND":"NULL")+","+(paid?"'SIMULATED'":"NULL")+","+(delivered?"("+created+")+INTERVAL 2 SECOND":"NULL")+","+(status.equals("COMPLETED")?"("+created+")+INTERVAL 3 SECOND":"NULL")+")";
        jdbc.update(sql,id,id,UUID.randomUUID().toString(),price,status);return id;
    }
    @Test void anonymousIs401()throws Exception {assertEquals(401,response(null).getStatus());}
    @Test void invalidSessionIs401()throws Exception {assertEquals(401,response("Bearer invalid").getStatus());}
    @Test void ordinaryAccountIs403()throws Exception {assertEquals(403,response(user).getStatus());}
    @Test void emptyTableHasAllZeroCounters()throws Exception {var n=overview();for(String key:List.of("total","pendingPayment","paid","delivered","completed","cancelled","closedTimeout","completedAmountInCents","overduePendingPayment","oldestPendingPaymentSeconds"))assertEquals(0,n.path(key).asLong(),key);assertFalse(n.path("sampledAt").asString().isBlank());}
    @Test void sixStatusesPartitionTotal()throws Exception {for(String s:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(s,3500,1200);var n=overview();assertEquals(6,n.path("total").asLong());for(String key:List.of("pendingPayment","paid","delivered","completed","cancelled","closedTimeout"))assertEquals(1,n.path(key).asLong());assertEquals(3500,n.path("completedAmountInCents").asLong());}
    @Test void onlyCompletedSnapshotsContributeMoney()throws Exception {for(String s:List.of("PENDING_PAYMENT","PAID","DELIVERED","CANCELLED","CLOSED_TIMEOUT"))order(s,99999999,1200);order("COMPLETED",2500,60);order("COMPLETED",9999,60);assertEquals(12499,overview().path("completedAmountInCents").asLong());}
    @Test void currentProductPriceCannotChangeHistoricalAmount()throws Exception {long id=order("COMPLETED",3500,60);jdbc.update("UPDATE products SET price_in_cents=9999 WHERE id=?",id);assertEquals(3500,overview().path("completedAmountInCents").asLong());}
    @Test void onlyExpiredPendingBelongsToOverdueSubset()throws Exception {for(String s:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(s,100,1200);order("PENDING_PAYMENT",100,60);var n=overview();assertEquals(2,n.path("pendingPayment").asLong());assertEquals(1,n.path("overduePendingPayment").asLong());}
    @Test void exactDueTimeAlreadyCountsAsOverdue()throws Exception {order("PENDING_PAYMENT",100,900);assertEquals(1,overview().path("overduePendingPayment").asLong());}
    @Test void freshPendingIsNotOverdue()throws Exception {order("PENDING_PAYMENT",100,60);var n=overview();assertEquals(0,n.path("overduePendingPayment").asLong());assertTrue(n.path("oldestPendingPaymentSeconds").asLong()>=60);}
    @Test void futureCreationTimeCannotProduceNegativeAge()throws Exception {order("PENDING_PAYMENT",100,-120);var n=overview();assertEquals(0,n.path("oldestPendingPaymentSeconds").asLong());assertEquals(0,n.path("overduePendingPayment").asLong());}
    @Test void oldNonPendingDoesNotInflatePendingAge()throws Exception {order("COMPLETED",100,10000);order("PENDING_PAYMENT",100,10);long age=overview().path("oldestPendingPaymentSeconds").asLong();assertTrue(age>=10&&age<30);}
    @Test void responseContainsOnlyAggregateFields()throws Exception {order("COMPLETED",100,60);var n=overview();assertEquals(Set.of("total","pendingPayment","paid","delivered","completed","cancelled","closedTimeout","completedAmountInCents","overduePendingPayment","oldestPendingPaymentSeconds","sampledAt"),new HashSet<>(n.propertyNames()));assertFalse(n.toString().contains("PRIVATE_"));assertFalse(n.toString().contains("trade_buyer"));}
    @Test void readsNeverCloseOrdersOrWriteEventsAndNotifications()throws Exception {long id=order("PENDING_PAYMENT",100,1200);var before=jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id);overview();overview();assertEquals(before,jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id));for(String table:List.of("order_events","order_timeout_outbox","notification_outbox","user_notifications"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));}
    @Test void revokedAdminLosesAccessImmediately()throws Exception {overview();jdbc.update("DELETE FROM user_role_grants WHERE user_id=101");assertEquals(403,response(admin).getStatus());}
    @Test void loggedOutAdminCannotRead()throws Exception {assertEquals(204,mvc.perform(post("/api/users/logout").header("Authorization",admin)).andReturn().getResponse().getStatus());assertEquals(401,response(admin).getStatus());}
    @Test void databaseFailureIsNotFalseZeroSuccess()throws Exception {jdbc.execute("RENAME TABLE market_orders TO market_orders_private_unavailable");try{var r=response(admin);assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));}finally{jdbc.execute("RENAME TABLE market_orders_private_unavailable TO market_orders");}}
    @Test void exactSafeIntegerBoundaryIsPreserved(){assertEquals(9007199254740991L,AdminOrderOverviewService.safeInteger(new BigDecimal("9007199254740991")));assertEquals(0,AdminOrderOverviewService.safeInteger(BigDecimal.ZERO));}
    @Test void fractionalNegativeAndOverflowNumbersAreRejected(){for(String n:List.of("-1","1.5","9007199254740992","9223372036854775808"))assertThrows(RuntimeException.class,()->AdminOrderOverviewService.safeInteger(new BigDecimal(n)));}
    @Test void globalCountsIncludeOrdersOfDifferentBuyers()throws Exception {long id=order("COMPLETED",3500,60);jdbc.update("UPDATE market_orders SET buyer_id=101 WHERE id=?",id);order("COMPLETED",5800,60);var n=overview();assertEquals(2,n.path("completed").asLong());assertEquals(9300,n.path("completedAmountInCents").asLong());}
}
