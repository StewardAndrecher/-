package com.campus.market.order;
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
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false","market.notifications.mq.enabled=true","market.notifications.mq.prefix=campus.notification.review102","market.notifications.mq.dispatch-enabled=false","market.notifications.mq.listener-auto-startup=false","market.notifications.mq.confirm-timeout-ms=1500","spring.rabbitmq.port=${LESSON74_MQ_PORT:1}","spring.rabbitmq.username=${LESSON74_MQ_USER:disabled}","spring.rabbitmq.password=${LESSON74_MQ_PASSWORD:disabled}","spring.rabbitmq.virtual-host=${LESSON74_MQ_VHOST:disabled}"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class OrderReviewNotificationMqIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson102_mq_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson102_mq_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired com.campus.market.notification.NotificationService notices;
    @Autowired NotificationOutbox outbox;@Autowired NotificationDispatcher dispatcher;@Autowired NotificationSender sender;@Autowired NotificationConsumer consumer;@Autowired NotificationMqSettings settings;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;@Autowired org.springframework.amqp.core.AmqpAdmin admin;@Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void fixtures(){assertEquals("/campus-lesson74-verify",System.getenv("LESSON74_MQ_VHOST"));listeners.stop();for(String q:List.of(settings.queue(),settings.parkingQueue()))admin.purgeQueue(q,true);
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
    @Autowired SessionService sessions;@Autowired OrderReviewService reviews;String buyer,seller,outsider;
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"review_"+id,"隔离验收")).accessToken();}
    @AfterEach void stop(){listeners.stop();}
    String enqueue(){reviews.create("1",new OrderReviewModels.Create(5L,"私有评价XYZ"),buyer);return jdbc.queryForObject("SELECT event_id FROM notification_outbox",String.class);}
    long inbox(){return jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications",Long.class);}
    void waitFor(java.util.function.BooleanSupplier test)throws Exception {long end=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();while(System.nanoTime()<end){if(test.getAsBoolean())return;Thread.sleep(50);}fail("私有MQ异步结果超时");}
    org.springframework.amqp.core.Message message(String event){return org.springframework.amqp.core.MessageBuilder.withBody(("{\"schema\":1,\"eventId\":\""+event+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).setMessageId(event).setContentType("application/json").build();}
    org.springframework.amqp.core.Message receive()throws Exception {final org.springframework.amqp.core.Message[] msg={null};waitFor(()->{msg[0]=rabbit.receive(settings.queue());return msg[0]!=null;});return msg[0];}
    @Test void persistentConfirmedPublishUsesDedicatedQueue()throws Exception {String event=enqueue();assertTrue(dispatcher.dispatchOne());var msg=receive();assertEquals(event,msg.getMessageProperties().getMessageId());assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,msg.getMessageProperties().getReceivedDeliveryMode());assertEquals(2,json.readTree(msg.getBody()).size());assertFalse(new String(msg.getBody(),java.nio.charset.StandardCharsets.UTF_8).contains("私有评价XYZ"));assertEquals(Set.of("schema","eventId"),new HashSet<>(json.readTree(msg.getBody()).propertyNames()));assertNotNull(jdbc.queryForObject("SELECT published_at FROM notification_outbox",String.class));}
    @Test void actualListenerCreatesInboxAndAcknowledges()throws Exception {enqueue();listeners.start();dispatcher.dispatchOne();waitFor(()->inbox()==1);assertEquals(102,jdbc.queryForObject("SELECT recipient_id FROM user_notifications",Long.class));assertEquals("ORDER_REVIEWS",notices.page(1,10,"ALL",seller).records().get(0).target());assertEquals("ORDER",jdbc.queryForObject("SELECT category FROM user_notifications",String.class));assertEquals(1,jdbc.queryForObject("SELECT resource_id FROM user_notifications",Long.class));assertFalse(jdbc.queryForObject("SELECT body FROM user_notifications",String.class).contains("私有评价XYZ"));waitFor(()->admin.getQueueInfo(settings.queue()).getMessageCount()==0);assertNotNull(jdbc.queryForObject("SELECT delivered_at FROM notification_outbox",String.class));}
    @Test void duplicateMessagesProduceOnlyOneNotification()throws Exception {String event=enqueue();listeners.start();for(int n=0;n<3;n++)rabbit.send(settings.exchange(),"notice",message(event));waitFor(()->inbox()==1);waitFor(()->admin.getQueueInfo(settings.queue()).getMessageCount()==0);assertEquals(1,inbox());}
    @Test void unrouteablePublishRetainsTaskAndRecovers()throws Exception {String event=enqueue();var binding=new org.springframework.amqp.core.Binding(settings.queue(),org.springframework.amqp.core.Binding.DestinationType.QUEUE,settings.exchange(),"notice",null);admin.removeBinding(binding);try{dispatcher.dispatchOne();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_reviews",Long.class));assertNull(jdbc.queryForObject("SELECT published_at FROM notification_outbox",String.class));assertEquals("DELIVERY_UNCONFIRMED",jdbc.queryForObject("SELECT last_error FROM notification_outbox",String.class));}finally{admin.declareBinding(binding);}jdbc.update("UPDATE notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)");dispatcher.dispatchOne();assertEquals(event,receive().getMessageProperties().getMessageId());}
    @Test void confirmBeforeLostConsumptionCanBeRepublished()throws Exception {enqueue();dispatcher.dispatchOne();receive();jdbc.update("UPDATE notification_outbox SET published_at=CURRENT_TIMESTAMP(6)-INTERVAL 31 SECOND");listeners.start();dispatcher.dispatchOne();waitFor(()->inbox()==1);}
    @Test void malformedAndUnknownMessagesAreRejected(){assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(message(UUID.randomUUID().toString())));var msg=org.springframework.amqp.core.MessageBuilder.withBody("{}".getBytes()).setMessageId("bad").build();assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(msg));assertEquals(0,inbox());}
    @Test void wrongMessageIdAndInjectedRecipientAreRejected(){String event=enqueue();var mismatch=message(event);mismatch.getMessageProperties().setMessageId(UUID.randomUUID().toString());assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(mismatch));var injected=org.springframework.amqp.core.MessageBuilder.withBody(("{\"schema\":1,\"eventId\":\""+event+"\",\"recipientId\":2}").getBytes()).setMessageId(event).build();assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(injected));assertEquals(0,inbox());}
    @Test void poisonMessageMovesToDurableParkingQueue()throws Exception {listeners.start();rabbit.send(settings.exchange(),"notice",message(UUID.randomUUID().toString()));waitFor(()->admin.getQueueInfo(settings.parkingQueue()).getMessageCount()==1);assertEquals(0,inbox());}
    @Test void consumerDatabaseFailureParksThenValidTaskCanRecover()throws Exception {enqueue();jdbc.execute("CREATE TRIGGER fail_mq_inbox BEFORE INSERT ON user_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");listeners.start();try{dispatcher.dispatchOne();waitFor(()->admin.getQueueInfo(settings.parkingQueue()).getMessageCount()==1);assertEquals(0,inbox());assertNull(jdbc.queryForObject("SELECT delivered_at FROM notification_outbox",String.class));}finally{jdbc.execute("DROP TRIGGER fail_mq_inbox");}jdbc.update("UPDATE notification_outbox SET published_at=CURRENT_TIMESTAMP(6)-INTERVAL 31 SECOND");dispatcher.dispatchOne();waitFor(()->inbox()==1);}
    @Test void deliveredTaskIsNotScheduledAgain()throws Exception {enqueue();listeners.start();dispatcher.dispatchOne();waitFor(()->inbox()==1);assertFalse(dispatcher.dispatchOne());}
}
