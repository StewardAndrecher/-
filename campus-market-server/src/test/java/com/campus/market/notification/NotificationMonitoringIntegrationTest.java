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
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false","market.monitoring.enabled=true","market.monitoring.token=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","market.monitoring.poll-enabled=false","market.notifications.mq.enabled=true","market.notifications.mq.prefix=campus.notification.verify94","market.notifications.mq.dispatch-enabled=false","market.notifications.mq.listener-auto-startup=false","market.notifications.mq.confirm-timeout-ms=1500","spring.rabbitmq.port=${LESSON74_MQ_PORT:1}","spring.rabbitmq.username=${LESSON74_MQ_USER:disabled}","spring.rabbitmq.password=${LESSON74_MQ_PASSWORD:disabled}","spring.rabbitmq.virtual-host=${LESSON74_MQ_VHOST:disabled}"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class NotificationMonitoringIntegrationTest {
    static Connection control;static String schema,url;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson94_mq_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");registry.add("spring.datasource.url",()->url);
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson94_mq_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired NotificationOutbox outbox;
    @Autowired NotificationMonitor monitor;
    @Autowired NotificationMetrics metrics;
    @Autowired NotificationMqSettings settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.core.AmqpAdmin admin;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired io.micrometer.prometheusmetrics.PrometheusMeterRegistry registry;
    @Autowired MockMvc mvc;
    @MockitoSpyBean NotificationOverviewService source;
    @BeforeEach void fixtures(){
        assertEquals("/campus-lesson74-verify",System.getenv("LESSON74_MQ_VHOST"));
        listeners.stop();for(String q:List.of(settings.queue(),settings.parkingQueue()))admin.purgeQueue(q,true);
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(1,'notice_private94','unused','私有监控验收')");
    }
    @AfterEach void stop(){listeners.stop();}
    double gauge(String suffix){return registry.get("campus.notification."+suffix).gauge().value();}
    String enqueue(){
        new TransactionTemplate(transactions).executeWithoutResult(tx->outbox.enqueue("TEST:"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT),1,"ORDER",1,"PRIVATE_NOTIFICATION_TITLE","PRIVATE_NOTIFICATION_BODY"));
        return jdbc.queryForObject("SELECT event_id FROM notification_outbox",String.class);
    }
    void waitFor(java.util.function.BooleanSupplier condition)throws Exception {
        long end=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}fail("通知监控私有队列等待超时");
    }
    @Test void realMetricsRequireIndependentTokenAndContainNoNotificationContent()throws Exception {
        enqueue();monitor.pollDatabase();monitor.pollBroker();
        assertEquals(401,mvc.perform(get("/api/ops/metrics")).andReturn().getResponse().getStatus());
        var result=mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+"b".repeat(64))).andReturn().getResponse();
        assertEquals(200,result.getStatus());assertEquals("no-store",result.getHeader("Cache-Control"));String text=result.getContentAsString();
        assertTrue(text.contains("campus_notification_outbox_pending 1.0"));assertFalse(text.contains("PRIVATE_NOTIFICATION"));assertFalse(text.contains("notice_private94"));
    }
    @Test void databaseCountersFollowDeliveryWithoutCountingReadState(){
        String event=enqueue();monitor.pollDatabase();assertEquals(1,gauge("outbox.pending"));assertEquals(0,gauge("outbox.delivered"));
        outbox.deliver(event);monitor.pollDatabase();assertEquals(0,gauge("outbox.pending"));assertEquals(1,gauge("outbox.delivered"));
        jdbc.update("UPDATE user_notifications SET read_at=CURRENT_TIMESTAMP(6)");monitor.pollDatabase();assertEquals(1,gauge("outbox.delivered"));
    }
    @Test void passiveBrokerSamplingReadsQueuedMessageWithoutConsuming()throws Exception {
        rabbit.convertAndSend(settings.exchange(),"notice","private sampling fixture");
        waitFor(()->admin.getQueueInfo(settings.queue()).getMessageCount()==1);
        monitor.pollBroker();assertEquals(1,gauge("mq.broker.up"));
        assertEquals(1,registry.get("campus.notification.mq.queue.ready").tag("queue","work").gauge().value());
        assertEquals(1,admin.getQueueInfo(settings.queue()).getMessageCount());
    }
    @Test void passiveBrokerSamplingDetectsActualConsumer()throws Exception {
        listeners.start();waitFor(()->admin.getQueueInfo(settings.queue()).getConsumerCount()>0);monitor.pollBroker();
        assertTrue(registry.get("campus.notification.mq.queue.consumers").tag("queue","work").gauge().value()>0);
    }
    @Test void absentQueueProducesUnknownNotZero(){
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("admin",admin);
        beans.addBean("names",new NotificationMqSettings("campus.notification.absent94",100));
        var another=new NotificationMonitor(source,metrics,beans.getBeanProvider(org.springframework.amqp.core.AmqpAdmin.class),beans.getBeanProvider(NotificationMqSettings.class),true,false);
        another.pollBroker();assertEquals(0,gauge("mq.broker.up"));assertTrue(Double.isNaN(registry.get("campus.notification.mq.queue.ready").tag("queue","work").gauge().value()));
    }
    @Test void scrapingMetricsDoesNotQueryTheDatabaseAgain()throws Exception {
        enqueue();monitor.pollDatabase();var before=jdbc.queryForMap("SELECT * FROM notification_outbox");
        clearInvocations(source);
        assertEquals(200,mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+"b".repeat(64))).andReturn().getResponse().getStatus());
        verifyNoInteractions(source);assertEquals(before,jdbc.queryForMap("SELECT * FROM notification_outbox"));
    }
}
