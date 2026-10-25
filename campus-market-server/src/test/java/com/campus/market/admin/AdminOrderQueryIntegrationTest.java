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
class AdminOrderQueryIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson97_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson97_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired AdminOrderQueryService service;
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
    JsonNode page(String query)throws Exception {
        var r=mvc.perform(get("/api/admin/orders"+query).header("Authorization",admin)).andReturn().getResponse();
        assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());
    }
    int code(String query,String auth)throws Exception {var r=get("/api/admin/orders"+query);if(auth!=null)r.header("Authorization",auth);return mvc.perform(r).andReturn().getResponse().getStatus();}
    @Test void anonymousIs401()throws Exception {assertEquals(401,code("",null));}
    @Test void invalidSessionIs401()throws Exception {assertEquals(401,code("","Bearer invalid"));}
    @Test void ordinaryAccountIs403()throws Exception {assertEquals(403,code("",user));}
    @Test void emptyResponseHasOnePageAndExplicitQueryEcho()throws Exception {var n=page("");assertEquals(0,n.path("total").asLong());assertEquals(1,n.path("page").asInt());assertEquals(1,n.path("totalPages").asLong());assertEquals("ALL",n.path("status").asString());assertTrue(n.path("orderId").isNull());assertEquals(0,n.path("records").size());assertFalse(n.path("sampledAt").asString().isBlank());}
    @Test void allStatesAreVisibleWithoutParticipantData()throws Exception {
        for(String state:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(state,3500,1200);
        var n=page("");assertEquals(6,n.path("total").asLong());assertEquals(6,n.path("records").size());
        for(var row:n.path("records")){assertEquals(Set.of("id","productId","productTitleSnapshot","priceInCentsSnapshot","status","createdAt","paymentDueAt"),new HashSet<>(row.propertyNames()));assertFalse(row.toString().contains("PRIVATE_BUYER"));assertFalse(row.toString().contains("PRIVATE_SELLER"));}
    }
    @Test void eachOfSixStatusesIsFilteredInSql()throws Exception {var states=List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT");for(String s:states)order(s,100,1200);for(String s:states){var n=page("?status="+s);assertEquals(1,n.path("total").asLong());assertEquals(s,n.path("records").get(0).path("status").asString());}}
    @Test void overdueFilterIncludesOnlyDuePending()throws Exception {for(String s:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT"))order(s,100,1200);order("PENDING_PAYMENT",100,60);var n=page("?status=OVERDUE_PENDING");assertEquals(1,n.path("total").asLong());assertEquals("PENDING_PAYMENT",n.path("records").get(0).path("status").asString());}
    @Test void exactDueTimeCountsAsOverdue()throws Exception {order("PENDING_PAYMENT",100,900);assertEquals(1,page("?status=OVERDUE_PENDING").path("total").asLong());}
    @Test void futurePendingIsNotOverdue()throws Exception {order("PENDING_PAYMENT",100,-60);assertEquals(0,page("?status=OVERDUE_PENDING").path("total").asLong());}
    @Test void exactIdReturnsOnlyMatchingOrderAndEcho()throws Exception {long id=order("PAID",3500,60);order("COMPLETED",9999,60);var n=page("?orderId="+id);assertEquals(1,n.path("total").asLong());assertEquals(id,n.path("orderId").asLong());assertEquals(id,n.path("records").get(0).path("id").asLong());}
    @Test void idAndStatusUseIntersection()throws Exception {long id=order("PAID",100,60);assertEquals(0,page("?orderId="+id+"&status=COMPLETED").path("total").asLong());assertEquals(1,page("?orderId="+id+"&status=PAID").path("total").asLong());}
    @Test void missingIdIsEmptyQueryInsteadOfResource404()throws Exception {assertEquals(0,page("?orderId=9007199254740991").path("total").asLong());}
    @Test void invalidStatusOrInjectionIsRejected()throws Exception {for(String status:List.of("paid","UNKNOWN","ALL ","ALL' OR 1=1")){var r=mvc.perform(get("/api/admin/orders").param("status",status).header("Authorization",admin)).andReturn().getResponse();assertEquals(400,r.getStatus());}}
    @Test void idMustBeCanonicalSafePositiveIntegerText()throws Exception {for(String id:List.of("0","-1","01","1.0","1e3","true","null"," 1","9007199254740992","99999999999999999","1 OR 1=1")){var r=mvc.perform(get("/api/admin/orders").param("orderId",id).header("Authorization",admin)).andReturn().getResponse();assertEquals(400,r.getStatus(),id);}}
    @Test void invalidPaginationAndOverflowAre400()throws Exception {for(String q:List.of("?page=0","?page=-1","?size=0","?size=21","?page=2147483648","?page=1.5"))assertEquals(400,code(q,admin),q);}
    @Test void sizeTwentyReturnsAtMostTwentyRows()throws Exception {for(int i=0;i<21;i++)order("PENDING_PAYMENT",100,60);var n=page("?size=20");assertEquals(21,n.path("total").asLong());assertEquals(20,n.path("records").size());assertEquals(2,n.path("totalPages").asLong());}
    @Test void equalTimesSortByIdDescendingAcrossPages()throws Exception {
        for(int i=0;i<12;i++)order("PENDING_PAYMENT",100,60);jdbc.update("UPDATE market_orders SET created_at='2026-01-01 10:00:00'");
        var one=page("?page=1");var two=page("?page=2");long previous=Long.MAX_VALUE;var ids=new HashSet<Long>();
        for(var n:List.of(one,two))for(var row:n.path("records")){long id=row.path("id").asLong();assertTrue(id<previous);assertTrue(ids.add(id));previous=id;}assertEquals(12,ids.size());
    }
    @Test void pageClampsToLastPageIncludingEmptyResults()throws Exception {for(int i=0;i<12;i++)order("PAID",100,60);assertEquals(2,page("?page=2147483647").path("page").asInt());assertEquals(1,page("?status=COMPLETED&page=2147483647").path("page").asInt());}
    @Test void filterTotalCountsAllPages()throws Exception {for(int i=0;i<12;i++)order("PAID",100,60);order("COMPLETED",100,60);var n=page("?status=PAID&page=2");assertEquals(12,n.path("total").asLong());assertEquals(2,n.path("records").size());}
    @Test void readingExpiredOrdersDoesNotCloseOrWriteAnything()throws Exception {long id=order("PENDING_PAYMENT",100,1200);var before=jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id);page("?status=OVERDUE_PENDING");page("");assertEquals(before,jdbc.queryForMap("SELECT * FROM market_orders WHERE id=?",id));for(String t:List.of("order_events","order_timeout_outbox","notification_outbox","user_notifications"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));}
    @Test void revokedAdminLosesAccessImmediately()throws Exception {page("");jdbc.update("DELETE FROM user_role_grants WHERE user_id=101");assertEquals(403,code("",admin));}
    @Test void loggedOutAdminCannotRead()throws Exception {assertEquals(204,mvc.perform(post("/api/users/logout").header("Authorization",admin)).andReturn().getResponse().getStatus());assertEquals(401,code("",admin));}
    @Test void databaseFailureDoesNotBecomeEmptySuccess()throws Exception {jdbc.execute("RENAME TABLE market_orders TO market_orders_private_unavailable");try{var r=mvc.perform(get("/api/admin/orders").header("Authorization",admin)).andReturn().getResponse();assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));}finally{jdbc.execute("RENAME TABLE market_orders_private_unavailable TO market_orders");}}
    @Test void countAndRowsUseRepeatableReadSnapshot()throws Exception {
        long id=order("PENDING_PAYMENT",100,60);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.executeWithoutResult(state->{var first=service.page("PENDING_PAYMENT","",1,10);assertEquals(1,first.total());
                try{pool.submit(()->jdbc.update("UPDATE market_orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",id)).get(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                var same=service.page("PENDING_PAYMENT","",1,10);assertEquals(1,same.total());assertEquals("PENDING_PAYMENT",same.records().get(0).status());
            });assertEquals(0,service.page("PENDING_PAYMENT","",1,10).total());
        }finally{pool.shutdownNow();}
    }
    @Test void displayedPriceAndTitleAreOrderSnapshots()throws Exception {long id=order("PAID",3500,60);jdbc.update("UPDATE products SET title='CURRENT_TITLE',price_in_cents=9999 WHERE id=?",id);var row=page("?orderId="+id).path("records").get(0);assertEquals("PRIVATE_TITLE",row.path("productTitleSnapshot").asString());assertEquals(3500,row.path("priceInCentsSnapshot").asLong());}
}
