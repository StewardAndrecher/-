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
class ReceivedReviewIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson117_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson117_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired ReceivedReviewService received;@Autowired SessionService sessions;@Autowired OrderReviewService reviews;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
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
    MockHttpServletResponse received(String token,Map<String,String> query)throws Exception {
        var request=get("/api/orders/received-reviews");query.forEach(request::param);if(token!=null)request.header("Authorization",token);
        return mvc.perform(request).andReturn().getResponse();
    }
    JsonNode receivedPage(String token,String role,String rating,int number,int size)throws Exception {
        return node(received(token,Map.of("role",role,"rating",rating,"page",""+number,"size",""+size)),200);
    }
    JsonNode receivedPage(String token)throws Exception{return node(received(token,Map.of()),200);}
    void peer(long order,long author,int rating,String content){jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating,content) VALUES(?,?,?,?)",order,author,rating,content);}
    @Test void authenticationPrecedesEvenInvalidQuery()throws Exception {
        for(String token:Arrays.asList(null,"Bearer "+"A".repeat(43)))assertEquals(401,received(token,Map.of("userId","102","page","0")).getStatus());
    }
    @Test void expiredAndRevokedSessionsAre401()throws Exception {
        sessions.logout(buyer);assertEquals(401,received(buyer,Map.of()).getStatus());jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE user_id=102");assertEquals(401,received(seller,Map.of()).getStatus());
    }
    @Test void emptyDefaultsAreBuyerAllAndPrivate()throws Exception {
        var n=receivedPage(buyer);assertEquals(101,n.path("userId").asLong());assertEquals("BUYER",n.path("role").asString());assertEquals("ALL",n.path("rating").asString());assertEquals(0,n.path("total").asLong());assertEquals(1,n.path("totalPages").asLong());assertEquals(0,n.path("items").size());
    }
    @Test void ownReviewIsNotReceivedAndPeerReviewIs()throws Exception {
        peer(1,101,1,"我给对方");assertEquals(0,receivedPage(buyer).path("total").asLong());peer(1,102,5,"对方给我");var n=receivedPage(buyer);assertEquals(1,n.path("total").asLong());assertEquals("对方给我",n.at("/items/0/content").asString());assertEquals(5,n.at("/items/0/rating").asInt());
    }
    @Test void sellerReceivesBuyerReviewUnderSellerRole()throws Exception {
        peer(1,101,2,"买方评价");assertEquals(1,receivedPage(seller,"SELLER","LOW",1,10).path("total").asLong());assertEquals(0,receivedPage(seller).path("total").asLong());
    }
    @Test void outsidersCannotSeeEitherSide()throws Exception {
        peer(1,101,3,"私密一");peer(1,102,4,"私密二");for(String role:List.of("BUYER","SELLER"))assertEquals(0,receivedPage(outsider,role,"ALL",1,10).path("total").asLong());
    }
    @Test void unknownIdentityParameterCannotOverrideOwner()throws Exception {
        peer(1,102,5,"私密");var response=received(outsider,Map.of("userId","101"));assertEquals(400,response.getStatus());assertFalse(response.getContentAsString().contains("私密"));
    }
    @Test void receivedScopeUsesBothParticipantsNotAnyReviewOnOwnOrder()throws Exception {
        peer(1,103,5,"无关者伪造");assertEquals(0,receivedPage(buyer).path("total").asLong());assertEquals(0,receivedPage(seller,"SELLER","ALL",1,10).path("total").asLong());
    }
    @Test void incompleteOrdersDoNotCountEvenIfReviewExists()throws Exception {
        for(long order=2;order<=6;order++)peer(order,102,5,"非完成订单");assertEquals(0,receivedPage(buyer).path("total").asLong());
    }
    @Test void ratingBucketsPartitionAllReceivedReviews()throws Exception {
        for(int id=7;id<=10;id++)add(id,101,102);int[] ids={1,7,8,9,10};for(int i=0;i<5;i++)peer(ids[i],102,i+1,"分值样本");
        var n=receivedPage(buyer);assertEquals(5,n.at("/summary/total").asLong());assertEquals(2,n.at("/summary/good").asLong());assertEquals(1,n.at("/summary/neutral").asLong());assertEquals(2,n.at("/summary/low").asLong());
        assertEquals(2,receivedPage(buyer,"BUYER","GOOD",1,10).path("total").asLong());assertEquals(1,receivedPage(buyer,"BUYER","NEUTRAL",1,10).path("total").asLong());assertEquals(2,receivedPage(buyer,"BUYER","LOW",1,10).path("total").asLong());
    }
    @Test void roleSummaryDoesNotChangeWithRatingOrPage()throws Exception {
        add(7,101,102);peer(1,102,5,"");peer(7,102,2,"");assertEquals(receivedPage(buyer).path("summary"),receivedPage(buyer,"BUYER","GOOD",99,1).path("summary"));
    }
    @Test void filtersApplyBeforePagingWithLastPageClamp()throws Exception {
        for(int id=7;id<=18;id++){add(id,101,102);peer(id,102,id%2==0?5:2,"");}
        var p=receivedPage(buyer,"BUYER","GOOD",2,2);assertEquals(6,p.path("total").asLong());assertEquals(2,p.path("items").size());assertEquals(14,p.at("/items/0/orderId").asLong());
        var last=receivedPage(buyer,"BUYER","GOOD",1000000,2);assertEquals(3,last.path("page").asInt());assertEquals(10,last.at("/items/0/orderId").asLong());
    }
    @Test void emptyRatingFilterClampsWithoutLosingWholeRoleSummary()throws Exception {
        peer(1,102,5,"");var n=receivedPage(buyer,"BUYER","LOW",123,10);assertEquals(0,n.path("total").asLong());assertEquals(1,n.path("page").asInt());assertEquals(1,n.at("/summary/total").asLong());
    }
    @Test void equalReviewTimesSortByDescendingOrderId()throws Exception {
        add(7,101,102);add(8,101,102);for(long id:List.of(1L,7L,8L))peer(id,102,5,"");jdbc.update("UPDATE order_reviews SET created_at=CURRENT_TIMESTAMP-INTERVAL 1 MINUTE");
        assertEquals(List.of(8L,7L,1L),received.page(buyer,Map.of()).items().stream().map(ReceivedReviewService.Entry::orderId).toList());
    }
    @Test void reviewTimePrecedesOrderIdInSort()throws Exception {
        add(7,101,102);peer(1,102,5,"");peer(7,102,5,"");jdbc.update("UPDATE order_reviews SET created_at=CURRENT_TIMESTAMP-INTERVAL 1 MINUTE WHERE order_id=7");assertEquals(1,receivedPage(buyer).at("/items/0/orderId").asLong());
    }
    @Test void unknownDuplicateEmptyAndNonCanonicalParametersAre400()throws Exception {
        for(Map<String,String> q:List.of(Map.of("unknown","x"),Map.of("rating",""),Map.of("role","buyer"),Map.of("rating","all"),Map.of("rating","GOOD' OR 1=1"),Map.of("page","01"),Map.of("page","+1"),Map.of("page","1.0"),Map.of("page","1e1"),Map.of("page","0"),Map.of("page","1000001"),Map.of("size","21"),Map.of("size","-1")))assertEquals(400,received(buyer,q).getStatus());
        for(String key:List.of("page","size","role","rating"))assertEquals(400,mvc.perform(get("/api/orders/received-reviews").header("Authorization",buyer).param(key,key.equals("role")?"BUYER":key.equals("rating")?"ALL":"1",key.equals("role")?"SELLER":key.equals("rating")?"GOOD":"2")).andReturn().getResponse().getStatus());
    }
    @Test void minimalFieldsAndNoPeerIdentityOrSecrets()throws Exception {
        peer(1,102,4,"真实文字");var response=received(buyer,Map.of());var n=node(response,200);assertEquals("no-cache",response.getHeader("Pragma"));
        assertEquals(Set.of("userId","role","rating","page","size","total","totalPages","summary","items","sampledAt"),new HashSet<>(n.propertyNames()));
        assertEquals(Set.of("orderId","productTitleSnapshot","rating","content","completedAt","reviewedAt"),new HashSet<>(n.path("items").get(0).propertyNames()));
        for(String secret:List.of("reviewerId","sellerId","buyerId","nickname","PRIVATE_HASH",buyer.substring(7),seller.substring(7)))assertFalse(n.toString().contains(secret));
    }
    @Test void titleUsesOrderSnapshotAfterProductEdit()throws Exception {
        peer(1,102,5,"");jdbc.update("UPDATE products SET title='修改后的新标题' WHERE id=1");assertEquals("隔离评价样本",receivedPage(buyer).at("/items/0/productTitleSnapshot").asString());
    }
    @Test void unicodeAndPlainTextArePreserved()throws Exception {
        String value="<script>alert(1)</script>\n中文 😀\t备注";peer(1,102,5,value);assertEquals(value,receivedPage(buyer).at("/items/0/content").asString());
    }
    @Test void readsWriteNoBusinessRecordsOrNotifications()throws Exception {
        peer(1,102,5,"");var before=stable();receivedPage(buyer);receivedPage(seller,"SELLER","ALL",1,10);receivedPage(buyer,"BUYER","LOW",99,20);assertEquals(before,stable());
    }
    Map<String,List<Map<String,Object>>> stable(){var value=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("market_orders","order_reviews","products","order_events","notification_outbox","user_notifications","login_sessions"))value.put(table,jdbc.queryForList("SELECT * FROM "+table));return value;}
    @Test void missingTableFails500NotFalseEmptySuccess()throws Exception {
        jdbc.execute("RENAME TABLE order_reviews TO order_reviews_private_unavailable");try{assertEquals(500,received(buyer,Map.of()).getStatus());}finally{jdbc.execute("RENAME TABLE order_reviews_private_unavailable TO order_reviews");}
    }
    @Test void invalidTimestampFails503()throws Exception {
        peer(1,102,5,"");jdbc.update("UPDATE order_reviews SET created_at=CURRENT_TIMESTAMP-INTERVAL 30 MINUTE");assertEquals(503,received(buyer,Map.of()).getStatus());
    }
    @Test void futureTimestampFails503()throws Exception {
        peer(1,102,5,"");jdbc.update("UPDATE order_reviews SET created_at=CURRENT_TIMESTAMP+INTERVAL 1 DAY");assertEquals(503,received(buyer,Map.of()).getStatus());
    }
    @Test void nonCanonicalOrControlTextFails503()throws Exception {
        peer(1,102,5,"  边缘空格  ");assertEquals(503,received(buyer,Map.of()).getStatus());jdbc.update("UPDATE order_reviews SET content=?","控制"+(char)1+"字符");assertEquals(503,received(buyer,Map.of()).getStatus());
    }
    @Test void existingCompletedSummaryAndCreateStayCompatible()throws Exception {
        node(submit(seller,5,"对方通过现有接口提交"),201);assertEquals(1,receivedPage(buyer).path("total").asLong());node(submit(seller,5,"对方通过现有接口提交"),200);assertEquals(1,receivedPage(buyer).path("total").asLong());assertEquals(1,taskCount());
    }
    @Test void repeatableReadRetainsSnapshotAcrossConcurrentPeerReview()throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{assertEquals(0,received.page(buyer,Map.of()).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->reviews.create("1",new OrderReviewModels.Create(5L,"并发对方评价"),seller)).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,received.page(buyer,Map.of()).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});
        assertEquals(1,receivedPage(buyer).path("total").asLong());
    }
}
