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
class OrderIssueIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson104_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson104_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired SessionService sessions;@Autowired OrderIssueService issues;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired javax.sql.DataSource source;
    final JsonMapper json=JsonMapper.builder().build();String buyer,seller,outsider;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_issues","order_reviews","user_notifications","notification_outbox","order_events","order_timeout_outbox","market_orders","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
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
    final String description="交接时发现商品存在故障，请协助核对处理。";
    MockHttpServletResponse read(String id,String token)throws Exception {var r=get("/api/orders/"+id+"/issue");if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    MockHttpServletResponse postIssue(String id,String token,String body)throws Exception {var r=post("/api/orders/"+id+"/issue").contentType("application/json").content(body);if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    MockHttpServletResponse submit(String id,String token,String reason,String text)throws Exception{return postIssue(id,token,json.writeValueAsString(Map.of("reason",reason,"description",text)));}
    MockHttpServletResponse submit(String token)throws Exception{return submit("1",token,"ITEM_CONDITION",description);}
    JsonNode node(MockHttpServletResponse r,int expected)throws Exception {assertEquals(expected,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM order_issues",Long.class);}
    @Test void anonymousReadAndWriteAre401()throws Exception {assertEquals(401,read("1",null).getStatus());assertEquals(401,submit(null).getStatus());assertEquals(0,count());}
    @Test void outsiderAndMissingOrdersShare404()throws Exception {for(String id:List.of("1","999")){assertEquals(404,read(id,outsider).getStatus());assertEquals(404,submit(id,outsider,"OTHER",description).getStatus());}assertEquals(0,count());}
    @Test void invalidIdsAre400()throws Exception {for(String id:List.of("0","01","-1","1.0","1e1","9007199254740992"))assertEquals(400,read(id,buyer).getStatus());}
    @Test void revokedAndExpiredSessionsCannotReadOrSubmit()throws Exception {sessions.logout(buyer);assertEquals(401,submit(buyer).getStatus());jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP WHERE user_id=102");assertEquals(401,read("1",seller).getStatus());}
    @Test void threePaidStagesAllowSubmission()throws Exception {for(String id:List.of("1","2","4")){assertTrue(node(read(id,buyer),200).path("canSubmit").asBoolean());node(submit(id,buyer,"HANDOVER",description),201);}assertEquals(3,count());}
    @Test void unpaidCancelledAndTimeoutCannotSubmit()throws Exception {for(String id:List.of("3","5","6")){assertFalse(node(read(id,buyer),200).path("canSubmit").asBoolean());assertEquals(409,submit(id,buyer,"OTHER",description).getStatus());}assertEquals(0,count());}
    @Test void firstSubmission201ContainsOwnRecord()throws Exception {var n=node(submit(buyer),201);assertFalse(n.path("replayed").asBoolean());assertEquals(description,n.at("/summary/issue/description").asString());assertFalse(n.at("/summary/canSubmit").asBoolean());}
    @Test void sellerCannotReadBuyersIssue()throws Exception {submit(buyer);var n=node(read("1",seller),200);assertTrue(n.path("issue").isNull());assertTrue(n.path("canSubmit").asBoolean());assertEquals("SELLER",n.path("viewerRole").asString());}
    @Test void buyerCannotReadSellersIssue()throws Exception {submit(seller);assertTrue(node(read("1",buyer),200).path("issue").isNull());}
    @Test void bothParticipantsHaveIndependentFirstRecords()throws Exception {submit(buyer);node(submit("1",seller,"HANDOVER","买家交接时间多次变更，希望核对沟通过程。"),201);assertEquals(2,count());assertEquals(description,node(read("1",buyer),200).at("/issue/description").asString());}
    @Test void sameNormalizedPayloadReplays200AndRetainsTimestamp()throws Exception {var first=node(submit(buyer),201);var before=jdbc.queryForList("SELECT * FROM order_issues");var again=node(submit("1",buyer,"ITEM_CONDITION"," \r\n"+description+"\u00a0"),200);assertTrue(again.path("replayed").asBoolean());assertEquals(first.path("summary"),again.path("summary"));assertEquals(before,jdbc.queryForList("SELECT * FROM order_issues"));}
    @Test void differentPayload409NeverOverwrites()throws Exception {submit(buyer);var before=jdbc.queryForList("SELECT * FROM order_issues");assertEquals(409,submit("1",buyer,"OTHER",description).getStatus());assertEquals(409,submit("1",buyer,"ITEM_CONDITION",description+"补充").getStatus());assertEquals(before,jdbc.queryForList("SELECT * FROM order_issues"));}
    @Test void reasonWhitelistRejectsUnknownAndInjection()throws Exception {for(String reason:List.of("","item_condition","REFUND","OTHER' OR 1=1","OTHER "))assertEquals(400,submit("1",buyer,reason,description).getStatus());assertEquals(0,count());}
    @Test void allThreeReasonsAreSupported()throws Exception {for(String reason:List.of("ITEM_CONDITION","HANDOVER","OTHER")){jdbc.update("DELETE FROM order_issues");node(submit("1",buyer,reason,description),201);}}
    @Test void emptyShortAndOverlongDescriptionAre400()throws Exception {for(String value:List.of(""," ","字".repeat(9),"字".repeat(1001)))assertEquals(400,submit("1",buyer,"OTHER",value).getStatus());assertEquals(0,count());}
    @Test void unicodeCodepointLimitsAllowTenAndThousand()throws Exception {node(submit("1",buyer,"OTHER","😀".repeat(10)),201);jdbc.update("DELETE FROM order_issues");node(submit("1",buyer,"OTHER","😀".repeat(1000)),201);}
    @Test void controlCharactersRejectedAndLineEndingsNormalize()throws Exception {assertEquals(400,submit("1",buyer,"OTHER",description+"\u0000").getStatus());var n=node(submit("1",buyer,"OTHER","\u3000\ufeff第一行记录了商品问题\r\n\t第二行说明交接情况\u00a0"),201);assertEquals("第一行记录了商品问题\n\t第二行说明交接情况",n.at("/summary/issue/description").asString());}
    @Test void descriptionAndReasonRejectJsonTypeCoercion()throws Exception {for(String value:List.of("1234567890","true","[]","{}","null"))assertEquals(400,postIssue("1",buyer,"{\"reason\":\"OTHER\",\"description\":"+value+"}").getStatus());assertEquals(400,postIssue("1",buyer,"{\"reason\":1,\"description\":\""+description+"\"}").getStatus());}
    @Test void forgedApplicantCannotWriteForPeer()throws Exception {node(postIssue("1",buyer,json.writeValueAsString(Map.of("reason","OTHER","description",description,"applicantId",102,"userId",102))),201);assertEquals(101,jdbc.queryForObject("SELECT applicant_id FROM order_issues",Long.class));assertTrue(node(read("1",seller),200).path("issue").isNull());}
    @Test void exactShapeExcludesPeerIdentityAndOtherPrivateData()throws Exception {var n=node(submit(buyer),201);assertEquals(Set.of("summary","replayed"),new HashSet<>(n.propertyNames()));var summary=n.path("summary");assertEquals(Set.of("orderId","userId","orderStatus","viewerRole","canSubmit","issue"),new HashSet<>(summary.propertyNames()));assertEquals(Set.of("reason","description","createdAt","resolution"),new HashSet<>(summary.path("issue").propertyNames()));for(String secret:List.of("applicantId","sellerId","buyerId","review_seller","PRIVATE_HASH",seller.substring(7)))assertFalse(n.toString().contains(secret));}
    Map<String,List<Map<String,Object>>> stable(){var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("market_orders","products","order_events","order_reviews","notification_outbox","user_notifications","order_timeout_outbox"))result.put(table,jdbc.queryForList("SELECT * FROM "+table));return result;}
    @Test void issueDoesNotModifyOrdersProductsReviewsEventsOrMq()throws Exception {var before=stable();submit(buyer);assertEquals(before,stable());assertEquals(1,count());}
    @Test void readsAreReadOnly()throws Exception {submit(buyer);var before=jdbc.queryForList("SELECT * FROM order_issues");var others=stable();read("1",buyer);read("1",seller);assertEquals(before,jdbc.queryForList("SELECT * FROM order_issues"));assertEquals(others,stable());}
    @Test void outerRollbackRemovesOnlyNewIssue(){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{issues.create("1",new OrderIssueModels.Create("OTHER",description),buyer);tx.setRollbackOnly();});assertEquals(0,count());}
    @Test void insertFailureReturns500WithoutPartialWrite()throws Exception {jdbc.execute("CREATE TRIGGER private_issue_failure BEFORE INSERT ON order_issues FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");try{assertEquals(500,submit(buyer).getStatus());assertEquals(0,count());}finally{jdbc.execute("DROP TRIGGER private_issue_failure");}}
    @Test void missingTableIs500NotFalseEmptyRecord()throws Exception {jdbc.execute("RENAME TABLE order_issues TO order_issues_private_unavailable");try{assertEquals(500,read("1",buyer).getStatus());}finally{jdbc.execute("RENAME TABLE order_issues_private_unavailable TO order_issues");}}
    @Test void databaseChecksAndForeignKeysRemainEnforced(){for(String sql:List.of("INSERT INTO order_issues VALUES(1,101,'BAD','abcdefghijk',CURRENT_TIMESTAMP)","INSERT INTO order_issues VALUES(1,101,'OTHER','short',CURRENT_TIMESTAMP)"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO order_issues VALUES(999,101,'OTHER','abcdefghijk',CURRENT_TIMESTAMP)"));}
    @Test void databaseUniqueKeyPreventsDuplicateParticipant()throws Exception {submit(buyer);assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO order_issues(order_id,applicant_id,reason,description) VALUES(1,101,'OTHER','abcdefghijk')"));assertEquals(1,count());}
    @Test void corruptTimestampFails503()throws Exception {jdbc.update("INSERT INTO order_issues(order_id,applicant_id,reason,description,created_at) VALUES(1,101,'OTHER','abcdefghijk',CURRENT_TIMESTAMP-INTERVAL 30 MINUTE)");assertEquals(503,read("1",buyer).getStatus());}
    @Test void malformedAndNullBodiesAre400()throws Exception {for(String body:List.of("null","{bad","{}"))assertEquals(400,postIssue("1",buyer,body).getStatus());}
    @Test void concurrentSamePayloadCreatesOnceAndReplaysOnce()throws Exception {assertEquals(List.of(200,201),concurrent(buyer,buyer,false));assertEquals(1,count());}
    @Test void concurrentDifferentPayloadCreatesOnceAndConflictsOnce()throws Exception {assertEquals(List.of(201,409),concurrent(buyer,buyer,true));assertEquals(1,count());}
    @Test void concurrentBuyerAndSellerCanEachSubmit()throws Exception {assertEquals(List.of(201,201),concurrent(buyer,seller,true));assertEquals(2,count());}
    List<Integer> concurrent(String first,String second,boolean different)throws Exception {var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);try{var a=pool.submit(()->{gate.await();return submit(first).getStatus();});var b=pool.submit(()->{gate.await();return submit("1",second,different?"OTHER":"ITEM_CONDITION",description).getStatus();});gate.countDown();var result=new ArrayList<>(List.of(a.get(5,java.util.concurrent.TimeUnit.SECONDS),b.get(5,java.util.concurrent.TimeUnit.SECONDS)));Collections.sort(result);return result;}finally{pool.shutdownNow();}}
    @Test void revokedSessionWhileWaitingForOrderLockCannotSubmit()throws Exception {var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try(var c=source.getConnection()){c.setAutoCommit(false);c.createStatement().executeQuery("SELECT id FROM market_orders WHERE id=1 FOR UPDATE").close();var f=pool.submit(()->submit(buyer).getStatus());assertThrows(java.util.concurrent.TimeoutException.class,()->f.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));sessions.logout(buyer);c.commit();assertEquals(401,f.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,count());}finally{pool.shutdownNow();}}
}
