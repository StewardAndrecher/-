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
class OrderReviewTaskIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson103_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson103_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired OrderReviewTaskService tasks;@Autowired SessionService sessions;@Autowired OrderReviewService reviews;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired javax.sql.DataSource source;
    final JsonMapper json=JsonMapper.builder().build();String buyer,seller,outsider;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_reviews","user_notifications","notification_outbox","order_events","order_timeout_outbox","market_orders","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'review_buyer','PRIVATE_HASH','甲'),(102,'review_seller','PRIVATE_HASH','乙'),(103,'review_outsider','PRIVATE_HASH','丙')");
        String[] states={"COMPLETED","PAID","PENDING_PAYMENT","DELIVERED","CANCELLED","CLOSED_TIMEOUT"};
        for(int i=0;i<states.length;i++){
            long id=i+1;String state=states[i];boolean paid=Set.of("PAID","DELIVERED","COMPLETED").contains(state),delivered=Set.of("DELIVERED","COMPLETED").contains(state);
            jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status) VALUES(?,'隔离评价样本','只用于本课测试',100,102,?)",id,paid?"SOLD":"ON_SALE");
            String created="CURRENT_TIMESTAMP(6)-INTERVAL 20 MINUTE";
            String query="INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,cancelled_at,paid_at,closed_at,payment_kind,delivered_at,completed_at) VALUES(?,?,101,102,?,'隔离评价样本',100,0,'甲','乙',?,"+created+","+(state.equals("CANCELLED")?"CURRENT_TIMESTAMP(6)-INTERVAL 19 MINUTE":"NULL")+","+(paid?"CURRENT_TIMESTAMP(6)-INTERVAL 19 MINUTE":"NULL")+","+(state.equals("CLOSED_TIMEOUT")?"CURRENT_TIMESTAMP(6)-INTERVAL 4 MINUTE":"NULL")+","+(paid?"'SIMULATED'":"NULL")+","+(delivered?"CURRENT_TIMESTAMP(6)-INTERVAL 18 MINUTE":"NULL")+","+(state.equals("COMPLETED")?"CURRENT_TIMESTAMP(6)-INTERVAL 17 MINUTE":"NULL")+")";
            jdbc.update(query,id,id,UUID.randomUUID().toString(),state);
        }
        buyer=issue(101);seller=issue(102);outsider=issue(103);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"review_"+id,"隔离验收")).accessToken();}
    MockHttpServletResponse getReviews(String id,String token)throws Exception {var r=get("/api/orders/"+id+"/reviews");if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    MockHttpServletResponse postReview(String id,String token,String body)throws Exception {var r=post("/api/orders/"+id+"/reviews").contentType("application/json").content(body);if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    MockHttpServletResponse submit(String token,int rating,String content)throws Exception {return postReview("1",token,json.writeValueAsString(Map.of("rating",rating,"content",content)));}
    JsonNode node(MockHttpServletResponse response,int expected)throws Exception {assertEquals(expected,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));return json.readTree(response.getContentAsByteArray());}
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM order_reviews",Long.class);}
    MockHttpServletResponse list(String token,String role,String status,int page,int size)throws Exception {
        var request=get("/api/orders/review-tasks").param("role",role).param("status",status).param("page",""+page).param("size",""+size);
        if(token!=null)request.header("Authorization",token);return mvc.perform(request).andReturn().getResponse();
    }
    JsonNode page(String token,String role,String status,int number,int size)throws Exception{return node(list(token,role,status,number,size),200);}
    JsonNode pending(String token)throws Exception{return page(token,"BUYER","PENDING",1,10);}
    void add(long id,long buyerId,long sellerId){jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status) VALUES(?,'隔离评价样本','分页夹具',100,?,'SOLD')",id,sellerId);jdbc.update("INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,paid_at,payment_kind,delivered_at,completed_at) SELECT ?,?,?,?,?,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,'甲','乙','COMPLETED',created_at,paid_at,payment_kind,delivered_at,completed_at FROM market_orders WHERE id=1",id,id,buyerId,sellerId,UUID.randomUUID().toString());}
    long taskCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class);}
    @Test void anonymousAndInvalidSessionsCannotList()throws Exception {assertEquals(401,list(null,"BUYER","PENDING",1,10).getStatus());assertEquals(401,list("Bearer "+"A".repeat(43),"BUYER","PENDING",1,10).getStatus());}
    @Test void defaultsArePrivateBuyerPending()throws Exception {var r=mvc.perform(get("/api/orders/review-tasks").header("Authorization",buyer)).andReturn().getResponse();var n=node(r,200);assertEquals("BUYER",n.path("role").asString());assertEquals("PENDING",n.path("status").asString());assertEquals(101,n.path("userId").asLong());assertEquals(1,n.path("total").asLong());}
    @Test void onlyCompletedOrdersEnterSummaryAndResults()throws Exception {var n=pending(buyer);assertEquals(1,n.path("total").asLong());assertEquals(1,n.at("/summary/total").asLong());assertEquals(1,n.at("/items/0/orderId").asLong());}
    @Test void outsiderCannotSeeParticipantsTasks()throws Exception {var n=pending(outsider);assertEquals(0,n.path("total").asLong());assertEquals(0,n.path("items").size());assertEquals(1,n.path("page").asInt());assertEquals(1,n.path("totalPages").asInt());}
    @Test void sellerAndBuyerUseDifferentScopes()throws Exception {add(7,103,102);assertEquals(1,pending(buyer).path("total").asLong());assertEquals(2,page(seller,"SELLER","PENDING",1,10).path("total").asLong());assertEquals(0,page(seller,"BUYER","PENDING",1,10).path("total").asLong());assertEquals(0,page(buyer,"SELLER","PENDING",1,10).path("total").asLong());}
    @Test void peerReviewDoesNotRemoveMyPendingTask()throws Exception {node(submit(seller,4,"对方已评价"),201);assertEquals(1,pending(buyer).path("total").asLong());assertEquals(0,page(seller,"SELLER","PENDING",1,10).path("total").asLong());assertEquals(1,page(seller,"SELLER","REVIEWED",1,10).path("total").asLong());}
    @Test void ownReviewMovesOneTaskWithoutChangingCompletedTotal()throws Exception {submit(buyer,5,"好评");var p=pending(buyer);assertEquals(0,p.path("total").asLong());assertEquals(1,p.at("/summary/total").asLong());assertEquals(1,p.at("/summary/reviewed").asLong());var done=page(buyer,"BUYER","REVIEWED",1,10);assertEquals(1,done.path("total").asLong());assertFalse(done.at("/items/0/reviewedAt").isNull());}
    @Test void replayDoesNotDuplicateTaskOrCounter()throws Exception {submit(buyer,5,"");var before=page(buyer,"BUYER","REVIEWED",1,10);node(submit(buyer,5,""),200);assertEquals(before,page(buyer,"BUYER","REVIEWED",1,10));assertEquals(1,taskCount());}
    @Test void peerReviewIsNotExposedInReviewedTime()throws Exception {submit(seller,5,"秘密文字");assertTrue(pending(buyer).at("/items/0/reviewedAt").isNull());}
    @Test void forgedUserParameterCannotChangeIdentity()throws Exception {var r=mvc.perform(get("/api/orders/review-tasks").param("userId","102").header("Authorization",outsider)).andReturn().getResponse();var n=node(r,200);assertEquals(103,n.path("userId").asLong());assertEquals(0,n.path("total").asLong());}
    @Test void rolesAndStatusUseExactWhitelist()throws Exception {for(String r:List.of("buyer","ADMIN","BUYER' OR 1=1","BUYER "))assertEquals(400,list(buyer,r,"PENDING",1,10).getStatus());for(String v:List.of("ALL","pending","COMPLETED","REVIEWED' OR 1=1"))assertEquals(400,list(buyer,"BUYER",v,1,10).getStatus());}
    @Test void invalidPageAndSizeAreRejected()throws Exception {for(int n:new int[]{0,-1,1000001})assertEquals(400,list(buyer,"BUYER","PENDING",n,10).getStatus());for(int n:new int[]{0,-1,21})assertEquals(400,list(buyer,"BUYER","PENDING",1,n).getStatus());}
    @Test void nonIntegerPagingParametersAre400()throws Exception {for(String value:List.of("1.0","true","9999999999999999"))assertEquals(400,mvc.perform(get("/api/orders/review-tasks").param("page",value).header("Authorization",buyer)).andReturn().getResponse().getStatus());}
    @Test void sqlFiltersBeforePaginationAndSummaryCoversAllPages()throws Exception {for(int id=7;id<=18;id++)add(id,101,102);for(int id=7;id<=16;id++)jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating) VALUES(?,101,5)",id);var n=page(buyer,"BUYER","PENDING",1,2);assertEquals(3,n.path("total").asLong());assertEquals(13,n.at("/summary/total").asLong());assertEquals(10,n.at("/summary/reviewed").asLong());assertEquals(18,n.at("/items/0/orderId").asLong());assertEquals(17,n.at("/items/1/orderId").asLong());var last=page(buyer,"BUYER","PENDING",2,2);assertEquals(1,last.path("items").size());assertEquals(1,last.at("/items/0/orderId").asLong());assertEquals(n.path("summary"),last.path("summary"));}
    @Test void equalCompletionTimesUseDescendingId()throws Exception {add(7,101,102);add(8,101,102);var n=pending(buyer);assertEquals(List.of(8L,7L,1L),new ArrayList<>(tasks.page("BUYER","PENDING",1,10,buyer).items().stream().map(OrderReviewTaskModels.Entry::orderId).toList()));}
    @Test void newerCompletionPrecedesHigherOrderId()throws Exception {add(7,101,102);jdbc.update("UPDATE market_orders SET completed_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE id=1");assertEquals(1,pending(buyer).at("/items/0/orderId").asLong());}
    @Test void pageClampsAfterReviewRemovesLastPendingRow()throws Exception {add(7,101,102);assertEquals(2,page(buyer,"BUYER","PENDING",2,1).path("page").asInt());submit(buyer,5,"");var n=page(buyer,"BUYER","PENDING",2,1);assertEquals(1,n.path("page").asInt());assertEquals(7,n.at("/items/0/orderId").asLong());}
    @Test void emptyReviewedFilterKeepsPendingSummaryAndClamps()throws Exception {var n=page(buyer,"BUYER","REVIEWED",99,10);assertEquals(0,n.path("items").size());assertEquals(1,n.path("page").asInt());assertEquals(1,n.at("/summary/pending").asLong());}
    @Test void minimalShapeContainsNoPeerOrReviewContent()throws Exception {submit(buyer,3,"不能泄露的私密文字");var n=page(buyer,"BUYER","REVIEWED",1,10);assertEquals(Set.of("userId","role","status","page","size","total","totalPages","summary","items"),new HashSet<>(n.propertyNames()));assertEquals(Set.of("orderId","productTitleSnapshot","priceInCentsSnapshot","completedAt","reviewedAt"),new HashSet<>(n.path("items").get(0).propertyNames()));for(String secret:List.of("不能泄露","reviewerId","sellerId","buyerId","rating","password_hash","PRIVATE_HASH"))assertFalse(n.toString().contains(secret));}
    @Test void listUsesOrderSnapshotsNotCurrentProduct()throws Exception {jdbc.update("UPDATE products SET title='之后变更标题',price_in_cents=9999 WHERE id=1");var n=pending(buyer);assertEquals("隔离评价样本",n.at("/items/0/productTitleSnapshot").asString());assertEquals(100,n.at("/items/0/priceInCentsSnapshot").asLong());}
    @Test void readsDoNotWriteBusinessTablesOrNotificationJobs()throws Exception {submit(buyer,5,"");var before=stable();pending(buyer);page(buyer,"BUYER","REVIEWED",1,10);page(seller,"SELLER","PENDING",1,10);assertEquals(before,stable());}
    Map<String,List<Map<String,Object>>> stable(){var map=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("order_reviews","market_orders","products","order_events","notification_outbox","user_notifications"))map.put(table,jdbc.queryForList("SELECT * FROM "+table));return map;}
    @Test void missingReviewTableIs500NotFalsePendingList()throws Exception {jdbc.execute("RENAME TABLE order_reviews TO order_reviews_private_unavailable");try{assertEquals(500,list(buyer,"BUYER","PENDING",1,10).getStatus());}finally{jdbc.execute("RENAME TABLE order_reviews_private_unavailable TO order_reviews");}}
    @Test void malformedReviewTimestampIs503()throws Exception {jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating,created_at) VALUES(1,101,5,CURRENT_TIMESTAMP-INTERVAL 30 MINUTE)");assertEquals(503,list(buyer,"BUYER","REVIEWED",1,10).getStatus());}
    @Test void revokedSessionCannotReadPreviouslyVisibleList()throws Exception {pending(buyer);sessions.logout(buyer);assertEquals(401,list(buyer,"BUYER","PENDING",1,10).getStatus());}
    @Test void repeatableReadSnapshotSurvivesConcurrentReviewCommit()throws Exception {var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(1,tasks.page("BUYER","PENDING",1,10,buyer).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->reviews.create("1",new OrderReviewModels.Create(5L,"并发评价"),buyer)).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,tasks.page("BUYER","PENDING",1,10,buyer).total());assertEquals(0,tasks.page("BUYER","REVIEWED",1,10,buyer).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(0,tasks.page("BUYER","PENDING",1,10,buyer).total());assertEquals(1,tasks.page("BUYER","REVIEWED",1,10,buyer).total());}
}
