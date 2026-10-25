package com.campus.market.admin;
import com.campus.market.order.*;
import com.campus.market.notification.*;
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
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false","market.notifications.mq.enabled=true","market.notifications.mq.prefix=campus.notification.resolve105","market.notifications.mq.dispatch-enabled=false","market.notifications.mq.listener-auto-startup=false","market.notifications.mq.confirm-timeout-ms=1500","spring.rabbitmq.port=${LESSON74_MQ_PORT:1}","spring.rabbitmq.username=${LESSON74_MQ_USER:disabled}","spring.rabbitmq.password=${LESSON74_MQ_PASSWORD:disabled}","spring.rabbitmq.virtual-host=${LESSON74_MQ_VHOST:disabled}"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class AdminIssueResolutionMqIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson105_mq_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson105_mq_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired com.campus.market.notification.NotificationService notices;
    @Autowired NotificationOutbox outbox;@Autowired NotificationDispatcher dispatcher;@Autowired NotificationSender sender;@Autowired NotificationConsumer consumer;@Autowired NotificationMqSettings settings;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;@Autowired org.springframework.amqp.core.AmqpAdmin admin;@Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();
    @Autowired SessionService sessions;@Autowired OrderIssueService issues;@Autowired AdminOrderIssueService adminIssues;
    String buyer,seller,outsider,adminToken,secondAdmin,lastKey;
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"fixture_"+id,"隔离验收")).accessToken();}
    @BeforeEach void fixtures(){assertEquals("/campus-lesson74-verify",System.getenv("LESSON74_MQ_VHOST"));listeners.stop();for(String q:List.of(settings.queue(),settings.parkingQueue()))admin.purgeQueue(q,true);
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
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(201,'ADMIN'),(202,'ADMIN')");adminToken=issue(201);secondAdmin=issue(202);
        issues.create("1",new OrderIssueModels.Create("ITEM_CONDITION","交接时发现商品存在故障，请协助核对处理。"),buyer);
    }
    @AfterEach void stop(){listeners.stop();}
    String enqueue(){lastKey=UUID.randomUUID().toString();adminIssues.resolve("1","101",new AdminOrderIssueModels.Resolve("ANSWERED","已记录你的反馈，请双方核对商品及交接情况。",lastKey),adminToken);return jdbc.queryForObject("SELECT event_id FROM notification_outbox",String.class);}
    long inbox(){return jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications",Long.class);}
    void waitFor(java.util.function.BooleanSupplier test)throws Exception {long end=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();while(System.nanoTime()<end){if(test.getAsBoolean())return;Thread.sleep(50);}fail("私有MQ异步结果超时");}
    org.springframework.amqp.core.Message message(String event){return org.springframework.amqp.core.MessageBuilder.withBody(("{\"schema\":1,\"eventId\":\""+event+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).setMessageId(event).setContentType("application/json").build();}
    org.springframework.amqp.core.Message receive()throws Exception {final org.springframework.amqp.core.Message[] msg={null};waitFor(()->{msg[0]=rabbit.receive(settings.queue());return msg[0]!=null;});return msg[0];}
    @Test void persistentMessageContainsOnlySchemaAndEventId()throws Exception {String event=enqueue();assertTrue(dispatcher.dispatchOne());var msg=receive();assertEquals(event,msg.getMessageProperties().getMessageId());assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,msg.getMessageProperties().getReceivedDeliveryMode());assertEquals(Set.of("schema","eventId"),new HashSet<>(json.readTree(msg.getBody()).propertyNames()));assertNotNull(jdbc.queryForObject("SELECT published_at FROM notification_outbox",String.class));}
    @Test void actualListenerDeliversOnlyToApplicant()throws Exception {enqueue();listeners.start();dispatcher.dispatchOne();waitFor(()->inbox()==1);assertEquals(101,jdbc.queryForObject("SELECT recipient_id FROM user_notifications",Long.class));assertEquals("OWN_ORDER_ISSUE",notices.page(1,10,"ALL",buyer).records().get(0).target());assertEquals("ORDER",jdbc.queryForObject("SELECT category FROM user_notifications",String.class));String body=jdbc.queryForObject("SELECT body FROM user_notifications",String.class);assertFalse(body.contains("故障"));assertFalse(body.contains("已记录你的反馈"));assertNotNull(jdbc.queryForObject("SELECT delivered_at FROM notification_outbox",String.class));}
    @Test void duplicateDeliveryCreatesOneInboxRecord()throws Exception {String event=enqueue();listeners.start();for(int i=0;i<3;i++)rabbit.send(settings.exchange(),"notice",message(event));waitFor(()->inbox()==1);waitFor(()->admin.getQueueInfo(settings.queue()).getMessageCount()==0);assertEquals(1,inbox());}
    @Test void unrouteablePublishRetainsResolutionAndRecovers()throws Exception {enqueue();var binding=new org.springframework.amqp.core.Binding(settings.queue(),org.springframework.amqp.core.Binding.DestinationType.QUEUE,settings.exchange(),"notice",null);admin.removeBinding(binding);try{dispatcher.dispatchOne();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_issue_resolutions",Long.class));assertNull(jdbc.queryForObject("SELECT published_at FROM notification_outbox",String.class));assertEquals("DELIVERY_UNCONFIRMED",jdbc.queryForObject("SELECT last_error FROM notification_outbox",String.class));}finally{admin.declareBinding(binding);}jdbc.update("UPDATE notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)");listeners.start();dispatcher.dispatchOne();waitFor(()->inbox()==1);}
    @Test void consumerFailureParksThenRecoversWithoutReprocessingResolution()throws Exception {enqueue();jdbc.execute("CREATE TRIGGER private_result_inbox_failure BEFORE INSERT ON user_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");listeners.start();try{dispatcher.dispatchOne();waitFor(()->admin.getQueueInfo(settings.parkingQueue()).getMessageCount()==1);assertEquals(0,inbox());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_issue_resolutions",Long.class));}finally{jdbc.execute("DROP TRIGGER private_result_inbox_failure");}jdbc.update("UPDATE notification_outbox SET published_at=CURRENT_TIMESTAMP(6)-INTERVAL 31 SECOND");dispatcher.dispatchOne();waitFor(()->inbox()==1);}
    @Test void replayedAdminRequestDoesNotSendAnotherNotification()throws Exception {enqueue();listeners.start();dispatcher.dispatchOne();waitFor(()->inbox()==1);assertTrue(adminIssues.resolve("1","101",new AdminOrderIssueModels.Resolve("ANSWERED","已记录你的反馈，请双方核对商品及交接情况。",lastKey),adminToken).replayed());assertFalse(dispatcher.dispatchOne());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class));assertEquals(1,inbox());}
}
