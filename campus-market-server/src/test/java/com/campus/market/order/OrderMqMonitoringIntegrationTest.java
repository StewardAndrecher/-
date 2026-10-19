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
@SpringBootTest(properties={"market.monitoring.enabled=true","market.monitoring.token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","market.monitoring.poll-enabled=false","spring.datasource.url=${LESSON75_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=false","market.orders.mq.enabled=true","market.orders.mq.dispatch-enabled=false","market.orders.mq.listener-auto-startup=false","market.orders.mq.prefix=campus.order.verify75","market.orders.mq.delay-ms=200","market.orders.mq.confirm-timeout-ms=1500","spring.rabbitmq.port=${LESSON74_MQ_PORT:1}","spring.rabbitmq.username=${LESSON74_MQ_USER:disabled}","spring.rabbitmq.password=${LESSON74_MQ_PASSWORD:disabled}","spring.rabbitmq.virtual-host=${LESSON74_MQ_VHOST:disabled}","market.orders.simulated-payment-enabled=true","market.orders.timeout-sweep-enabled=false","spring.data.redis.port=${LESSON67_REDIS_PORT:1}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON75_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson75_verify\\?.*")
class OrderMqMonitoringIntegrationTest {
 @Autowired org.springframework.context.ApplicationContext context;@Autowired OrderTimeoutOutbox outbox;@Autowired OrderTimeoutSender sender;@Autowired OrderTimeoutDispatcher dispatcher;@Autowired OrderTimeoutConsumer consumer;@Autowired OrderMqSettings settings;@Autowired RabbitTemplate rabbit;@Autowired AmqpAdmin admin;@Autowired RabbitListenerEndpointRegistry listeners;
 @Autowired OrderMqMonitor monitor;@Autowired io.micrometer.prometheusmetrics.PrometheusMeterRegistry registry;
 @Autowired OrderRepository orders;
 @Autowired OrderTimeoutSweep sweep;
 @Autowired ProductDetailCache cache;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired OrderService service;@Autowired ProductService products;
 @Autowired ProductRepository repository;@Autowired SessionService sessions;@Autowired PlatformTransactionManager transactions;@Autowired PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();String hash,sellerToken,buyerToken,otherToken;long product,seller,buyer,other;
 @BeforeAll void hash(){hash=encoder.encode("Campus2026!");}
 @BeforeEach void fixtures()throws Exception {
  assertTrue(System.getenv("LESSON75_DB_URL").contains("/campus_lesson75_verify?"));
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






 @AfterEach void stop(){listeners.stop();}
 double metric(String name){return registry.get(name).gauge().value();}
 double counter(String name,String result){return registry.get(name).tag("result",result).counter().count();}
 void expire(long id){jdbc.update("UPDATE market_orders SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE id=?",id);}
 org.springframework.amqp.core.Message message(long id)throws Exception {String event=jdbc.queryForObject("SELECT event_id FROM order_timeout_outbox WHERE order_id=?",String.class,id);return MessageBuilder.withBody(json.writeValueAsBytes(Map.of("schema",1,"eventId",event,"orderId",id))).setMessageId(event).build();}
 void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}fail("MQ异步结果未在时限内出现");}
 @Test void pendingRetryAndLeaseAreActualDatabaseValues()throws Exception{created();outbox.claim();monitor.poll();assertEquals(1,metric("campus.order.outbox.pending"));assertEquals(1,metric("campus.order.outbox.retrying"));assertEquals(1,metric("campus.order.outbox.leased"));assertTrue(metric("campus.order.outbox.oldest.seconds")>=0);assertEquals(1,metric("campus.order.mq.broker.up"));}
 @Test void publishedTasksAreExcludedFromPending()throws Exception{created();assertTrue(dispatcher.dispatchOne());monitor.poll();assertEquals(0,metric("campus.order.outbox.pending"));assertEquals(0,metric("campus.order.outbox.retrying"));}
 @Test void brokerConfirmedCounterTracksRealSend()throws Exception{created();double before=counter("campus.order.mq.publish","confirmed");sender.send(outbox.claim());assertEquals(before+1,counter("campus.order.mq.publish","confirmed"));}
 @Test void returnedMessageIncrementsFailureCounter()throws Exception{created();var job=outbox.claim();var binding=new Binding(settings.delayQueue(),Binding.DestinationType.QUEUE,settings.delayExchange(),"delay",null);admin.removeBinding(binding);double before=counter("campus.order.mq.publish","failed");try{assertThrows(IllegalStateException.class,()->sender.send(job));}finally{admin.declareBinding(binding);}assertEquals(before+1,counter("campus.order.mq.publish","failed"));}
 @Test void committedCloseAndReplayHaveDifferentOutcomes()throws Exception{long id=created();expire(id);double closed=counter("campus.order.mq.consume","closed"),noop=counter("campus.order.mq.consume","noop");consumer.consume(message(id));consumer.consume(message(id));assertEquals(closed+1,counter("campus.order.mq.consume","closed"));assertEquals(noop+1,counter("campus.order.mq.consume","noop"));assertEquals(2,service.timeline(id,buyerToken).events().size());}
 @Test void rejectedMessageDoesNotBecomeMetricLabel(){double before=counter("campus.order.mq.consume","rejected");assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->consumer.consume(new Message("private-payload-do-not-export".getBytes(StandardCharsets.UTF_8))));assertEquals(before+1,counter("campus.order.mq.consume","rejected"));assertFalse(registry.scrape().contains("private-payload-do-not-export"));}
 @Test void realParkingBacklogIsVisible()throws Exception{listeners.start();rabbit.send(settings.workExchange(),"timeout",new Message("bad".getBytes(StandardCharsets.UTF_8)));waitFor(()->admin.getQueueInfo(settings.parkingQueue()).getMessageCount()==1);monitor.poll();assertEquals(1,registry.get("campus.order.mq.queue.ready").tag("queue","parking").gauge().value());assertEquals(1,metric("campus.order.mq.broker.up"));}
 @Test void privateScrapeDoesNotChangePublicHealthExposure()throws Exception{monitor.poll();mvc.perform(get("/api/ops/metrics")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());var response=mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+"a".repeat(64))).andReturn().getResponse();assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("campus_order_mq_broker_up 1.0"));assertFalse(response.getContentAsString().contains("a".repeat(64)));assertEquals("no-store",response.getHeader("Cache-Control"));mvc.perform(get("/api/health/readiness")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());mvc.perform(get("/api/prometheus")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());mvc.perform(get("/api/env")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());}
}
