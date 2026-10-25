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
class OwnOrderSearchIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson111_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson111_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
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

    @Autowired OrderService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OrderRepository repository;
    MockHttpServletResponse search(String auth,String role,String status,String id,int page,int size)throws Exception{
        var r=get("/api/orders").param("role",role).param("status",status).param("orderId",id).param("page",""+page).param("size",""+size);
        if(auth!=null)r.header("Authorization",auth);
        return mvc.perform(r).andReturn().getResponse();
    }
    JsonNode node(MockHttpServletResponse r)throws Exception{assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode search(String id)throws Exception{return node(search(user,"BUYER","ALL",id,1,10));}
    @Test void anonymousCannotSearchEvenInvalidIds()throws Exception{assertEquals(401,search(null,"BUYER","ALL","01",1,10).getStatus());}
    @Test void revokedSessionCannotSearch()throws Exception{mvc.perform(post("/api/users/logout").header("Authorization",user));assertEquals(401,search(user,"BUYER","ALL","1",1,10).getStatus());}
    @Test void omittedFilterEchoesNullAndKeepsOldBehavior()throws Exception{order("PAID",100,60);var n=node(mvc.perform(get("/api/orders").header("Authorization",user)).andReturn().getResponse());assertTrue(n.path("orderId").isNull());assertEquals(1,n.path("total").asLong());}
    @Test void explicitEmptyFilterMeansAllOwnOrders()throws Exception{order("PAID",100,60);order("DELIVERED",100,60);assertEquals(2,search("").path("total").asLong());}
    @Test void exactOrderReturnsOnlyOwnMatch()throws Exception{long first=order("PAID",100,60);order("PAID",100,60);var n=search(""+first);assertEquals(first,n.path("orderId").asLong());assertEquals(1,n.path("total").asLong());assertEquals(first,n.at("/items/0/id").asLong());}
    @Test void unknownOrderIsEmptyWithOwnUnfilteredSummary()throws Exception{order("PAID",100,60);var n=search("999999");assertEquals(0,n.path("total").asLong());assertEquals(1,n.path("summary").path("total").asLong());assertEquals(0,n.path("items").size());}
    @Test void thirdPartyCannotFindAnExistingOrder()throws Exception{long id=order("PAID",100,60);for(String role:List.of("BUYER","SELLER")){var n=node(search(admin,role,"ALL",""+id,1,10));assertEquals(0,n.path("total").asLong());assertEquals(0,n.path("summary").path("total").asLong());}}
    @Test void foreignAndNonexistentResultsHaveSameVisibleBusinessShape()throws Exception{long id=order("PAID",100,60);var a=node(search(admin,"BUYER","ALL",""+id,1,10));var b=node(search(admin,"BUYER","ALL","999999",1,10));assertEquals(a.path("summary"),b.path("summary"));assertEquals(a.path("items"),b.path("items"));assertEquals(a.path("total"),b.path("total"));}
    @Test void sellerCanSearchItsOwnSale()throws Exception{long id=order("PAID",100,60);var n=node(search(seller,"SELLER","PAID",""+id,1,10));assertEquals(1,n.path("total").asLong());assertEquals("SELLER",n.path("role").asString());}
    @Test void wrongParticipantRoleStillReturnsEmpty()throws Exception{long id=order("PAID",100,60);assertEquals(0,node(search(seller,"BUYER","ALL",""+id,1,10)).path("total").asLong());}
    @Test void statusAndOrderIdAreAnIntersection()throws Exception{long id=order("COMPLETED",100,60);order("PAID",100,60);assertEquals(0,node(search(user,"BUYER","PAID",""+id,1,10)).path("total").asLong());assertEquals(1,node(search(user,"BUYER","COMPLETED",""+id,1,10)).path("total").asLong());}
    @Test void strictCanonicalIdRejectsWhitespaceAndNumericVariants()throws Exception{for(String id:List.of(" "," 1","1 ","0","01","-1","+1","1.0","1e0","１","9007199254740992","12345678901234567"))assertEquals(400,search(user,"BUYER","ALL",id,1,10).getStatus());}
    @Test void sqlTextCannotChangeScopeOrBusinessRows()throws Exception{order("PAID",100,60);for(String id:List.of("1 OR 1=1","1;DROP TABLE users","1' UNION SELECT 1"))assertEquals(400,search(user,"BUYER","ALL",id,1,10).getStatus());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM market_orders",Long.class));}
    @Test void largestSafeIntegerIsPreservedExactly()throws Exception{long id=order("PAID",100,60);jdbc.update("UPDATE market_orders SET id=9007199254740991 WHERE id=?",id);var n=search("9007199254740991");assertEquals(1,n.path("total").asLong());assertEquals(9007199254740991L,n.at("/items/0/id").asLong());}
    @Test void summaryAlwaysCoversTheWholeOwnRole()throws Exception{long id=order("COMPLETED",3500,60);order("PAID",100,60);var all=search("");var filtered=node(search(user,"BUYER","COMPLETED",""+id,1,10));assertEquals(all.path("summary"),filtered.path("summary"));assertEquals(2,filtered.path("summary").path("total").asLong());assertEquals(3500,filtered.path("summary").path("completedAmountInCents").asLong());}
    @Test void orderOutsideFirstPageIsFoundBeforePagination()throws Exception{long first=order("PAID",100,60);for(int n=0;n<12;n++)order("PAID",100,60);var result=search(""+first);assertEquals(1,result.path("total").asLong());assertEquals(first,result.at("/items/0/id").asLong());assertEquals(13,result.path("summary").path("total").asLong());}
    @Test void filteredOutOfRangePageClampsToOne()throws Exception{long id=order("PAID",100,60);var n=node(search(user,"BUYER","ALL",""+id,999999,1));assertEquals(1,n.path("page").asLong());assertEquals(1,n.path("totalPages").asLong());assertEquals(1,n.path("items").size());}
    @Test void emptyFilteredPageAlsoClampsToOne()throws Exception{var n=node(search(user,"BUYER","ALL","999999",999999,10));assertEquals(1,n.path("page").asLong());assertEquals(1,n.path("totalPages").asLong());assertEquals(0,n.path("items").size());}
    @Test void invalidRoleStatusAndPaginationStillReject()throws Exception{assertEquals(400,search(user,"ADMIN","ALL","",1,10).getStatus());assertEquals(400,search(user,"BUYER","BAD","",1,10).getStatus());assertEquals(400,search(user,"BUYER","ALL","",0,10).getStatus());assertEquals(400,search(user,"BUYER","ALL","",1,21).getStatus());}
    @Test void clientUserIdCannotChangeIdentity()throws Exception{long id=order("PAID",100,60);var n=node(mvc.perform(get("/api/orders").header("Authorization",admin).param("userId","103").param("orderId",""+id)).andReturn().getResponse());assertEquals(0,n.path("total").asLong());}
    @Test void oldServiceOverloadRemainsCompatible(){order("PAID",100,60);var n=service.page("BUYER","ALL",1,10,user);assertNull(n.orderId());assertEquals(1,n.total());}
    @Test void readsDoNotCloseExpiredOrWriteBusinessEvents()throws Exception{long id=order("PENDING_PAYMENT",100,1200);var before=jdbc.queryForList("SELECT * FROM market_orders");var products=jdbc.queryForList("SELECT * FROM products");search(""+id);search("");assertEquals(before,jdbc.queryForList("SELECT * FROM market_orders"));assertEquals(products,jdbc.queryForList("SELECT * FROM products"));for(String table:List.of("order_events","order_timeout_outbox","notification_outbox","user_notifications"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));}
    @Test void responseEchoIsMinimalAndDoesNotAddIdentityParameters()throws Exception{var n=search("");assertEquals(Set.of("items","total","page","size","totalPages","role","status","orderId","summary"),new HashSet<>(n.propertyNames()));assertFalse(n.has("userId"));assertFalse(n.has("requestId"));}
    @Test void databaseErrorIsNotAnEmptySuccess()throws Exception{jdbc.execute("RENAME TABLE market_orders TO private_unavailable_orders");try{var r=search(user,"BUYER","ALL","1",1,10);assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SQL"));}finally{jdbc.execute("RENAME TABLE private_unavailable_orders TO market_orders");}}
    @Test void stateChangeRemovesMatchOnNextQuery()throws Exception{long id=order("PAID",100,60);assertEquals(1,node(search(user,"BUYER","PAID",""+id,1,10)).path("total").asLong());jdbc.update("UPDATE market_orders SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);assertEquals(0,node(search(user,"BUYER","PAID",""+id,1,10)).path("total").asLong());}
    @Test void summaryCountAndRowsShareSnapshotDuringConcurrentProgress()throws Exception{
        long id=order("PAID",100,60);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return result;}).when(repository).summary(103,false);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var future=pool.submit(()->service.page("BUYER","PAID",""+id,1,10,user));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            jdbc.update("UPDATE market_orders SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
            release.countDown();var result=future.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,result.total());assertEquals(1,result.summary().paid());assertEquals("PAID",result.items().get(0).status());
            assertEquals(0,service.page("BUYER","PAID",""+id,1,10,user).total());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
