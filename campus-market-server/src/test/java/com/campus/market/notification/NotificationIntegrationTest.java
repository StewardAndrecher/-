package com.campus.market.notification;
import com.campus.market.notification.mq.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.campus.market.product.ProductDetailCache;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class NotificationIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson92_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson92_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    @MockitoSpyBean ProductDetailCache cache;
    final JsonMapper json=JsonMapper.builder().build();String admin,seller,other,secondAdmin;
    @BeforeEach void fixtures(){
        com.campus.market.support.FinancialFixtureCleanup.clear(jdbc);
        for(String table:List.of("product_reports","product_moderation","product_moderation_events","order_timeout_outbox","order_events","market_orders","product_favorites","products","user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'review_admin','not-used','审核员'),(102,'review_seller','not-used','卖家'),(103,'review_other','not-used','其他账号'),(104,'review_admin2','not-used','审核员乙')");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(101,'ADMIN'),(104,'ADMIN')");
        jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status,category_code) VALUES(201,'审核教材','整改前描述',3500,102,'ON_SALE','BOOK'),(202,'下架台灯','',100,102,'OFF_SHELF','DAILY'),(203,'已售出物品','',100,102,'SOLD','OTHER'),(204,'历史商品','',100,NULL,'ON_SALE','UNCLASSIFIED')");
        admin=token(101,"review_admin","审核员");seller=token(102,"review_seller","卖家");other=token(103,"review_other","其他账号");secondAdmin=token(104,"review_admin2","审核员乙");clearInvocations(cache);
    }
    String token(long id,String name,String nick){return "Bearer "+sessions.issueToken(new UserResponse(id,name,nick)).accessToken();}
    Map<String,Object> payload(String action,long version,String reason,String request){return Map.of("action",action,"version",version,"reason",reason,"requestId",request);}
    String key(){return UUID.randomUUID().toString();}
    org.springframework.mock.web.MockHttpServletResponse action(long id,String actor,Map<String,?> body)throws Exception {return mvc.perform(post("/api/admin/products/"+id+"/moderation/actions").header("Authorization",actor).contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();}
    JsonNode success(org.springframework.mock.web.MockHttpServletResponse r)throws Exception {assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode block(long id,long version)throws Exception {return success(action(id,admin,payload("BLOCK",version,"平台发现违规内容",key())));}
    JsonNode get(String path,String actor)throws Exception {var request=getRequest(path,actor);var response=mvc.perform(request).andReturn().getResponse();assertEquals(200,response.getStatus(),response.getContentAsString());if(path.startsWith("/api/admin")||path.contains("/moderation")||path.contains("/feed")||path.contains("/reports")||path.contains("/notifications"))assertEquals("no-store",response.getHeader("Cache-Control"));return json.readTree(response.getContentAsByteArray());}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getRequest(String path,String actor){var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);return actor==null?request:request.header("Authorization",actor);}
    int getStatus(String path,String actor)throws Exception {return mvc.perform(getRequest(path,actor)).andReturn().getResponse().getStatus();}
    org.springframework.mock.web.MockHttpServletResponse appeal(long id,String actor,long version,String reason,String request)throws Exception {return mvc.perform(post("/api/products/"+id+"/moderation/appeals").header("Authorization",actor).contentType("application/json").content(json.writeValueAsString(Map.of("version",version,"reason",reason,"requestId",request)))).andReturn().getResponse();}
    int sellerStatus(long id,String status,long version)throws Exception {return mvc.perform(put("/api/products/"+id+"/status").header("Authorization",seller).contentType("application/json").content(json.writeValueAsString(Map.of("status",status,"version",version)))).andReturn().getResponse().getStatus();}
    long version(long id){return jdbc.queryForObject("SELECT version FROM products WHERE id=?",Long.class,id);}
    int events(){return jdbc.queryForObject("SELECT COUNT(*) FROM product_moderation_events",Integer.class);}
    String state(long id){return jdbc.queryForObject("SELECT state FROM product_moderation WHERE product_id=?",String.class,id);}
    int createOrder()throws Exception {return mvc.perform(post("/api/products/201/orders").header("Authorization",other).contentType("application/json").content(json.writeValueAsString(Map.of("productVersion",0,"requestId",key())))).andReturn().getResponse().getStatus();}
    List<Integer> parallel(Callable<Integer> a,Callable<Integer> b)throws Exception {var pool=Executors.newFixedThreadPool(2);try{var x=pool.submit(a);var y=pool.submit(b);return List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}

    MockHttpServletResponse submit(long product,String actor,String category,String reason,String request)throws Exception {var r=post("/api/products/"+product+"/reports").contentType("application/json").content(json.writeValueAsString(Map.of("category",category,"reason",reason,"requestId",request)));if(actor!=null)r.header("Authorization",actor);return mvc.perform(r).andReturn().getResponse();}
    long report()throws Exception {return created(submit(201,other,"MISLEADING","商品描述疑似不实",key())).path("ticket").path("id").asLong();}
    JsonNode created(MockHttpServletResponse r)throws Exception {assertEquals(201,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    MockHttpServletResponse resolve(long id,String actor,String action,String reason,long version,Long productVersion,String key)throws Exception {var data=new HashMap<String,Object>();data.put("action",action);data.put("reason",reason);data.put("version",version);data.put("productVersion",productVersion);data.put("requestId",key);var r=post("/api/admin/reports/"+id+"/resolve").contentType("application/json").content(json.writeValueAsString(data));if(actor!=null)r.header("Authorization",actor);return mvc.perform(r).andReturn().getResponse();}
    @Autowired NotificationOutbox outbox; @Autowired PlatformTransactionManager transactions;
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM product_reports",Integer.class);}
    void enqueue(String source,long user,String type,long resource){new TransactionTemplate(transactions).executeWithoutResult(tx->outbox.enqueue(source,user,type,resource,"私有通知","仅用于通知测试"));}
    String source(){return "TEST:"+key().toUpperCase(Locale.ROOT);}
    String event(){return jdbc.queryForObject("SELECT event_id FROM notification_outbox ORDER BY created_at DESC,event_id DESC LIMIT 1",String.class);}
    long inbox(){return jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications",Long.class);}
    long tasks(){return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class);}
    long notice(long user){enqueue(source(),user,"ORDER",201);String id=event();outbox.deliver(id);return jdbc.queryForObject("SELECT id FROM user_notifications WHERE event_id=?",Long.class,id);}
    MockHttpServletResponse readNotice(long id,String auth)throws Exception {var request=post("/api/notifications/"+id+"/read");if(auth!=null)request.header("Authorization",auth);return mvc.perform(request).andReturn().getResponse();}
    @Test void privateNotificationEndpointsRequireLogin()throws Exception {assertEquals(401,getStatus("/api/notifications",null));assertEquals(401,getStatus("/api/notifications/unread-count",null));assertEquals(401,readNotice(1,null).getStatus());}
    @Test void notificationListFiltersAndIdsAreValidated()throws Exception {for(String q:List.of("page=0","size=21","status=BAD"))assertEquals(400,getStatus("/api/notifications?"+q,other));assertEquals(400,readNotice(0,other).getStatus());}
    @Test void recipientIsolationAndNoAdminOverride()throws Exception {long id=notice(103);assertEquals(1,get("/api/notifications",other).path("total").asInt());assertEquals(0,get("/api/notifications",seller).path("total").asInt());assertEquals(404,readNotice(id,seller).getStatus());assertEquals(404,readNotice(id,admin).getStatus());assertEquals(404,readNotice(999999,other).getStatus());}
    @Test void responseDoesNotExposeSourceOrRecipient()throws Exception {notice(103);var n=get("/api/notifications",other).path("records").get(0);for(String field:List.of("eventId","recipientId","sourceKey","token","requestId"))assertFalse(n.has(field));}
    @Test void ownReadDecrementsUnreadCount()throws Exception {long id=notice(103);assertEquals(1,get("/api/notifications/unread-count",other).path("unreadCount").asInt());var n=success(readNotice(id,other));assertFalse(n.path("readAt").isNull());assertEquals(0,get("/api/notifications/unread-count",other).path("unreadCount").asInt());}
    @Test void repeatedReadPreservesFirstReadTime()throws Exception {long id=notice(103);String time=success(readNotice(id,other)).path("readAt").asString();assertEquals(time,success(readNotice(id,other)).path("readAt").asString());}
    @Test void concurrentReadsAreIdempotent()throws Exception {long id=notice(103);assertEquals(List.of(200,200),parallel(()->readNotice(id,other).getStatus(),()->readNotice(id,other).getStatus()));assertEquals(0,get("/api/notifications/unread-count",other).path("unreadCount").asInt());}
    @Test void paginationAndUnreadFilteringAreStable()throws Exception {long first=notice(103);notice(103);notice(103);success(readNotice(first,other));var a=get("/api/notifications?size=2",other);var b=get("/api/notifications?size=2&page=2",other);assertEquals(3,a.path("total").asInt());assertEquals(1,b.path("records").size());assertTrue(a.path("records").get(1).path("id").asLong()>b.path("records").get(0).path("id").asLong());assertEquals(2,get("/api/notifications?status=UNREAD",other).path("total").asInt());}
    @Test void readFailureLeavesUnreadState()throws Exception {long id=notice(103);jdbc.execute("CREATE TRIGGER fail_notice_read BEFORE UPDATE ON user_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");try{assertEquals(500,readNotice(id,other).getStatus());assertEquals(1,get("/api/notifications/unread-count",other).path("unreadCount").asInt());}finally{jdbc.execute("DROP TRIGGER fail_notice_read");}}
    @Test void deliveryIsIdempotentAndDoesNotResetReadTime()throws Exception {long id=notice(103);String key=event();String at=success(readNotice(id,other)).path("readAt").asString();assertFalse(outbox.deliver(key));assertEquals(1,inbox());assertEquals(at,success(readNotice(id,other)).path("readAt").asString());}
    @Test void concurrentDeliveryCreatesOnlyOneInboxRow()throws Exception {enqueue(source(),103,"ORDER",201);String event=event();var codes=parallel(()->outbox.deliver(event)?1:0,()->outbox.deliver(event)?1:0);assertEquals(1,codes.stream().mapToInt(Integer::intValue).sum());assertEquals(1,inbox());}
    @Test void unknownSourceCannotCreateInbox(){assertThrows(NotificationOutbox.UnknownNotificationEvent.class,()->outbox.deliver(key()));assertEquals(0,inbox());}
    @Test void enqueueRequiresRealTransaction(){assertThrows(RuntimeException.class,()->outbox.enqueue("TEST:ONE",103,"ORDER",201,"标题","内容"));assertEquals(0,tasks());}
    @Test void duplicateSourceCannotSilentlyReplacePayload(){String key=source();enqueue(key,103,"ORDER",201);assertThrows(RuntimeException.class,()->enqueue(key,103,"ORDER",201));assertEquals(1,tasks());}
    @Test void outerRollbackRemovesOutbox(){new TransactionTemplate(transactions).executeWithoutResult(tx->{outbox.enqueue("TEST:ROLLBACK",103,"ORDER",201,"标题","内容");tx.setRollbackOnly();});assertEquals(0,tasks());}
    @Test void inboxFailureRollsBackDeliveryMarkerThenCanRetry(){enqueue(source(),103,"ORDER",201);String event=event();jdbc.execute("CREATE TRIGGER fail_notice_insert BEFORE INSERT ON user_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");try{assertThrows(RuntimeException.class,()->outbox.deliver(event));assertNull(jdbc.queryForObject("SELECT delivered_at FROM notification_outbox WHERE event_id=?",String.class,event));assertEquals(0,inbox());}finally{jdbc.execute("DROP TRIGGER fail_notice_insert");}assertTrue(outbox.deliver(event));assertEquals(1,inbox());}
    @Test void emptyOutboxHasNoClaim(){assertNull(outbox.claim());}
    @Test void concurrentClaimsHaveOnlyOneLease()throws Exception {enqueue(source(),103,"ORDER",201);assertEquals(1,parallel(()->outbox.claim()!=null?1:0,()->outbox.claim()!=null?1:0).stream().mapToInt(Integer::intValue).sum());}
    @Test void expiredLeaseRecoversAndOldOwnerCannotAcknowledge(){enqueue(source(),103,"ORDER",201);var a=outbox.claim();jdbc.update("UPDATE notification_outbox SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND");var b=outbox.claim();assertNotEquals(a.leaseToken(),b.leaseToken());assertFalse(outbox.published(a));assertFalse(outbox.failed(a));assertTrue(outbox.published(b));}
    @Test void failureSchedulesBackoff(){enqueue(source(),103,"ORDER",201);var job=outbox.claim();assertTrue(outbox.failed(job));assertNull(outbox.claim());assertEquals("DELIVERY_UNCONFIRMED",jdbc.queryForObject("SELECT last_error FROM notification_outbox",String.class));}
    @Test void brokerConfirmWithoutConsumptionIsReclaimedAfterThirtySeconds(){enqueue(source(),103,"ORDER",201);var job=outbox.claim();assertTrue(outbox.published(job));assertNull(outbox.claim());jdbc.update("UPDATE notification_outbox SET published_at=CURRENT_TIMESTAMP(6)-INTERVAL 31 SECOND");assertNotNull(outbox.claim());}
    @Test void deliveredTaskIsNeverRepublishedEvenWithoutPublishedMarker(){enqueue(source(),103,"ORDER",201);String event=event();outbox.deliver(event);assertNull(outbox.claim());assertEquals(1,inbox());}
    @Test void orderCreationNotifiesBothParticipantsOnly()throws Exception {assertEquals(201,createOrder());assertEquals(2,tasks());assertEquals(Set.of(102L,103L),new HashSet<>(jdbc.queryForList("SELECT recipient_id FROM notification_outbox",Long.class)));assertEquals(0,inbox());}
    @Test void orderCreationReplayDoesNotDuplicateNotifications()throws Exception {String key=key();var body=json.writeValueAsString(Map.of("productVersion",0,"requestId",key));for(int n=0;n<2;n++)assertEquals(n==0?201:200,mvc.perform(post("/api/products/201/orders").header("Authorization",other).contentType("application/json").content(body)).andReturn().getResponse().getStatus());assertEquals(2,tasks());}
    long createdOrder()throws Exception {assertEquals(201,createOrder());return jdbc.queryForObject("SELECT id FROM market_orders",Long.class);}
    @Test void fullOrderLifecycleProducesEightTasksWithSimulationLabel()throws Exception {long id=createdOrder();for(var step:List.of(Map.entry("simulate-payment",other),Map.entry("deliver",seller),Map.entry("complete",other)))assertEquals(200,mvc.perform(post("/api/orders/"+id+"/"+step.getKey()).header("Authorization",step.getValue())).andReturn().getResponse().getStatus());assertEquals(8,tasks());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE body LIKE '%不扣款%'",Long.class));}
    @Test void cancelledOrderProducesTwoMoreTasks()throws Exception {long id=createdOrder();assertEquals(200,mvc.perform(post("/api/orders/"+id+"/cancel").header("Authorization",other)).andReturn().getResponse().getStatus());assertEquals(4,tasks());}
    @Autowired com.campus.market.order.OrderService orderService;
    @Test void timedOutOrderProducesSystemClosureNotifications()throws Exception {long id=createdOrder();jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);assertTrue(orderService.closeExpired(id));assertEquals(4,tasks());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE body LIKE '%超时关闭%'",Long.class));}
    @Test void notificationFailureRollsBackOrderAndEvents()throws Exception {jdbc.execute("CREATE TRIGGER fail_notification BEFORE INSERT ON notification_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");try{assertEquals(500,createOrder());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM market_orders",Long.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_events",Long.class));assertEquals(0,version(201));}finally{jdbc.execute("DROP TRIGGER fail_notification");}}
    @Test void moderationNotifiesOnlySellerAndReplayDoesNotDuplicate()throws Exception {String key=key();success(action(201,admin,payload("BLOCK",0,"平台处理原因",key)));success(action(201,admin,payload("BLOCK",0,"平台处理原因",key)));assertEquals(1,tasks());assertEquals(102,jdbc.queryForObject("SELECT recipient_id FROM notification_outbox",Long.class));assertEquals("MODERATION",jdbc.queryForObject("SELECT category FROM notification_outbox",String.class));}
    @Test void appealAndReleaseEachNotifySeller()throws Exception {block(201,0);success(appeal(201,seller,1,"已经整改请复核",key()));success(action(201,admin,payload("RELEASE",2,"复核通过",key())));assertEquals(3,tasks());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE body LIKE '%商品仍下架%'",Long.class));}
    @Test void legacyProductWithoutSellerHasNoModerationRecipient()throws Exception {block(204,0);assertEquals(0,tasks());}
    @Test void dismissedReportNotifiesReporterOnly()throws Exception {long id=report();success(resolve(id,admin,"DISMISS","证据不足",0,null,key()));assertEquals(1,tasks());assertEquals(103,jdbc.queryForObject("SELECT recipient_id FROM notification_outbox",Long.class));assertEquals("REPORT",jdbc.queryForObject("SELECT category FROM notification_outbox",String.class));}
    @Test void reportBlockSeparatesSellerAndReporterContent()throws Exception {long id=report();success(resolve(id,admin,"BLOCK","核实存在问题",0,0L,key()));assertEquals(2,tasks());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=102 AND category='MODERATION'",Long.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=103 AND category='REPORT'",Long.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=102 AND category='REPORT'",Long.class));}
    @Test void reportResultNotificationFailureRollsBackWholeBlock()throws Exception {long id=report();jdbc.execute("CREATE TRIGGER fail_report_notification BEFORE INSERT ON notification_outbox FOR EACH ROW BEGIN IF NEW.category='REPORT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'; END IF; END");try{assertEquals(500,resolve(id,admin,"BLOCK","核实存在问题",0,0L,key()).getStatus());assertEquals(0,tasks());assertEquals(0,events());assertEquals(0,version(201));assertEquals("OPEN",get("/api/reports/"+id,other).path("status").asString());}finally{jdbc.execute("DROP TRIGGER fail_report_notification");}}
    @Test void databaseRejectsInvalidNotificationTarget(){assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO notification_outbox(event_id,source_key,recipient_id,category,resource_id,title,body) VALUES(?, 'TEST:BAD',103,'BAD',201,'标题','内容')",key()));}
}
