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
class IssueOverviewIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson107_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson107_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
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
    MockHttpServletResponse stats(String token)throws Exception {var r=get("/api/admin/order-issues/overview");if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    MockHttpServletResponse list(String token,String status,String age,int page,int size)throws Exception {var r=get("/api/admin/order-issues").param("status",status).param("age",age).param("page",""+page).param("size",""+size);if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();}
    JsonNode node(MockHttpServletResponse r)throws Exception {assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode stats()throws Exception{return node(stats(admin));}
    JsonNode aged(String age)throws Exception{return node(list(admin,"OPEN",age,1,20));}
    void report(long order,long applicant,int hours){jdbc.update("INSERT INTO order_issues(order_id,applicant_id,reason,description,created_at) VALUES(?,?,'OTHER','不应泄漏到概览的私有反馈正文',CURRENT_TIMESTAMP(6)-INTERVAL ? HOUR)",order,applicant,hours);}
    void age(int hours){jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL ? HOUR WHERE order_id=1 AND applicant_id=101",hours);}
    void resolve(long order,long applicant,String decision){adminIssues.resolve(""+order,""+applicant,new AdminOrderIssueModels.Resolve(decision,"不应泄漏到概览的私有处理说明",UUID.randomUUID().toString()),admin);}
    void add(long id){jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status) VALUES(?,'私有时效样本','隔离分页测试',100,102,'SOLD')",id);jdbc.update("INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,paid_at,payment_kind) SELECT ?,?,101,102,?,'私有时效样本',100,0,'甲','乙','PAID',created_at,paid_at,'SIMULATED' FROM market_orders WHERE id=1",id,id,UUID.randomUUID().toString());}
    @Test void anonymousCannotReadOverviewOrAgingRows()throws Exception {assertEquals(401,stats(null).getStatus());assertEquals(401,list(null,"OPEN","OVER_24H",1,10).getStatus());}
    @Test void ordinaryParticipantsCannotReadManagementData()throws Exception {for(String who:List.of(buyer,seller,outsider)){assertEquals(403,stats(who).getStatus());assertEquals(403,list(who,"OPEN","OVER_72H",1,10).getStatus());}}
    @Test void revokedSessionCannotKeepReading()throws Exception {stats();sessions.logout(admin);assertEquals(401,stats(admin).getStatus());assertEquals(401,list(admin,"OPEN","ALL",1,10).getStatus());}
    @Test void livePermissionRemovalTakesEffectOnNextRead()throws Exception {stats();jdbc.update("DELETE FROM user_role_grants WHERE user_id=201");assertEquals(403,stats(admin).getStatus());assertEquals(403,list(admin,"OPEN","OVER_24H",1,10).getStatus());}
    @Test void emptyTableHasZeroCountersAndNullOldest()throws Exception {jdbc.update("DELETE FROM order_issues");var n=stats();for(String k:List.of("total","open","resolved","answered","dismissed","openOver24h","openOver72h"))assertEquals(0,n.path(k).asLong());assertTrue(n.path("oldestOpenAt").isNull());assertFalse(n.path("sampledAt").asString().isEmpty());}
    @Test void freshIssueCountsOneOpenWithoutOverdueBuckets()throws Exception {var n=stats();assertEquals(1,n.path("total").asLong());assertEquals(1,n.path("open").asLong());assertEquals(0,n.path("resolved").asLong());assertEquals(0,n.path("openOver24h").asLong());assertFalse(n.path("oldestOpenAt").isNull());}
    @Test void bothParticipantsCountAsSeparateFeedbackNotOrders()throws Exception {report(1,102,2);var n=stats();assertEquals(2,n.path("total").asLong());assertEquals(2,n.path("open").asLong());}
    @Test void twoOutcomeTypesPartitionResolvedCounters()throws Exception {report(1,102,2);resolve(1,101,"ANSWERED");resolve(1,102,"DISMISSED");var n=stats();assertEquals(2,n.path("resolved").asLong());assertEquals(1,n.path("answered").asLong());assertEquals(1,n.path("dismissed").asLong());assertEquals(0,n.path("open").asLong());assertTrue(n.path("oldestOpenAt").isNull());}
    @Test void oldResolvedIssueIsNotAnOpenBacklog()throws Exception {age(100);resolve(1,101,"ANSWERED");report(2,101,1);var n=stats();assertEquals(2,n.path("total").asLong());assertEquals(1,n.path("open").asLong());assertEquals(0,n.path("openOver24h").asLong());assertEquals(0,n.path("openOver72h").asLong());assertEquals(jdbc.queryForObject("SELECT created_at FROM order_issues WHERE order_id=2",Timestamp.class).toLocalDateTime().toString(),n.path("oldestOpenAt").asString());}
    @Test void seventyTwoHourBucketIsASubsetOfTwentyFour()throws Exception {report(2,101,30);report(4,101,80);var n=stats();assertEquals(3,n.path("open").asLong());assertEquals(2,n.path("openOver24h").asLong());assertEquals(1,n.path("openOver72h").asLong());}
    @Test void exactTwentyFourHourBoundaryIsIncluded()throws Exception {age(24);assertEquals(1,stats().path("openOver24h").asLong());assertEquals(1,aged("OVER_24H").path("total").asLong());}
    @Test void exactSeventyTwoHourBoundaryIsIncluded()throws Exception {age(72);var n=stats();assertEquals(1,n.path("openOver24h").asLong());assertEquals(1,n.path("openOver72h").asLong());assertEquals(1,aged("OVER_72H").path("total").asLong());}
    @Test void justInsideTwentyFourHoursIsNotIncluded()throws Exception {jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR+INTERVAL 10 MINUTE");assertEquals(0,stats().path("openOver24h").asLong());assertEquals(0,aged("OVER_24H").path("total").asLong());}
    @Test void justInsideSeventyTwoHoursIsOnlyInTwentyFourBucket()throws Exception {jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 72 HOUR+INTERVAL 10 MINUTE");var n=stats();assertEquals(1,n.path("openOver24h").asLong());assertEquals(0,n.path("openOver72h").asLong());}
    @Test void clientCannotSupplyItsOwnClock()throws Exception {var r=mvc.perform(get("/api/admin/order-issues/overview").param("sampledAt","2099-01-01T00:00:00").header("Authorization",admin)).andReturn().getResponse();assertEquals(0,node(r).path("openOver24h").asLong());}
    @Test void ageRequiresOpenStatusAndExactWhitelist()throws Exception {for(String status:List.of("ALL","RESOLVED"))assertEquals(400,list(admin,status,"OVER_24H",1,10).getStatus());for(String age:List.of("","over_24h","24","OVER_24H ","ALL' OR 1=1"))assertEquals(400,list(admin,"OPEN",age,1,10).getStatus());}
    @Test void defaultListPreservesNewestFirstAndAddsSampleEcho()throws Exception {report(2,101,2);var r=mvc.perform(get("/api/admin/order-issues").header("Authorization",admin)).andReturn().getResponse();var n=node(r);assertEquals("OPEN",n.path("status").asString());assertEquals("ALL",n.path("age").asString());assertEquals(1,n.at("/items/0/orderId").asLong());assertFalse(n.path("sampledAt").asString().isEmpty());}
    @Test void agingListFiltersOutFreshAndResolvedIssues()throws Exception {report(2,101,30);report(4,101,80);resolve(4,101,"ANSWERED");var n=aged("OVER_24H");assertEquals(1,n.path("total").asLong());assertEquals(2,n.at("/items/0/orderId").asLong());assertEquals("OVER_24H",n.path("age").asString());assertEquals(0,aged("OVER_72H").path("total").asLong());}
    @Test void agingListUsesOldestFirstAndCompositeAscendingTies()throws Exception {age(80);report(1,102,80);report(2,101,30);jdbc.update("UPDATE order_issues SET created_at=(SELECT x.at FROM (SELECT created_at at FROM order_issues WHERE order_id=1 AND applicant_id=101) x) WHERE order_id=1");var n=aged("OVER_24H");assertEquals(1,n.at("/items/0/orderId").asLong());assertEquals(101,n.at("/items/0/applicantId").asLong());assertEquals(102,n.at("/items/1/applicantId").asLong());assertEquals(2,n.at("/items/2/orderId").asLong());}
    @Test void statusAllStillUsesDescendingCompositeOrder()throws Exception {age(80);report(1,102,80);jdbc.update("UPDATE order_issues SET created_at=(SELECT x.at FROM (SELECT created_at at FROM order_issues WHERE order_id=1 AND applicant_id=101) x)");var n=node(list(admin,"ALL","ALL",1,10));assertEquals(102,n.at("/items/0/applicantId").asLong());}
    @Test void sqlAgeFilterRunsBeforePaginationAndPageClamp()throws Exception {for(int id=7;id<=18;id++){add(id);report(id,101,id<16?1:80);}var n=node(list(admin,"OPEN","OVER_24H",1,2));assertEquals(3,n.path("total").asLong());assertEquals(2,n.path("items").size());assertEquals(2,n.path("totalPages").asLong());var last=node(list(admin,"OPEN","OVER_24H",99,2));assertEquals(2,last.path("page").asLong());assertEquals(1,last.path("items").size());}
    @Test void overviewContainsNoIdentityBodyOrAuditFields()throws Exception {resolve(1,101,"ANSWERED");var n=stats();assertEquals(Set.of("sampledAt","total","open","resolved","answered","dismissed","openOver24h","openOver72h","oldestOpenAt"),new HashSet<>(n.propertyNames()));for(String secret:List.of("私有","orderId","applicantId","resolverId","description","explanation","requestId","password","PRIVATE_HASH","issue_admin"))assertFalse(n.toString().contains(secret));}
    Map<String,List<Map<String,Object>>> stable(){var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("order_issues","order_issue_resolutions","market_orders","products","order_reviews","order_events","order_timeout_outbox","notification_outbox","user_notifications","user_role_grants","user_role_audit"))result.put(table,jdbc.queryForList("SELECT * FROM "+table));return result;}
    @Test void readsChangeNoBusinessAuditOrNotificationRows()throws Exception {age(80);var before=stable();stats();aged("OVER_24H");aged("OVER_72H");node(list(admin,"ALL","ALL",1,10));assertEquals(before,stable());}
    @Test void missingResultTableIs500NotZeroBacklog()throws Exception {jdbc.execute("RENAME TABLE order_issue_resolutions TO private_result_unavailable");try{assertEquals(500,stats(admin).getStatus());assertEquals(500,list(admin,"OPEN","OVER_24H",1,10).getStatus());}finally{jdbc.execute("RENAME TABLE private_result_unavailable TO order_issue_resolutions");}}
    @Test void resolutionBeforeFeedbackIs503()throws Exception {resolve(1,101,"ANSWERED");jdbc.update("UPDATE order_issue_resolutions SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE");assertEquals(503,stats(admin).getStatus());}
    @Test void feedbackInFutureIs503NotHealthyEmptyOverview()throws Exception {jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY");assertEquals(503,stats(admin).getStatus());assertEquals(503,list(admin,"OPEN","ALL",1,10).getStatus());}
    @Test void futureResolutionIs503()throws Exception {resolve(1,101,"ANSWERED");jdbc.update("UPDATE order_issue_resolutions SET created_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY");assertEquals(503,stats(admin).getStatus());}
    @Test void feedbackBeforePaymentIs503()throws Exception {jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 40 DAY");assertEquals(503,stats(admin).getStatus());}
    @Test void invalidApplicantRelationIs503()throws Exception {jdbc.update("UPDATE order_issues SET applicant_id=103");assertEquals(503,stats(admin).getStatus());}
    @Test void resolutionRemovesBacklogAndMovesOldest()throws Exception {age(100);report(2,101,30);resolve(1,101,"ANSWERED");var n=stats();assertEquals(1,n.path("openOver24h").asLong());assertEquals(0,n.path("openOver72h").asLong());assertEquals(1,n.path("resolved").asLong());assertEquals(jdbc.queryForObject("SELECT created_at FROM order_issues WHERE order_id=2",Timestamp.class).toLocalDateTime().toString(),n.path("oldestOpenAt").asString());}
    @Test void outerReadSnapshotSurvivesConcurrentResolution()throws Exception {age(80);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(1,overview.sample(admin).openOver72h());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->resolve(1,101,"ANSWERED")).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,overview.sample(admin).openOver72h());assertEquals(1,adminIssues.page("OPEN","OVER_72H",1,10,admin).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(0,overview.sample(admin).openOver72h());assertEquals(0,adminIssues.page("OPEN","OVER_72H",1,10,admin).total());}
    @Test void overviewDoesNotAcceptMutation()throws Exception {assertEquals(405,mvc.perform(post("/api/admin/order-issues/overview").header("Authorization",admin)).andReturn().getResponse().getStatus());}
}
