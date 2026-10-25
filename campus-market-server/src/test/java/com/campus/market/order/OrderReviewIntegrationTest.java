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
class OrderReviewIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson101_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson101_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired SessionService sessions;@Autowired OrderReviewService reviews;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
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
    @Test void anonymousReadAndWriteAre401()throws Exception {assertEquals(401,getReviews("1",null).getStatus());assertEquals(401,submit(null,5,"好评").getStatus());assertEquals(0,count());}
    @Test void outsiderAndMissingOrdersHaveSame404()throws Exception {for(String id:List.of("1","999")){assertEquals(404,getReviews(id,outsider).getStatus());assertEquals(404,postReview(id,outsider,"{\"rating\":5}").getStatus());}assertEquals(0,count());}
    @Test void invalidIdsAre400BeforeQueryingOtherOrders()throws Exception {for(String id:List.of("0","01","-1","1.0","1e1","9007199254740992"))assertEquals(400,getReviews(id,buyer).getStatus());}
    @Test void revokedAndExpiredSessionsCannotReview()throws Exception {sessions.logout(buyer);assertEquals(401,submit(buyer,5,"").getStatus());jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP WHERE user_id=102");assertEquals(401,submit(seller,5,"").getStatus());assertEquals(0,count());}
    @Test void completedOrderInitiallyAllowsEachParticipant()throws Exception {for(String token:List.of(buyer,seller)){var n=node(getReviews("1",token),200);assertTrue(n.path("canReview").asBoolean());assertEquals(0,n.path("reviews").size());}assertEquals(0,count());}
    @Test void otherFiveStatesCannotBeReviewed()throws Exception {for(int i=2;i<=6;i++){var n=node(getReviews(""+i,buyer),200);assertFalse(n.path("canReview").asBoolean());assertEquals(409,postReview(""+i,buyer,"{\"rating\":5}").getStatus());}assertEquals(0,count());}
    @Test void buyerFirstReviewIs201AndVisibleToBoth()throws Exception {var n=node(submit(buyer,5,"交接顺利"),201);assertFalse(n.path("replayed").asBoolean());assertFalse(n.path("summary").path("canReview").asBoolean());var peer=node(getReviews("1",seller),200);assertTrue(peer.path("canReview").asBoolean());assertEquals("BUYER",peer.path("reviews").get(0).path("authorRole").asString());assertFalse(peer.path("reviews").get(0).path("mine").asBoolean());}
    @Test void bothParticipantsCanLeaveExactlyOneReview()throws Exception {node(submit(buyer,5,"好评"),201);node(submit(seller,4,"准时"),201);assertEquals(2,count());for(String token:List.of(buyer,seller)){var n=node(getReviews("1",token),200);assertFalse(n.path("canReview").asBoolean());assertEquals(2,n.path("reviews").size());}}
    @Test void samePayloadReplays200AndPreservesTimestamp()throws Exception {var first=node(submit(buyer,5,"好评"),201);var before=jdbc.queryForList("SELECT * FROM order_reviews");var second=node(submit(buyer,5,"好评"),200);assertTrue(second.path("replayed").asBoolean());assertEquals(first.path("summary"),second.path("summary"));assertEquals(before,jdbc.queryForList("SELECT * FROM order_reviews"));}
    @Test void differentRatingOrContentIs409AndNeverOverwrites()throws Exception {submit(buyer,5,"好评");var before=jdbc.queryForList("SELECT * FROM order_reviews");assertEquals(409,submit(buyer,4,"好评").getStatus());assertEquals(409,submit(buyer,5,"不同").getStatus());assertEquals(before,jdbc.queryForList("SELECT * FROM order_reviews"));}
    @Test void canonicalWhitespaceAndLineEndingsReplay()throws Exception {node(submit(buyer,5," \r\n交接\r\n顺利\r\n "),201);assertTrue(node(submit(buyer,5,"交接\n顺利"),200).path("replayed").asBoolean());}
    @Test void unicodeEdgeWhitespaceNormalizesForClients()throws Exception {var n=node(submit(buyer,5,"\u00a0\ufeff好评\u3000"),201);assertEquals("好评",n.at("/summary/reviews/0/content").asString());}
    @Test void emptyOrOmittedTextIsAllowed()throws Exception {node(postReview("1",buyer,"{\"rating\":5}"),201);assertEquals("",node(getReviews("1",buyer),200).path("reviews").get(0).path("content").asString());}
    @Test void ratingsMustBeStrictJsonIntegersInRange()throws Exception {for(String value:List.of("0","6","-1","5.0","\"5\"","true","null","999999999999999999999"))assertEquals(400,postReview("1",buyer,"{\"rating\":"+value+"}").getStatus());assertEquals(400,postReview("1",buyer,"{}").getStatus());assertEquals(0,count());}
    @Test void maximumUnicodeLengthAcceptedAndOverflowRejected()throws Exception {assertEquals(400,submit(buyer,5,"😀".repeat(501)).getStatus());node(submit(buyer,5,"😀".repeat(500)),201);}
    @Test void controlCharactersRejectedButNewlinesAndTabsAccepted()throws Exception {assertEquals(400,submit(buyer,5,"坏\u0000字符").getStatus());node(submit(buyer,5,"第一行\n\t第二行"),201);}
    @Test void suppliedIdentityCannotForgeSellerReview()throws Exception {node(postReview("1",buyer,"{\"rating\":5,\"content\":\"隔离\",\"reviewerId\":102,\"authorRole\":\"SELLER\"}"),201);assertEquals(101,jdbc.queryForObject("SELECT reviewer_id FROM order_reviews",Long.class));assertEquals("BUYER",node(getReviews("1",buyer),200).path("reviews").get(0).path("authorRole").asString());}
    @Test void responsesHaveExactMinimalShapesAndNoCounterpartyIdentity()throws Exception {var r=node(submit(buyer,5,"好评"),201);assertEquals(Set.of("summary","replayed"),new HashSet<>(r.propertyNames()));var n=r.path("summary");assertEquals(Set.of("orderId","userId","orderStatus","viewerRole","canReview","reviews"),new HashSet<>(n.propertyNames()));assertEquals(Set.of("authorRole","rating","content","createdAt","mine"),new HashSet<>(n.path("reviews").get(0).propertyNames()));String text=n.toString();for(String secret:List.of("reviewerId","review_seller","PRIVATE_HASH",seller.substring(7),"nickname"))assertFalse(text.contains(secret));}
    @Test void readingIsReadOnly()throws Exception {submit(buyer,5,"好评");var before=jdbc.queryForList("SELECT * FROM order_reviews");node(getReviews("1",buyer),200);node(getReviews("1",seller),200);assertEquals(before,jdbc.queryForList("SELECT * FROM order_reviews"));}
    @Test void reviewOnlyAddsRecipientNotificationWithoutChangingOrderProductOrEvents()throws Exception {var orders=jdbc.queryForList("SELECT * FROM market_orders");var products=jdbc.queryForList("SELECT * FROM products");submit(buyer,5,"好评");assertEquals(orders,jdbc.queryForList("SELECT * FROM market_orders"));assertEquals(products,jdbc.queryForList("SELECT * FROM products"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_events",Long.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class));}
    @Test void outerTransactionCanRollbackReview(){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{reviews.create("1",new OrderReviewModels.Create(5L,"好评"),buyer);tx.setRollbackOnly();});assertEquals(0,count());}
    @Test void insertFailureReturns500AndDoesNotPartiallyWrite()throws Exception {jdbc.execute("CREATE TRIGGER private_review_failure BEFORE INSERT ON order_reviews FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private test failure'");try{assertEquals(500,submit(buyer,5,"好评").getStatus());assertEquals(0,count());}finally{jdbc.execute("DROP TRIGGER private_review_failure");}}
    @Test void missingTableIs500NotFalseEmptyReviews()throws Exception {jdbc.execute("RENAME TABLE order_reviews TO order_reviews_private_unavailable");try{assertEquals(500,getReviews("1",buyer).getStatus());}finally{jdbc.execute("RENAME TABLE order_reviews_private_unavailable TO order_reviews");}}
    @Test void databaseConstraintsEnforceRatingAndForeignKeys(){
        var check=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating) VALUES(1,101,6)"));
        assertEquals(3819,((SQLException)check.getMostSpecificCause()).getErrorCode());
        for(String sql:List.of("INSERT INTO order_reviews(order_id,reviewer_id,rating) VALUES(999,101,5)","INSERT INTO order_reviews(order_id,reviewer_id,rating) VALUES(1,999,5)")){
            var foreign=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql));assertEquals(1452,((SQLException)foreign.getMostSpecificCause()).getErrorCode());
        }
    }
    @Test void databaseUniqueKeyPreventsDuplicateParticipantRows()throws Exception {submit(buyer,5,"");assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating) VALUES(1,101,4)"));assertEquals(1,count());}
    @Test void outsiderStoredReviewIsRejectedAs503()throws Exception {jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating) VALUES(1,103,5)");assertEquals(503,getReviews("1",buyer).getStatus());assertEquals(503,submit(buyer,5,"").getStatus());assertEquals(1,count());}
    @Test void reviewBeforeCompletionTimeIsRejected()throws Exception {jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating,created_at) VALUES(1,101,5,CURRENT_TIMESTAMP-INTERVAL 30 MINUTE)");assertEquals(503,getReviews("1",seller).getStatus());}
    @Test void malformedJsonAndNullBodyAre400()throws Exception {for(String body:List.of("null","{bad"))assertEquals(400,postReview("1",buyer,body).getStatus());assertEquals(0,count());}
    @Test void concurrentSamePayloadCreatesOnceAndReplaysOnce()throws Exception {var statuses=concurrent(buyer,buyer,5,5);assertEquals(List.of(200,201),statuses);assertEquals(1,count());}
    @Test void concurrentDifferentPayloadCreatesOnceAndConflictsOnce()throws Exception {var statuses=concurrent(buyer,buyer,5,4);assertEquals(List.of(201,409),statuses);assertEquals(1,count());}
    @Test void concurrentBuyerAndSellerEachSucceed()throws Exception {assertEquals(List.of(201,201),concurrent(buyer,seller,5,4));assertEquals(2,count());}
    List<Integer> concurrent(String x,String y,int a,int b)throws Exception {var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);try{var first=pool.submit(()->{gate.await();return submit(x,a,"隔离并发").getStatus();});var second=pool.submit(()->{gate.await();return submit(y,b,"隔离并发").getStatus();});gate.countDown();var results=new ArrayList<>(List.of(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS)));Collections.sort(results);return results;}finally{pool.shutdownNow();}}
    @Test void sessionRevokedWhileWaitingForOrderLockCannotWrite()throws Exception {var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try(var c=source.getConnection()){c.setAutoCommit(false);c.createStatement().executeQuery("SELECT id FROM market_orders WHERE id=1 FOR UPDATE").close();var f=pool.submit(()->submit(buyer,5,"好评").getStatus());assertThrows(java.util.concurrent.TimeoutException.class,()->f.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));sessions.logout(buyer);c.commit();assertEquals(401,f.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,count());}finally{pool.shutdownNow();}}
    long tasks(){return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class);}
    @Test void buyerNotifiesOnlySeller()throws Exception {node(submit(buyer,5,"私有评价文字"),201);assertEquals(1,tasks());assertEquals(102,jdbc.queryForObject("SELECT recipient_id FROM notification_outbox",Long.class));assertEquals("ORDER_REVIEW:1:101",jdbc.queryForObject("SELECT source_key FROM notification_outbox",String.class));}
    @Test void sellerNotifiesOnlyBuyer()throws Exception {node(submit(seller,1,"仅订单可见"),201);assertEquals(101,jdbc.queryForObject("SELECT recipient_id FROM notification_outbox",Long.class));}
    @Test void eachSideProducesDistinctEvent()throws Exception {submit(buyer,5,"");submit(seller,4,"");assertEquals(2,tasks());assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT event_id) FROM notification_outbox",Long.class));assertEquals(List.of(101L,102L),jdbc.queryForList("SELECT recipient_id FROM notification_outbox ORDER BY recipient_id",Long.class));}
    @Test void replayPreservesTaskAndDeliveryState()throws Exception {submit(buyer,5,"相同");var before=jdbc.queryForList("SELECT * FROM notification_outbox");node(submit(buyer,5,"相同"),200);assertEquals(before,jdbc.queryForList("SELECT * FROM notification_outbox"));}
    @Test void conflictDoesNotCreateOrAlterTask()throws Exception {submit(buyer,5,"");var before=jdbc.queryForList("SELECT * FROM notification_outbox");assertEquals(409,submit(buyer,4,"").getStatus());assertEquals(before,jdbc.queryForList("SELECT * FROM notification_outbox"));}
    @Test void mqDisabledStillCommitsDurablePendingTask()throws Exception {node(submit(buyer,5,"待投递"),201);assertEquals(1,count());assertEquals(0,jdbc.queryForObject("SELECT attempts FROM notification_outbox",Integer.class));assertNull(jdbc.queryForObject("SELECT delivered_at FROM notification_outbox",String.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications",Long.class));}
    @Test void outboxFailureRollsBackReview()throws Exception {jdbc.execute("CREATE TRIGGER private_review_notice_failure BEFORE INSERT ON notification_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");try{assertEquals(500,submit(buyer,5,"").getStatus());assertEquals(0,count());assertEquals(0,tasks());}finally{jdbc.execute("DROP TRIGGER private_review_notice_failure");}}
    @Test void outerRollbackRemovesReviewAndNotification(){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{reviews.create("1",new OrderReviewModels.Create(5L,"回滚"),buyer);assertEquals(1,tasks());tx.setRollbackOnly();});assertEquals(0,count());assertEquals(0,tasks());}
    @Test void noticeUsesGenericBodyAndOrderResource()throws Exception {submit(buyer,2,"隐私文字XYZ");var row=jdbc.queryForMap("SELECT category,resource_id,title,body FROM notification_outbox");assertEquals("ORDER",row.get("category"));assertEquals(1L,((Number)row.get("resource_id")).longValue());String text=row.get("title")+" "+row.get("body");for(String secret:List.of("隐私文字XYZ","review_buyer","review_seller","PRIVATE_HASH","101","102","2星"))assertFalse(text.contains(secret));}
    @Test void replayOfHistoricalReviewDoesNotBackfill()throws Exception {jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating,content) VALUES(1,101,5,'历史评价')");node(submit(buyer,5,"历史评价"),200);assertEquals(0,tasks());assertEquals(409,submit(buyer,4,"历史评价").getStatus());assertEquals(0,tasks());}
    @Test void forgedRecipientIsIgnored()throws Exception {node(postReview("1",buyer,"{\"rating\":5,\"recipientId\":103,\"reviewerId\":102}"),201);assertEquals(102,jdbc.queryForObject("SELECT recipient_id FROM notification_outbox",Long.class));}
    @Test void refusedRequestsNeverCreateReminder()throws Exception {assertEquals(401,submit(null,5,"").getStatus());assertEquals(404,submit(outsider,5,"").getStatus());assertEquals(400,submit(buyer,6,"").getStatus());assertEquals(409,postReview("2",buyer,"{\"rating\":5}").getStatus());assertEquals(0,tasks());}

}
