package com.campus.market.notification;
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
class NotificationFilterIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson119_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson119_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired NotificationService service;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String owner,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("user_notifications","notification_outbox","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'filter_owner','PRIVATE_HASH','甲'),(102,'filter_other','PRIVATE_HASH','乙')");
        for(int i=0;i<9;i++)notice(101,"ORDER",i<3);
        for(int i=0;i<5;i++)notice(101,"REPORT",i<2);
        for(int i=0;i<4;i++)notice(101,"MODERATION",i<2);
        notice(102,"ORDER",false);notice(102,"REPORT",false);notice(102,"REPORT",false);
        owner=issue(101);other=issue(102);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"filter_"+id,"隔离验收")).accessToken();}
    long notice(long user,String category,boolean read){
        String event=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notification_outbox(event_id,source_key,recipient_id,category,resource_id,title,body,created_at,delivered_at) VALUES(?,?,?,?,201,?,'仅私有筛选验收',CURRENT_TIMESTAMP-INTERVAL 2 MINUTE,CURRENT_TIMESTAMP)",event,"TEST:"+event,user,category,category+"通知");
        jdbc.update("INSERT INTO user_notifications(event_id,recipient_id,category,resource_id,title,body,occurred_at,created_at,read_at) VALUES(?,?,?,201,?,'仅私有筛选验收',CURRENT_TIMESTAMP-INTERVAL 3 MINUTE,CURRENT_TIMESTAMP-INTERVAL 2 MINUTE,"+(read?"CURRENT_TIMESTAMP-INTERVAL 1 MINUTE":"NULL")+")",event,user,category,category+"通知");
        return jdbc.queryForObject("SELECT id FROM user_notifications WHERE event_id=?",Long.class,event);
    }
    MockHttpServletResponse get(String token,String category,String status,int page,int size)throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications").param("category",category).param("status",status).param("page",""+page).param("size",""+size);
        if(token!=null)request.header("Authorization",token);return mvc.perform(request).andReturn().getResponse();
    }
    JsonNode node(MockHttpServletResponse r,int status)throws Exception {assertEquals(status,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode page(String category,String status)throws Exception{return node(get(owner,category,status,1,20),200);}
    Map<String,List<Map<String,Object>>> stable(){var rows=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("users","login_sessions","user_notifications","notification_outbox","market_orders","products"))rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}
    @Test void omittedFiltersKeepLegacyAllResponse()throws Exception {var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications").header("Authorization",owner)).andReturn().getResponse();var n=node(r,200);assertEquals(18,n.path("total").asLong());assertEquals(10,n.path("records").size());assertEquals(Set.of("records","total","page","size"),new HashSet<>(n.propertyNames()));}
    @Test void eachCategoryFiltersItsOwnRecords()throws Exception {for(String category:List.of("ORDER","REPORT","MODERATION")){var n=page(category,"ALL");assertEquals(category.equals("ORDER")?9:category.equals("REPORT")?5:4,n.path("total").asLong());for(var row:n.path("records"))assertEquals(category,row.path("category").asString());}}
    @Test void readAndUnreadPartitionAllTypes()throws Exception {assertEquals(11,page("ALL","UNREAD").path("total").asLong());assertEquals(7,page("ALL","READ").path("total").asLong());for(var n:page("ALL","READ").path("records"))assertFalse(n.path("readAt").isNull());}
    @Test void allCategoryStatusIntersectionsAreAppliedInDatabase()throws Exception {
        for(String category:List.of("ORDER","REPORT","MODERATION")){int total=category.equals("ORDER")?9:category.equals("REPORT")?5:4,read=category.equals("ORDER")?3:2;assertEquals(read,page(category,"READ").path("total").asLong());assertEquals(total-read,page(category,"UNREAD").path("total").asLong());}
    }
    @Test void filtersApplyBeforePaginationAndCount()throws Exception {var p1=node(get(owner,"REPORT","UNREAD",1,2),200);var p2=node(get(owner,"REPORT","UNREAD",2,2),200);assertEquals(3,p1.path("total").asLong());assertEquals(2,p1.path("records").size());assertEquals(1,p2.path("records").size());assertTrue(p1.at("/records/1/id").asLong()>p2.at("/records/0/id").asLong());}
    @Test void unfilteredPageSizeAndDescendingIdRemainCompatible()throws Exception {var n=node(get(owner,"ALL","ALL",2,10),200);assertEquals(18,n.path("total").asLong());assertEquals(8,n.path("records").size());long previous=Long.MAX_VALUE;for(var row:n.path("records")){assertTrue(row.path("id").asLong()<previous);previous=row.path("id").asLong();}}
    @Test void outOfRangePageIsEmptyWithoutInventingDifferentCount()throws Exception {var n=node(get(owner,"REPORT","ALL",999,10),200);assertEquals(5,n.path("total").asLong());assertEquals(0,n.path("records").size());assertEquals(999,n.path("page").asInt());}
    @Test void otherUserSeesOnlyOwnCategoryRecords()throws Exception {var n=node(get(other,"REPORT","UNREAD",1,20),200);assertEquals(2,n.path("total").asLong());var mine=service.page(1,20,"ALL","ALL",owner).records().stream().map(NotificationModels.Notice::id).toList();for(var row:n.path("records"))assertFalse(mine.contains(row.path("id").asLong()));}
    @Test void forgedRecipientQueryNeverChangesOwner()throws Exception {var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications").param("category","REPORT").param("userId","101").param("recipientId","101").header("Authorization",other)).andReturn().getResponse();assertEquals(2,node(r,200).path("total").asLong());}
    @Test void invalidCaseWhitespaceAndInjectionFiltersAre400()throws Exception {for(String v:List.of("order","ORDER ","BAD","ORDER' OR 1=1","constructor",""))node(get(owner,v,"ALL",1,10),v.isEmpty()?200:400);for(String v:List.of("read","READ ","BAD","READ' OR 1=1"))node(get(owner,"ALL",v,1,10),400);}
    @Test void duplicateCategoryValuesAreRejected()throws Exception {for(String[] values:new String[][]{{"ORDER","REPORT"},{"ORDER","ORDER"}}){var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications").param("category",values).header("Authorization",owner)).andReturn().getResponse();node(r,400);}}
    @Test void invalidPagingStillReturns400()throws Exception {for(int p:new int[]{0,-1})node(get(owner,"ORDER","READ",p,10),400);for(int size:new int[]{0,21})node(get(owner,"ORDER","READ",1,size),400);}
    @Test void anonymousAndForgedIdentityRemain401EvenWithInvalidEnum()throws Exception {node(get(null,"INVALID","BAD",1,10),401);node(get("Bearer "+"A".repeat(43),"ORDER","READ",1,10),401);}
    @Test void expiredAndRevokedSessionsCannotFilter()throws Exception {sessions.logout(owner);node(get(owner,"ORDER","ALL",1,10),401);jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE user_id=102");node(get(other,"REPORT","UNREAD",1,10),401);}
    @Test void filteredReadIsPureAndDoesNotCreateMessages(){var before=stable();for(String category:List.of("ALL","ORDER","REPORT","MODERATION"))for(String status:List.of("ALL","READ","UNREAD"))service.page(1,20,status,category,owner);assertEquals(before,stable());}
    @Test void responseKeepsMinimumShapeAndSafeTargets()throws Exception {var n=page("REPORT","ALL");var first=n.path("records").get(0);assertEquals(Set.of("id","category","resourceId","target","title","body","occurredAt","createdAt","readAt"),new HashSet<>(first.propertyNames()));assertEquals("REPORT_DETAIL",first.path("target").asString());for(String secret:List.of("PRIVATE_HASH","recipientId","sourceKey","eventId",owner.substring(7)))assertFalse(n.toString().contains(secret));}
    @Test void globalUnreadCountIsIndependentOfCategoryRead(){assertEquals(11,service.unread(owner).unreadCount());service.page(1,20,"UNREAD","REPORT",owner);assertEquals(11,service.unread(owner).unreadCount());}
    @Test void readingOneFilteredNoticeMovesItBetweenStates(){var n=service.page(1,20,"UNREAD","REPORT",owner).records().get(0);service.read(n.id(),owner);assertEquals(2,service.page(1,20,"UNREAD","REPORT",owner).total());assertEquals(3,service.page(1,20,"READ","REPORT",owner).total());assertEquals(10,service.unread(owner).unreadCount());assertEquals(6,service.page(1,20,"UNREAD","ORDER",owner).total());}
    @Test void batchFromFilteredPageChangesOnlyExplicitReportIds(){var expected=service.page(1,20,"UNREAD","REPORT",owner).records().stream().map(NotificationModels.Notice::id).toList();service.readBatch(new NotificationModels.ReadBatch(expected),owner);assertEquals(0,service.page(1,20,"UNREAD","REPORT",owner).total());assertEquals(5,service.page(1,20,"READ","REPORT",owner).total());assertEquals(8,service.unread(owner).unreadCount());assertEquals(3,service.unread(other).unreadCount());assertEquals(6,service.page(1,20,"UNREAD","ORDER",owner).total());}
    @Test void batchWithOtherRecipientStillRollsBack(){var mine=service.page(1,20,"UNREAD","REPORT",owner).records().get(0);var theirs=service.page(1,20,"UNREAD","REPORT",other).records().get(0);var before=stable();assertThrows(com.campus.market.product.ProductNotFoundException.class,()->service.readBatch(new NotificationModels.ReadBatch(List.of(mine.id(),theirs.id())),owner));assertEquals(before,stable());}
    @Test void oldServiceOverloadMeansAllCategories(){assertEquals(service.page(1,10,"ALL","ALL",owner),service.page(1,10,"ALL",owner));assertEquals(service.page(1,10,"UNREAD","ALL",owner),service.page(1,10,"UNREAD",owner));}
    @Test void missingTableFails500InsteadOfEmptySuccess()throws Exception {jdbc.execute("RENAME TABLE user_notifications TO user_notifications_private_unavailable");try{node(get(owner,"REPORT","READ",1,10),500);}finally{jdbc.execute("RENAME TABLE user_notifications_private_unavailable TO user_notifications");}}
    @Test void repeatableReadRetainsCountAndPageAcrossConcurrentArrival(){var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(3,service.page(1,20,"UNREAD","REPORT",owner).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->notice(101,"REPORT",false))).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(3,service.page(1,20,"UNREAD","REPORT",owner).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(4,service.page(1,20,"UNREAD","REPORT",owner).total());}
}
