package com.campus.market.order;
import com.campus.market.product.*;
import com.campus.market.order.mq.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import com.campus.market.auth.SessionService;
import com.campus.market.user.UserResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.campus.market.order.OrderModels.*;
@SpringBootTest(properties={"spring.datasource.url=${LESSON74_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=false","market.orders.mq.enabled=true","market.orders.mq.dispatch-enabled=false","market.orders.mq.listener-auto-startup=false","market.orders.mq.prefix=campus.order.verify74","market.orders.mq.delay-ms=200","market.orders.mq.confirm-timeout-ms=1500","spring.rabbitmq.port=${LESSON74_MQ_PORT:1}","spring.rabbitmq.username=${LESSON74_MQ_USER:disabled}","spring.rabbitmq.password=${LESSON74_MQ_PASSWORD:disabled}","spring.rabbitmq.virtual-host=${LESSON74_MQ_VHOST:disabled}","market.orders.simulated-payment-enabled=true","market.orders.timeout-sweep-enabled=false","spring.data.redis.port=${LESSON67_REDIS_PORT:1}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON74_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson74_verify\\?.*")
class OrderMqIntegrationTest {
 @Autowired org.springframework.context.ApplicationContext context;@Autowired OrderTimeoutOutbox outbox;@Autowired OrderTimeoutSender sender;@Autowired OrderTimeoutDispatcher dispatcher;@Autowired OrderTimeoutConsumer consumer;@Autowired OrderMqSettings settings;@Autowired RabbitTemplate rabbit;@Autowired AmqpAdmin admin;@Autowired RabbitListenerEndpointRegistry listeners;
 @Autowired OrderRepository orders;
 @Autowired OrderTimeoutSweep sweep;
 @Autowired ProductDetailCache cache;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired OrderService service;@Autowired ProductService products;
 @Autowired ProductRepository repository;@Autowired SessionService sessions;@Autowired PlatformTransactionManager transactions;@Autowired PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();String hash,sellerToken,buyerToken,otherToken;long product,seller,buyer,other;
 @BeforeAll void hash(){hash=encoder.encode("Campus2026!");}
 @BeforeEach void fixtures()throws Exception {
  assertTrue(System.getenv("LESSON74_DB_URL").contains("/campus_lesson74_verify?"));
  assertEquals("/campus-lesson74-verify",System.getenv("LESSON74_MQ_VHOST"));
  listeners.stop();for(String queue:List.of(settings.delayQueue(),settings.workQueue(),settings.parkingQueue()))admin.purgeQueue(queue,true);
  jdbc.update("DELETE FROM market_orders");jdbc.update("DELETE FROM product_comments");jdbc.update("DELETE FROM product_favorites");jdbc.update("DELETE FROM purchase_intents");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  seller=user("order_seller");buyer=user("order_buyer");other=user("order_other");
  sellerToken=token(seller,"order_seller");buyerToken=token(buyer,"order_buyer");otherToken=token(other,"order_other");
  product=repository.insert("订单教材","原始描述",3500,seller,null,"BOOK");
 }
 long user(String name){jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES(?,?,?)",name,hash,name);return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);}
 String token(long id,String username){return "Bearer "+sessions.issueToken(new UserResponse(id,username,username)).accessToken();}
 String uuid(){return UUID.randomUUID().toString();}
 JsonNode check(MockHttpServletRequestBuilder request,int status)throws Exception {var r=mvc.perform(request).andReturn().getResponse();assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));return r.getContentAsByteArray().length==0?null:json.readTree(r.getContentAsString(StandardCharsets.UTF_8));}
 MockHttpServletRequestBuilder create(long id,long version,String key,String auth){var q=post("/api/products/"+id+"/orders").contentType("application/json").content(json.writeValueAsString(Map.of("productVersion",version,"requestId",key)));return auth==null?q:q.header("Authorization",auth);}
 long created()throws Exception{return check(create(product,0,uuid(),buyerToken),201).get("order").get("id").asLong();}
 long version(){return repository.findById(product).orElseThrow().version();}
 long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM market_orders",Long.class);}





 @Test void mqSchedulingExistsWithoutSweepAndUsesSeparateScheduler(){assertNotSame(context.getBean("taskScheduler"),context.getBean("orderMqTaskScheduler"));assertNotNull(context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class));}
 @AfterEach void stop(){listeners.stop();}
 void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}fail("MQ异步结果未在时限内出现");}
 String event(long id){return jdbc.queryForObject("SELECT event_id FROM order_timeout_outbox WHERE order_id=?",String.class,id);}
 long pending(){return jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE published_at IS NULL",Long.class);}
 void expire(long id){jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);}
 Message message(long id,String event){return MessageBuilder.withBody(("{\"schema\":1,\"eventId\":\""+event+"\",\"orderId\":"+id+"}").getBytes(StandardCharsets.UTF_8)).setMessageId(event).setContentType(MessageProperties.CONTENT_TYPE_JSON).build();}
 void sendDirect(long id){rabbit.send(settings.workExchange(),"timeout",message(id,event(id)));}
 @Test void orderCreationWritesExactlyOneOutboxTask()throws Exception{long id=created();assertNotNull(event(id));assertEquals(1,pending());assertEquals(0,jdbc.queryForObject("SELECT attempts FROM order_timeout_outbox WHERE order_id=?",Integer.class,id));}
 @Test void duplicateCreationDoesNotWriteAnotherTask(){String key=uuid();service.create(product,new Create(0L,key),buyerToken);service.create(product,new Create(0L,key),buyerToken);assertEquals(1,pending());}
 @Test void outerRollbackRemovesOrderAndOutbox(){var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(2);tx.executeWithoutResult(s->{service.create(product,new Create(0L,uuid()),buyerToken);s.setRollbackOnly();});assertEquals(0,count());assertEquals(0,pending());assertEquals(0,version());}
 @Test void failedOutboxInsertRollsBackOrderAndVersion(){jdbc.execute("CREATE TRIGGER reject_outbox BEFORE INSERT ON order_timeout_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated failure'");try{assertThrows(RuntimeException.class,()->service.create(product,new Create(0L,uuid()),buyerToken));}finally{jdbc.execute("DROP TRIGGER reject_outbox");}assertEquals(0,count());assertEquals(0,version());}
 @Test void noPendingTaskReturnsNull(){assertNull(outbox.claim());}
 @Test void claimHasLeaseAndDoesNotPublish()throws Exception{created();var job=outbox.claim();assertEquals(1,job.attempts());assertNotNull(job.leaseToken());assertNull(outbox.claim());assertEquals(1,pending());}
 @Test void concurrentClaimsCannotOwnSameTask()throws Exception{created();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->outbox.claim());var b=pool.submit(()->outbox.claim());assertEquals(1,(a.get()!=null?1:0)+(b.get()!=null?1:0));}finally{pool.shutdownNow();}}
 @Test void expiredLeaseCanRecoverButStaleOwnerCannotMarkSent()throws Exception{long id=created();var old=outbox.claim();jdbc.update("UPDATE order_timeout_outbox SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE order_id=?",id);var next=outbox.claim();assertNotEquals(old.leaseToken(),next.leaseToken());assertFalse(outbox.published(old));assertFalse(outbox.failed(old));assertTrue(outbox.published(next));assertEquals(0,pending());}
 @Test void failedDeliverySchedulesDatabaseRetry()throws Exception{long id=created();var job=outbox.claim();assertTrue(outbox.failed(job));assertNull(outbox.claim());assertEquals("DELIVERY_UNCONFIRMED",jdbc.queryForObject("SELECT last_error FROM order_timeout_outbox WHERE order_id=?",String.class,id));assertEquals(1,pending());}
 @Test void confirmedPersistentPublishMarksOutboxSent()throws Exception{long id=created();assertTrue(dispatcher.dispatchOne());assertEquals(0,pending());assertFalse(dispatcher.dispatchOne());var message=receiveWork();assertEquals(event(id),message.getMessageProperties().getMessageId());assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getReceivedDeliveryMode());}
 Message receiveWork()throws Exception{final Message[] found={null};waitFor(()->{found[0]=rabbit.receive(settings.workQueue());return found[0]!=null;});return found[0];}
 @Test void ttlDeadLetterRoutesToWorkQueue()throws Exception{long id=created();dispatcher.dispatchOne();var msg=receiveWork();assertTrue(msg.getMessageProperties().getHeaders().containsKey("x-death"));assertEquals(event(id),msg.getMessageProperties().getMessageId());}
 @Test void unrouteableMessageIsNotMarkedSent()throws Exception{long id=created();var binding=new Binding(settings.delayQueue(),Binding.DestinationType.QUEUE,settings.delayExchange(),"delay",null);admin.removeBinding(binding);try{assertTrue(dispatcher.dispatchOne());assertEquals(1,pending());assertEquals("PENDING_PAYMENT",service.detail(id,buyerToken).status());}finally{admin.declareBinding(binding);}}
 @Test void deliveryFailureThenRecoveryPublishesSameEvent()throws Exception{long id=created();var binding=new Binding(settings.delayQueue(),Binding.DestinationType.QUEUE,settings.delayExchange(),"delay",null);String event=event(id);admin.removeBinding(binding);try{dispatcher.dispatchOne();assertEquals(1,pending());}finally{admin.declareBinding(binding);}jdbc.update("UPDATE order_timeout_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE order_id=?",id);dispatcher.dispatchOne();assertEquals(0,pending());assertEquals(event,receiveWork().getMessageProperties().getMessageId());}
 @Test void actualListenerClosesExpiredOrderAndAcknowledges()throws Exception{long id=created();expire(id);listeners.start();dispatcher.dispatchOne();waitFor(()->"CLOSED_TIMEOUT".equals(service.detail(id,buyerToken).status()));assertEquals(2,service.timeline(id,buyerToken).events().size());assertEquals("SYSTEM",service.timeline(id,buyerToken).events().get(1).actorRole());waitFor(()->admin.getQueueInfo(settings.workQueue()).getMessageCount()==0);}
 @Test void paidOrderIsNeverClosedByLateMessage()throws Exception{long id=created();service.simulatePayment(id,buyerToken);consumer.consume(message(id,event(id)));assertEquals("PAID",service.detail(id,buyerToken).status());assertEquals(2,service.timeline(id,buyerToken).events().size());}
 @Test void completedOrderIsNeverClosed()throws Exception{long id=created();service.simulatePayment(id,buyerToken);service.deliver(id,sellerToken);service.complete(id,buyerToken);consumer.consume(message(id,event(id)));assertEquals("COMPLETED",service.detail(id,buyerToken).status());assertEquals(4,service.timeline(id,buyerToken).events().size());}
 @Test void cancelledOrderIsNotChanged()throws Exception{long id=created();service.cancel(id,buyerToken);consumer.consume(message(id,event(id)));assertEquals("CANCELLED",service.detail(id,buyerToken).status());assertEquals(2,service.timeline(id,buyerToken).events().size());}
 @Test void earlyDeliveryCannotCloseOrder()throws Exception{long id=created();consumer.consume(message(id,event(id)));assertEquals("PENDING_PAYMENT",service.detail(id,buyerToken).status());assertEquals(1,service.timeline(id,buyerToken).events().size());}
 @Test void repeatedConsumptionDoesNotDuplicateTimeoutOrRelease()throws Exception{long id=created();expire(id);var msg=message(id,event(id));consumer.consume(msg);long after=version();consumer.consume(msg);assertEquals(after,version());assertEquals(2,service.timeline(id,buyerToken).events().size());}
 @Test void scannerAndMqShareIdempotentCloseRule()throws Exception{long id=created();expire(id);assertTrue(service.closeExpired(id));long after=version();consumer.consume(message(id,event(id)));assertEquals(after,version());assertEquals(2,service.timeline(id,buyerToken).events().size());}
 @Test void businessRollbackCausesConsumptionToFailWithoutClosing()throws Exception{long id=created();expire(id);jdbc.execute("CREATE TRIGGER reject_event_mq BEFORE INSERT ON order_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated failure'");try{assertThrows(RuntimeException.class,()->consumer.consume(message(id,event(id))));assertEquals("PENDING_PAYMENT",service.detail(id,buyerToken).status());assertEquals(1,service.timeline(id,buyerToken).events().size());}finally{jdbc.execute("DROP TRIGGER reject_event_mq");}consumer.consume(message(id,event(id)));assertEquals("CLOSED_TIMEOUT",service.detail(id,buyerToken).status());}
 @Test void poisonMessageGoesToParkingQueue()throws Exception{listeners.start();rabbit.send(settings.workExchange(),"timeout",new Message("invalid".getBytes(StandardCharsets.UTF_8)));waitFor(()->admin.getQueueInfo(settings.parkingQueue()).getMessageCount()==1);assertEquals(0,count());}
 @Test void businessFailureRetriesThenParksAndScannerCanRecover()throws Exception{long id=created();expire(id);jdbc.execute("CREATE TRIGGER reject_event_mq BEFORE INSERT ON order_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated failure'");try{listeners.start();sendDirect(id);waitFor(()->admin.getQueueInfo(settings.parkingQueue()).getMessageCount()==1);assertEquals("PENDING_PAYMENT",service.detail(id,buyerToken).status());assertEquals(1,service.timeline(id,buyerToken).events().size());}finally{listeners.stop();jdbc.execute("DROP TRIGGER reject_event_mq");}assertTrue(service.closeExpired(id));assertEquals("CLOSED_TIMEOUT",service.detail(id,buyerToken).status());}
 @Test void mismatchedOutboxIdentityIsRejected()throws Exception{long id=created();assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(message(id,uuid())));}
 @Test void malformedContractsAreRejected()throws Exception{long id=created();for(String body:List.of("{}","[]","{\"schema\":2,\"eventId\":\""+event(id)+"\",\"orderId\":"+id+"}","x".repeat(257))){assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(new Message(body.getBytes(StandardCharsets.UTF_8))));}}
 @Test void messageIdMustMatchBody()throws Exception{long id=created();var msg=message(id,event(id));msg.getMessageProperties().setMessageId(uuid());assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(msg));}
 @Test void topologyUsesDurableQuorumQueues(){for(var q:new OrderMqConfiguration().orderMqTopology(settings).getDeclarablesByType(org.springframework.amqp.core.Queue.class)){assertTrue(q.isDurable());assertEquals("quorum",q.getArguments().get("x-queue-type"));assertEquals(q.getName(),admin.declareQueue(q));}for(String queue:List.of(settings.delayQueue(),settings.workQueue(),settings.parkingQueue())){var info=admin.getQueueInfo(queue);assertNotNull(info);}assertEquals(0,admin.getQueueInfo(settings.delayQueue()).getConsumerCount());}
}
