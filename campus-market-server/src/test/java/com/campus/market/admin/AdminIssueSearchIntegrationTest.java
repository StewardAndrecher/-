package com.campus.market.admin;
import com.campus.market.order.*;
import com.campus.market.notification.*;
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
class AdminIssueSearchIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson108_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson108_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired IssueOverviewService overview;@Autowired AdminOrderIssueService adminIssues;@Autowired NotificationOutbox outbox;@Autowired SessionService sessions;@Autowired OrderIssueService issues;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired javax.sql.DataSource source;
    final JsonMapper json=JsonMapper.builder().build();String buyer,seller,outsider,admin,secondAdmin;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_issue_resolutions","order_issues","order_reviews","user_notifications","notification_outbox","order_events","order_timeout_outbox","market_orders","products","user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
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
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(201,'issue_admin','PRIVATE_HASH','管理员甲'),(202,'issue_admin2','PRIVATE_HASH','管理员乙')");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(201,'ADMIN'),(202,'ADMIN')");admin=issue(201);secondAdmin=issue(202);
        issues.create("1",new OrderIssueModels.Create("ITEM_CONDITION","交接时发现商品存在故障，请协助核对处理。"),buyer);
        jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 30 DAY,paid_at=CURRENT_TIMESTAMP(6)-INTERVAL 30 DAY+INTERVAL 1 MINUTE WHERE id IN(1,2,4)");
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"review_"+id,"隔离验收")).accessToken();}
    MockHttpServletResponse search(String token,String id,String status,String age,int page,int size)throws Exception {var r=get("/api/admin/order-issues").param("orderId",id).param("status",status).param("age",age).param("page",""+page).param("size",""+size);if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    JsonNode node(MockHttpServletResponse response)throws Exception {assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));return json.readTree(response.getContentAsByteArray());}
    JsonNode search(String id)throws Exception{return node(search(admin,id,"ALL","ALL",1,10));}
    void report(long order,long applicant,int hours){jdbc.update("INSERT INTO order_issues(order_id,applicant_id,reason,description,created_at) VALUES(?,?,'OTHER','仅供精确检索测试的私有反馈正文',CURRENT_TIMESTAMP(6)-INTERVAL ? HOUR)",order,applicant,hours);}
    void age(int hours){jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL ? HOUR WHERE order_id=1 AND applicant_id=101",hours);}
    void resolve(long applicant){adminIssues.resolve("1",""+applicant,new AdminOrderIssueModels.Resolve("ANSWERED","仅供精确检索测试的私有处理说明",UUID.randomUUID().toString()),admin);}
    @Test void exactIdDoesNotAuthorizeAnonymousOrOrdinaryAccounts()throws Exception {assertEquals(401,search(null,"1","ALL","ALL",1,10).getStatus());for(String who:List.of(buyer,seller,outsider))assertEquals(403,search(who,"1","ALL","ALL",1,10).getStatus());}
    @Test void omittedOrderFilterStillEchoesNullAndFindsAll()throws Exception {var n=node(mvc.perform(get("/api/admin/order-issues").header("Authorization",admin)).andReturn().getResponse());assertTrue(n.path("orderId").isNull());assertEquals(1,n.path("total").asLong());}
    @Test void exactOrderIdFindsOnlyMatchingFeedback()throws Exception {report(2,101,1);var n=search("1");assertEquals(1,n.path("orderId").asLong());assertEquals(1,n.path("total").asLong());assertEquals(1,n.at("/items/0/orderId").asLong());}
    @Test void bothParticipantsRemainSeparateCasesForOneOrder()throws Exception {report(1,102,1);var n=search("1");assertEquals(2,n.path("total").asLong());assertEquals(Set.of(101L,102L),new HashSet<>(adminIssues.page("ALL","ALL","1",1,10,admin).items().stream().map(AdminOrderIssueModels.Item::applicantId).toList()));}
    @Test void nonexistentOrderIsEmptyNotAnUnrelatedCase()throws Exception {var n=search("99999");assertEquals(99999,n.path("orderId").asLong());assertEquals(0,n.path("total").asLong());assertEquals(1,n.path("totalPages").asLong());}
    @Test void orderWithoutFeedbackIsEmpty()throws Exception {assertEquals(0,search("2").path("total").asLong());}
    @Test void emptyFilterMeansUnrestrictedButWhitespaceDoesNot()throws Exception {assertTrue(search("").path("orderId").isNull());for(String id:List.of(" "," 1","1 "))assertEquals(400,search(admin,id,"ALL","ALL",1,10).getStatus());}
    @Test void canonicalOrderIdIsStrictAndNeverSqlText()throws Exception {for(String id:List.of("0","01","-1","+1","1.0","1e0","１","1 OR 1=1","1; DROP TABLE users","9007199254740992","12345678901234567"))assertEquals(400,search(admin,id,"ALL","ALL",1,10).getStatus());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_issues",Long.class));}
    @Test void largestSafeIntegerIsAcceptedWithoutPrecisionLoss()throws Exception {var n=search("9007199254740991");assertEquals(9007199254740991L,n.path("orderId").asLong());assertEquals(0,n.path("total").asLong());}
    @Test void statusAndIdAreAnIntersection()throws Exception {resolve(101);assertEquals(0,node(search(admin,"1","OPEN","ALL",1,10)).path("total").asLong());assertEquals(1,node(search(admin,"1","RESOLVED","ALL",1,10)).path("total").asLong());assertEquals(0,node(search(admin,"2","RESOLVED","ALL",1,10)).path("total").asLong());}
    @Test void waitingAgeAndIdAreAnIntersection()throws Exception {age(80);report(2,101,1);assertEquals(1,node(search(admin,"1","OPEN","OVER_72H",1,10)).path("total").asLong());assertEquals(0,node(search(admin,"2","OPEN","OVER_24H",1,10)).path("total").asLong());}
    @Test void incompatibleAgeAndStatusStillFail()throws Exception {assertEquals(400,search(admin,"1","RESOLVED","OVER_24H",1,10).getStatus());}
    @Test void sqlExactFilterPrecedesPaginationAndClamp()throws Exception {report(1,102,2);report(2,101,1);var first=node(search(admin,"1","ALL","ALL",1,1));assertEquals(2,first.path("total").asLong());assertEquals(2,first.path("totalPages").asLong());var last=node(search(admin,"1","ALL","ALL",99,1));assertEquals(2,last.path("page").asLong());assertEquals(1,last.at("/items/0/orderId").asLong());assertNotEquals(first.at("/items/0/applicantId").asLong(),last.at("/items/0/applicantId").asLong());}
    @Test void returnedPageMayShrinkAfterAResolutionWithoutLosingFilter()throws Exception {age(80);report(1,102,80);assertEquals(2,node(search(admin,"1","OPEN","OVER_72H",2,1)).path("page").asLong());resolve(101);var n=node(search(admin,"1","OPEN","OVER_72H",2,1));assertEquals(1,n.path("page").asLong());assertEquals(1,n.path("orderId").asLong());assertEquals("OVER_72H",n.path("age").asString());assertEquals(102,n.at("/items/0/applicantId").asLong());}
    @Test void blankSearchDoesNotResetSelectedAge()throws Exception {age(80);var n=node(search(admin,"","OPEN","OVER_72H",1,10));assertTrue(n.path("orderId").isNull());assertEquals("OVER_72H",n.path("age").asString());assertEquals(1,n.path("total").asLong());}
    @Test void responseEchoIsMinimalAndContainsNoBodiesOrAudit()throws Exception {var n=search("1");assertEquals(Set.of("status","age","orderId","sampledAt","page","size","total","totalPages","items"),new HashSet<>(n.propertyNames()));for(String field:List.of("description","explanation","resolverId","requestId","password","PRIVATE_HASH"))assertFalse(n.toString().contains(field));}
    @Test void oldServiceOverloadsRemainUnfiltered()throws Exception {assertNull(adminIssues.page("OPEN",1,10,admin).orderId());assertNull(adminIssues.page("OPEN","ALL",1,10,admin).orderId());}
    @Test void revokedSessionCannotSearchKnownOrder()throws Exception {search("1");sessions.logout(admin);assertEquals(401,search(admin,"1","ALL","ALL",1,10).getStatus());}
    @Test void revokedGrantCannotSearchKnownOrder()throws Exception {search("1");jdbc.update("DELETE FROM user_role_grants WHERE user_id=201");assertEquals(403,search(admin,"1","ALL","ALL",1,10).getStatus());}
    Map<String,List<Map<String,Object>>> stable(){var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("order_issues","order_issue_resolutions","market_orders","products","order_reviews","order_events","order_timeout_outbox","notification_outbox","user_notifications","user_role_grants","user_role_audit"))result.put(table,jdbc.queryForList("SELECT * FROM "+table));return result;}
    @Test void repeatedExactQueriesWriteNoBusinessOrNotificationRows()throws Exception {var before=stable();search("1");search("");search("2");node(search(admin,"1","OPEN","OVER_24H",1,10));assertEquals(before,stable());}
    @Test void missingResultTableIs500NotAFalseEmptySearch()throws Exception {jdbc.execute("RENAME TABLE order_issue_resolutions TO private_search_unavailable");try{assertEquals(500,search(admin,"1","ALL","ALL",1,10).getStatus());}finally{jdbc.execute("RENAME TABLE private_search_unavailable TO order_issue_resolutions");}}
    @Test void corruptThirdApplicantFailsInsteadOfLeakingExtraCase()throws Exception {report(1,102,1);report(1,103,1);assertEquals(503,search(admin,"1","ALL","ALL",1,10).getStatus());}
    @Test void repeatableReadExactSearchKeepsItsSnapshot()throws Exception {var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(1,adminIssues.page("OPEN","ALL","1",1,10,admin).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->resolve(101)).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,adminIssues.page("OPEN","ALL","1",1,10,admin).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(0,adminIssues.page("OPEN","ALL","1",1,10,admin).total());}
}
