package com.campus.market.favorite;
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
class FavoriteBatchIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson118_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson118_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired FavoriteBatchService service;@Autowired FavoriteService favorites;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String owner,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("product_favorites","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'batch_owner','PRIVATE_HASH','甲'),(102,'batch_other','PRIVATE_HASH','乙'),(103,'batch_seller','PRIVATE_HASH','丙')");
        for(long id=1;id<=22;id++)jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status) VALUES(?,'隔离收藏样本','只用于本课测试',100,103,?)",id,id==2?"OFF_SHELF":id==3?"SOLD":"ON_SALE");
        for(long id=1;id<=12;id++)jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(101,?)",id);
        for(long id:List.of(1L,2L,13L))jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(102,?)",id);
        owner=issue(101);other=issue(102);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"batch_"+id,"隔离验收")).accessToken();}
    MockHttpServletResponse send(String token,String body)throws Exception {
        var request=post("/api/favorites/remove-batch").contentType("application/json").content(body);if(token!=null)request.header("Authorization",token);
        return mvc.perform(request).andReturn().getResponse();
    }
    MockHttpServletResponse batch(String token,List<Long> ids)throws Exception{return send(token,json.writeValueAsString(Map.of("productIds",ids)));}
    JsonNode node(MockHttpServletResponse r,int expected)throws Exception {assertEquals(expected,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    long count(long user){return jdbc.queryForObject("SELECT COUNT(*) FROM product_favorites WHERE user_id=?",Long.class,user);}
    List<Map<String,Object>> rows(){return jdbc.queryForList("SELECT * FROM product_favorites ORDER BY user_id,product_id");}
    @Test void selectedOwnRowsRemovedAndReceiptPreservesRequestOrder()throws Exception {
        var n=node(batch(owner,List.of(3L,1L)),200);assertEquals(101,n.path("userId").asLong());assertEquals(3,n.at("/productIds/0").asLong());assertEquals(1,n.at("/productIds/1").asLong());assertEquals(2,n.path("removedCount").asInt());assertEquals(10,count(101));assertEquals(3,count(102));
    }
    @Test void duplicateRequestIsNaturallyIdempotent()throws Exception {
        assertEquals(2,node(batch(owner,List.of(1L,2L)),200).path("removedCount").asInt());assertEquals(0,node(batch(owner,List.of(1L,2L)),200).path("removedCount").asInt());assertEquals(10,count(101));
    }
    @Test void unknownAndOtherUsersOnlyFavoritesAreNoops()throws Exception {
        var before=rows();var n=node(batch(owner,List.of(13L,999999L)),200);assertEquals(0,n.path("removedCount").asInt());assertEquals(before,rows());
    }
    @Test void mixedPresentAndMissingRowsDoNotExpandScope()throws Exception {
        var n=node(batch(owner,List.of(1L,99999L,2L)),200);assertEquals(2,n.path("removedCount").asInt());assertEquals(10,count(101));assertEquals(3,count(102));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM product_favorites WHERE user_id=101 AND product_id=3",Long.class));
    }
    @Test void unavailableAndSoldFavoritesCanBeRemoved()throws Exception {
        node(batch(owner,List.of(2L,3L)),200);assertEquals(10,count(101));assertEquals("OFF_SHELF",jdbc.queryForObject("SELECT status FROM products WHERE id=2",String.class));assertEquals("SOLD",jdbc.queryForObject("SELECT status FROM products WHERE id=3",String.class));
    }
    @Test void twentyIdsAreAcceptedAndTwentyOneRejected()throws Exception {
        for(long id=13;id<=20;id++)jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(101,?)",id);
        var ids=java.util.stream.LongStream.rangeClosed(1,21).boxed().toList();var before=rows();node(batch(owner,ids),400);assertEquals(before,rows());assertEquals(20,node(batch(owner,ids.subList(0,20)),200).path("removedCount").asInt());assertEquals(0,count(101));
    }
    @Test void invalidMixedIdsNeverPartiallyDelete()throws Exception {
        for(List<Long> ids:List.of(List.of(1L,0L),List.of(1L,-1L),List.of(1L,9007199254740992L),List.of(1L,1L))){var before=rows();node(batch(owner,ids),400);assertEquals(before,rows());}
    }
    @Test void emptyMissingAndNullBodyDoNotDelete()throws Exception {
        var before=rows();for(String body:List.of("{\"productIds\":[]}","{}","null","{\"productIds\":null}","[]"))node(send(owner,body),400);assertEquals(before,rows());
    }
    @Test void jsonMustContainIntegerIdsWithoutCoercion()throws Exception {
        var before=rows();for(String value:List.of("\"1\"","1.0","1e0","true","null","{}","[]","9999999999999999999999999999999999"))node(send(owner,"{\"productIds\":["+value+"]}"),400);assertEquals(before,rows());
    }
    @Test void unknownFieldsAndDuplicateObjectKeysAreRejected()throws Exception {
        var before=rows();for(String body:List.of("{\"productIds\":[1],\"userId\":102}","{\"productIds\":[1],\"productIds\":[2]}","{\"ids\":[1]}","{\"productIds\":1}"))node(send(owner,body),400);assertEquals(before,rows());
    }
    @Test void malformedJsonIs400WithoutWrite()throws Exception {var before=rows();node(send(owner,"{\"productIds\":[1"),400);assertEquals(before,rows());}
    @Test void missingAndForgedSessionsAre401()throws Exception {
        var before=rows();node(batch(null,List.of(1L)),401);node(batch("Bearer "+"A".repeat(43),List.of(1L)),401);assertEquals(before,rows());
    }
    @Test void revokedAndExpiredSessionsCannotMutate()throws Exception {
        var before=rows();sessions.logout(owner);node(batch(owner,List.of(1L)),401);jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE user_id=102");node(batch(other,List.of(1L)),401);assertEquals(before,rows());
    }
    @Test void validlyBoundEmptyRequestStillAuthenticatesFirst()throws Exception {node(send(null,"{\"productIds\":[]}"),401);}
    @Test void otherAccountCannotDeleteOwnersRows()throws Exception {
        assertEquals(1,node(batch(other,List.of(1L,4L)),200).path("removedCount").asInt());assertEquals(12,count(101));assertEquals(2,count(102));
    }
    @Test void sameAccountNewSessionSeesRemoval()throws Exception {
        String second=issue(101);node(batch(second,List.of(1L,2L)),200);assertEquals(10,favorites.page(1,20,owner).total());
    }
    @Test void receiptIsMinimalAndDoesNotRevealProductOrPeer()throws Exception {
        var r=batch(owner,List.of(1L,22L));var n=node(r,200);assertEquals("no-cache",r.getHeader("Pragma"));assertEquals(Set.of("userId","productIds","removedCount"),new HashSet<>(n.propertyNames()));
        for(String secret:List.of("PRIVATE_HASH","title","nickname","sellerId",owner.substring(7),other.substring(7)))assertFalse(n.toString().contains(secret));
    }
    @Test void productMetadataVersionsAndOtherBusinessTablesRemainUnchanged()throws Exception {
        var before=stable();node(batch(owner,List.of(1L,2L,3L)),200);assertEquals(before,stable());
    }
    Map<String,List<Map<String,Object>>> stable(){var value=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("users","products","login_sessions","market_orders","order_events","order_reviews","user_notifications","notification_outbox","order_timeout_outbox"))value.put(table,jdbc.queryForList("SELECT * FROM "+table));return value;}
    @Test void transactionRollbackRestoresEntireSelection(){
        var before=rows();new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{assertEquals(3,service.remove(new FavoriteBatchModels.Request(List.of(1L,2L,3L)),owner).removedCount());status.setRollbackOnly();});assertEquals(before,rows());
    }
    @Test void databaseFailureIs500NotSuccessReceipt()throws Exception {
        jdbc.execute("RENAME TABLE product_favorites TO product_favorites_private_unavailable");try{assertEquals(500,batch(owner,List.of(1L)).getStatus());}finally{jdbc.execute("RENAME TABLE product_favorites_private_unavailable TO product_favorites");}assertEquals(12,count(101));
    }
    @Test void concurrentDuplicateBatchesDeleteEachRowAtMostOnce()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);var ids=java.util.stream.LongStream.rangeClosed(1,12).boxed().toList();
        try{var a=pool.submit(()->{start.await();return service.remove(new FavoriteBatchModels.Request(ids),owner);});var b=pool.submit(()->{start.await();return service.remove(new FavoriteBatchModels.Request(ids),owner);});start.countDown();assertEquals(12,a.get(5,java.util.concurrent.TimeUnit.SECONDS).removedCount()+b.get(5,java.util.concurrent.TimeUnit.SECONDS).removedCount());assertEquals(0,count(101));assertEquals(3,count(102));}finally{pool.shutdownNow();}
    }
    @Test void laterNewFavoriteOutsideExplicitIdsSurvives()throws Exception {
        node(batch(owner,List.of(1L,2L)),200);favorites.add(13,owner);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM product_favorites WHERE user_id=101 AND product_id=13",Long.class));
    }
    @Test void existingSingleAddRemoveAndIdsRemainCompatible()throws Exception {
        node(batch(owner,List.of(1L)),200);assertTrue(favorites.add(1,owner).favorite());assertEquals(List.of(1L),favorites.selectedIds(List.of(1L),owner));assertFalse(favorites.remove(1,owner).favorite());assertEquals(11,count(101));
    }
    @Test void directServiceAlsoValidatesNullsWithoutPartialWrite(){
        var before=rows();for(FavoriteBatchModels.Request r:Arrays.asList(null,new FavoriteBatchModels.Request(null),new FavoriteBatchModels.Request(Arrays.asList(1L,null))))assertThrows(com.campus.market.product.InvalidProductException.class,()->service.remove(r,owner));assertEquals(before,rows());
    }
}
