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
class OrderIssueListIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson106_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson106_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired OrderIssueListService tasks;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired javax.sql.DataSource source;
    final JsonMapper json=JsonMapper.builder().build();String buyer,seller,outsider;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_issue_resolutions","order_issues","order_reviews","user_notifications","notification_outbox","order_events","order_timeout_outbox","market_orders","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
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
    MockHttpServletResponse list(String token,String role,String status,int page,int size)throws Exception {
        var request=get("/api/orders/issues").param("role",role).param("status",status).param("page",""+page).param("size",""+size);
        if(token!=null)request.header("Authorization",token);return mvc.perform(request).andReturn().getResponse();
    }
    JsonNode node(MockHttpServletResponse response)throws Exception {assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));return json.readTree(response.getContentAsByteArray());}
    JsonNode page(String token,String role,String status,int number,int size)throws Exception{return node(list(token,role,status,number,size));}
    JsonNode own(String token)throws Exception{return page(token,"BUYER","ALL",1,10);}
    void report(long order,long who){jdbc.update("INSERT INTO order_issues(order_id,applicant_id,reason,description) VALUES(?,?,'OTHER','私有反馈正文不应出现在列表中')",order,who);}
    void resolve(long order,long who,String decision){jdbc.update("INSERT INTO order_issue_resolutions(order_id,applicant_id,resolver_id,decision,explanation,request_id) VALUES(?,?,103,?,'私有处理说明不应出现在列表中',?)",order,who,decision,UUID.randomUUID().toString());}
    void add(long id,long buyerId,long sellerId){jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status) VALUES(?,'隔离评价样本','分页夹具',100,?,'SOLD')",id,sellerId);jdbc.update("INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,paid_at,payment_kind,delivered_at,completed_at) SELECT ?,?,?,?,?,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,'甲','乙','COMPLETED',created_at,paid_at,payment_kind,delivered_at,completed_at FROM market_orders WHERE id=1",id,id,buyerId,sellerId,UUID.randomUUID().toString());}
    @Test void anonymousAndInvalidSessionsCannotList()throws Exception {assertEquals(401,list(null,"BUYER","ALL",1,10).getStatus());assertEquals(401,list("Bearer "+"A".repeat(43),"BUYER","ALL",1,10).getStatus());}
    @Test void defaultsAreCurrentBuyerAllAndEmptyIsNotAnError()throws Exception {var n=node(mvc.perform(get("/api/orders/issues").header("Authorization",buyer)).andReturn().getResponse());assertEquals("BUYER",n.path("role").asString());assertEquals("ALL",n.path("status").asString());assertEquals(101,n.path("userId").asLong());assertEquals(0,n.path("total").asLong());assertEquals(1,n.path("totalPages").asLong());}
    @Test void eligibleOrdersWithoutOwnSubmissionAreAbsent()throws Exception {report(1,102);assertEquals(0,own(buyer).path("total").asLong());assertEquals(1,page(seller,"SELLER","ALL",1,10).path("total").asLong());}
    @Test void paidDeliveredAndCompletedOwnReportsAreIncluded()throws Exception {report(1,101);report(2,101);report(4,101);assertEquals(3,own(buyer).path("total").asLong());}
    @Test void rolesScopeOnlySubmittedOwnReports()throws Exception {report(1,101);report(1,102);add(7,103,101);report(7,101);assertEquals(1,own(buyer).path("total").asLong());assertEquals(1,page(buyer,"SELLER","ALL",1,10).path("total").asLong());assertEquals(0,own(outsider).path("total").asLong());}
    @Test void forgedIdentityParametersCannotSwitchOwner()throws Exception {report(1,101);var r=mvc.perform(get("/api/orders/issues").param("userId","101").param("applicantId","101").header("Authorization",outsider)).andReturn().getResponse();var n=node(r);assertEquals(103,n.path("userId").asLong());assertEquals(0,n.path("total").asLong());}
    @Test void filtersUseExactWhitelistBeforeSql()throws Exception {for(String r:List.of("buyer","ALL","ADMIN","BUYER' OR 1=1","BUYER "))assertEquals(400,list(buyer,r,"ALL",1,10).getStatus());for(String v:List.of("open","OPEN ","PENDING","RESOLVED' OR 1=1"))assertEquals(400,list(buyer,"BUYER",v,1,10).getStatus());}
    @Test void invalidPageAndSizeAreRejected()throws Exception {for(int v:new int[]{0,-1,1000001})assertEquals(400,list(buyer,"BUYER","ALL",v,10).getStatus());for(int v:new int[]{0,-1,21})assertEquals(400,list(buyer,"BUYER","ALL",1,v).getStatus());}
    @Test void nonIntegerPagingParametersAreRejected()throws Exception {for(String v:List.of("1.0","true","9999999999999999"))assertEquals(400,mvc.perform(get("/api/orders/issues").param("page",v).header("Authorization",buyer)).andReturn().getResponse().getStatus());}
    @Test void ownResolutionMovesOneRowWithoutChangingTotal()throws Exception {report(1,101);assertEquals(1,page(buyer,"BUYER","OPEN",1,10).path("total").asLong());resolve(1,101,"ANSWERED");var n=page(buyer,"BUYER","OPEN",1,10);assertEquals(0,n.path("total").asLong());assertEquals(1,n.at("/summary/total").asLong());assertEquals(1,n.at("/summary/resolved").asLong());assertEquals("ANSWERED",page(buyer,"BUYER","RESOLVED",1,10).at("/items/0/resolutionDecision").asString());}
    @Test void peerResolutionDoesNotResolveMyReport()throws Exception {report(1,101);report(1,102);resolve(1,102,"DISMISSED");var n=page(buyer,"BUYER","OPEN",1,10);assertEquals(1,n.path("total").asLong());assertTrue(n.at("/items/0/resolutionDecision").isNull());assertTrue(n.at("/items/0/resolvedAt").isNull());assertEquals(0,n.at("/summary/resolved").asLong());}
    @Test void bothOutcomeTypesBelongToSeparateApplicants()throws Exception {report(1,101);report(1,102);resolve(1,101,"ANSWERED");resolve(1,102,"DISMISSED");assertEquals("ANSWERED",own(buyer).at("/items/0/resolutionDecision").asString());assertEquals("DISMISSED",page(seller,"SELLER","RESOLVED",1,10).at("/items/0/resolutionDecision").asString());}
    @Test void sqlFiltersBeforePaginationAndSummaryIsUnfiltered()throws Exception {for(int id=7;id<=18;id++){add(id,101,102);report(id,101);if(id<=16)resolve(id,101,"ANSWERED");}var n=page(buyer,"BUYER","OPEN",1,1);assertEquals(2,n.path("total").asLong());assertEquals(12,n.at("/summary/total").asLong());assertEquals(10,n.at("/summary/resolved").asLong());assertEquals(18,n.at("/items/0/orderId").asLong());var last=page(buyer,"BUYER","OPEN",2,1);assertEquals(17,last.at("/items/0/orderId").asLong());assertEquals(n.path("summary"),last.path("summary"));assertEquals(n.path("summary"),page(buyer,"BUYER","RESOLVED",1,10).path("summary"));}
    @Test void equalSubmissionTimesUseDescendingOrderId()throws Exception {report(1,101);add(7,101,102);add(8,101,102);report(7,101);report(8,101);jdbc.update("UPDATE order_issues SET created_at=(SELECT t.created_at FROM (SELECT created_at FROM order_issues WHERE order_id=1 AND applicant_id=101) t)");assertEquals(List.of(8L,7L,1L),tasks.page("BUYER","ALL",1,10,buyer).items().stream().map(OrderIssueListModels.Entry::orderId).toList());}
    @Test void newerSubmissionPrecedesLargerOrderId()throws Exception {add(7,101,102);report(7,101);report(1,101);jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE order_id=7");assertEquals(1,own(buyer).at("/items/0/orderId").asLong());}
    @Test void pageClampsWhenLastOpenReportIsResolved()throws Exception {add(7,101,102);report(1,101);report(7,101);assertEquals(2,page(buyer,"BUYER","OPEN",2,1).path("page").asInt());resolve(1,101,"ANSWERED");var n=page(buyer,"BUYER","OPEN",2,1);assertEquals(1,n.path("page").asInt());assertEquals(7,n.at("/items/0/orderId").asLong());}
    @Test void emptyFilterKeepsSummaryAndClampsToPageOne()throws Exception {report(1,101);var n=page(buyer,"BUYER","RESOLVED",99,10);assertEquals(1,n.path("page").asInt());assertEquals(0,n.path("items").size());assertEquals(1,n.at("/summary/open").asLong());}
    @Test void minimalShapeOmitsBodiesAuditAndPeerIdentity()throws Exception {report(1,101);resolve(1,101,"ANSWERED");var n=own(buyer);assertEquals(Set.of("userId","role","status","page","size","total","totalPages","summary","items"),new HashSet<>(n.propertyNames()));assertEquals(Set.of("total","open","resolved"),new HashSet<>(n.path("summary").propertyNames()));assertEquals(Set.of("orderId","productTitleSnapshot","reason","createdAt","resolutionDecision","resolvedAt"),new HashSet<>(n.path("items").get(0).propertyNames()));for(String secret:List.of("私有反馈","私有处理","description","explanation","resolverId","applicantId","requestId","password","PRIVATE_HASH","sellerId","buyerId"))assertFalse(n.toString().contains(secret));}
    @Test void listUsesOrderTitleSnapshotNotCurrentProduct()throws Exception {report(1,101);jdbc.update("UPDATE products SET title='后来修改的商品标题' WHERE id=1");assertEquals("隔离评价样本",own(buyer).at("/items/0/productTitleSnapshot").asString());}
    Map<String,List<Map<String,Object>>> stable(){var map=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("order_issues","order_issue_resolutions","order_reviews","market_orders","products","order_events","order_timeout_outbox","notification_outbox","user_notifications"))map.put(table,jdbc.queryForList("SELECT * FROM "+table));return map;}
    @Test void repeatedReadsWriteNoBusinessRowsOrNotifications()throws Exception {report(1,101);report(1,102);resolve(1,101,"ANSWERED");var before=stable();for(String role:List.of("BUYER","SELLER"))for(String status:List.of("ALL","OPEN","RESOLVED"))page(buyer,role,status,1,10);assertEquals(before,stable());}
    @Test void missingIssuesTableIs500NotFakeEmpty()throws Exception {jdbc.execute("RENAME TABLE order_issues TO order_issues_private_unavailable");try{assertEquals(500,list(buyer,"BUYER","ALL",1,10).getStatus());}finally{jdbc.execute("RENAME TABLE order_issues_private_unavailable TO order_issues");}}
    @Test void missingResultsTableIs500NotFakeOpen()throws Exception {report(1,101);jdbc.execute("RENAME TABLE order_issue_resolutions TO resolutions_private_unavailable");try{assertEquals(500,list(buyer,"BUYER","ALL",1,10).getStatus());}finally{jdbc.execute("RENAME TABLE resolutions_private_unavailable TO order_issue_resolutions");}}
    @Test void resultBeforeSubmissionIs503()throws Exception {report(1,101);resolve(1,101,"ANSWERED");jdbc.update("UPDATE order_issue_resolutions SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE");assertEquals(503,list(buyer,"BUYER","ALL",1,10).getStatus());}
    @Test void submissionBeforePaymentIs503()throws Exception {report(1,101);jdbc.update("UPDATE order_issues SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY");assertEquals(503,list(buyer,"BUYER","OPEN",1,10).getStatus());}
    @Test void impossibleOrderStateIs503()throws Exception {report(3,101);assertEquals(503,list(buyer,"BUYER","ALL",1,10).getStatus());}
    @Test void revokedSessionCannotReadPreviousRows()throws Exception {report(1,101);own(buyer);sessions.logout(buyer);assertEquals(401,list(buyer,"BUYER","ALL",1,10).getStatus());}
    @Test void repeatableReadKeepsCountAndRowsBeforeConcurrentResolutionCommit()throws Exception {report(1,101);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(1,tasks.page("BUYER","OPEN",1,10,buyer).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->resolve(1,101,"ANSWERED")).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,tasks.page("BUYER","OPEN",1,10,buyer).total());assertEquals(0,tasks.page("BUYER","RESOLVED",1,10,buyer).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(0,tasks.page("BUYER","OPEN",1,10,buyer).total());assertEquals(1,tasks.page("BUYER","RESOLVED",1,10,buyer).total());}
    @Test void listEndpointDoesNotAcceptMutation()throws Exception {assertEquals(405,mvc.perform(post("/api/orders/issues").header("Authorization",buyer)).andReturn().getResponse().getStatus());}
}
