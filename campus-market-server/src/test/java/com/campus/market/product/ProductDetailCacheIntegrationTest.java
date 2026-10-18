package com.campus.market.product;

import com.campus.market.auth.SessionService;
import com.campus.market.media.ImageService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
    "spring.datasource.url=${LESSON67_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
    "spring.datasource.username=root", "spring.datasource.password=",
    "market.product-cache.enabled=true", "market.product-cache.prefix=campus:lesson67:test:",
    "spring.data.redis.port=${LESSON67_REDIS_PORT:1}"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON67_DB_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson67_verify\\?.*")
class ProductDetailCacheIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository repository;
    @Autowired ProductService service;
    @Autowired ProductDetailCache cache;
    @Autowired com.campus.market.order.OrderRepository orders;
    @Autowired StringRedisTemplate redis;
    @Autowired SessionService sessions;
    @Autowired ImageService images;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper json = JsonMapper.builder().build();
    final String prefix = "campus:lesson67:test:";
    long id;
    String ownerToken;
    String buyerToken;

    String key(long version) { return prefix + id + ":" + version; }
    JsonNode check(MockHttpServletRequestBuilder request, int status) throws Exception {
        var r = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
        return r.getContentAsByteArray().length == 0 ? null : json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    String login(String user) throws Exception {
        return check(post("/api/users/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",user,"password","Campus2026!"))),200)
            .get("accessToken").asText();
    }
    @BeforeEach void fixtures() throws Exception {
        assertTrue(System.getenv("LESSON67_DB_URL").contains("/campus_lesson67_verify?"));
        // 只删除本测试前缀；不使用FLUSHDB/FLUSHALL。
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix+"*").build())) {
            cursor.forEachRemaining(redis::delete);
        }
        jdbc.update("DELETE FROM product_favorites"); jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM product_images"); jdbc.update("DELETE FROM login_sessions"); jdbc.update("DELETE FROM users");
        for (String name : new String[]{"redis_owner", "redis_buyer"}) {
            check(post("/api/users/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        }
        ownerToken=login("redis_owner"); buyerToken=login("redis_buyer");
        id=check(post("/api/products").header("Authorization","Bearer "+ownerToken).contentType("application/json")
            .content("{\"title\":\"缓存教材\",\"description\":\"第67课中文商品正文\",\"priceInCents\":3500,\"categoryCode\":\"BOOK\"}"),201).get("id").asLong();
    }
    ProductResponse detail() { return service.getProduct(id,null); }
    MockHttpServletRequestBuilder edit(String title,long version) {
        return put("/api/products/"+id).header("Authorization","Bearer "+ownerToken).contentType("application/json")
            .content(json.writeValueAsString(Map.of("title",title,"description","更新描述","priceInCents",5800,"version",version,"categoryCode","DIGITAL")));
    }
    MockHttpServletRequestBuilder status(String state,long version) {
        return put("/api/products/"+id+"/status").header("Authorization","Bearer "+ownerToken).contentType("application/json")
            .content(json.writeValueAsString(Map.of("status",state,"version",version)));
    }
    @Test void hitSkipsFullBodyQueryButStillChecksDatabaseState() {
        var spy=spy(new ProductRepository(jdbc));var reader=new ProductService(spy,sessions,images,cache,orders);
        var first=reader.getProduct(id,null);var second=reader.getProduct(id,null);
        assertEquals(first,second);verify(spy,times(1)).findById(id);verify(spy,times(2)).findDetailState(id);
    }
    @Test void storesReadableChineseJsonWithBoundedTtlAndNoAccountData() {
        detail();String value=redis.opsForValue().get(key(0));assertNotNull(value);
        var node=json.readTree(value);assertEquals("缓存教材",node.get("title").asText());
        assertEquals(7,node.size());
        for(String privateField:new String[]{"seller","nickname","favoriteCount","username","password","accessToken","authorization","status"})
            assertFalse(node.has(privateField),privateField);
        long ttl=redis.getExpire(key(0),TimeUnit.MILLISECONDS);assertTrue(ttl>0 && ttl<=75_000);
    }
    @Test void editEvictsOldVersionAndLoadsNewBody() throws Exception {
        detail();assertTrue(redis.hasKey(key(0)));check(edit("新版数码教材",0),200);
        assertFalse(redis.hasKey(key(0)));var result=detail();assertEquals("新版数码教材",result.title());
        assertEquals(5800,result.priceInCents());assertEquals("DIGITAL",result.category().code());assertEquals(1,result.version());
        assertTrue(redis.hasKey(key(1)));
    }
    @Test void offShelfNeverUsesPublicCacheAndKeepsOwnerAccessRules() throws Exception {
        detail();check(status("OFF_SHELF",0),200);assertFalse(redis.hasKey(key(0)));
        check(get("/api/products/"+id),404);
        check(get("/api/products/"+id).header("Authorization","Bearer "+buyerToken),404);
        assertEquals("OFF_SHELF",check(get("/api/products/"+id).header("Authorization","Bearer "+ownerToken),200).get("status").asText());
        check(get("/api/products/"+id).header("Authorization","Bearer "+"A".repeat(43)),401);
        assertFalse(redis.hasKey(key(1)));
    }
    @Test void soldNeverLeaksThroughCachedBody() throws Exception {
        var stale=detail();check(status("SOLD",0),200);cache.putPublic(stale);
        check(get("/api/products/"+id),404);
        check(get("/api/products/"+id).header("Authorization","Bearer "+buyerToken),404);
        assertEquals("SOLD",check(get("/api/products/"+id).header("Authorization","Bearer "+ownerToken),200).get("status").asText());
        assertFalse(redis.hasKey(key(1)));
    }
    @Test void rollbackRetainsOldBodyAndDoesNotInvalidateBeforeCommit() {
        detail();var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(state->{
            service.updateProduct(id,new UpdateProductRequest("事务内教材","事务内",4500L,null,0L),"Bearer "+ownerToken);
            assertTrue(redis.hasKey(key(0)));state.setRollbackOnly();
        });
        assertTrue(redis.hasKey(key(0)));assertEquals("缓存教材",detail().title());assertFalse(redis.hasKey(key(1)));
    }
    @Test void evictionRunsOnlyAfterSuccessfulOuterCommit() {
        detail();new TransactionTemplate(transactions).executeWithoutResult(state->{
            service.updateProduct(id,new UpdateProductRequest("提交后教材","事务内",4500L,null,0L),"Bearer "+ownerToken);
            assertTrue(redis.hasKey(key(0)));
        });
        assertFalse(redis.hasKey(key(0)));assertEquals("提交后教材",detail().title());
    }
    @Test void oldReaderRefillAfterEditCannotBecomeCurrent() throws Exception {
        var stale=detail();check(edit("并发新版教材",0),200);
        cache.putPublic(stale);assertTrue(redis.hasKey(key(0)));
        assertEquals("并发新版教材",detail().title());assertEquals(1,detail().version());
    }
    @Test void oldReaderRefillAfterOffShelfCannotMakeProductPublic() throws Exception {
        var stale=detail();check(status("OFF_SHELF",0),200);cache.putPublic(stale);
        assertTrue(redis.hasKey(key(0)));check(get("/api/products/"+id),404);
        assertFalse(redis.hasKey(key(1)));
    }
    @Test void nicknameChangesAreVisibleEvenWhenBodyIsCached() throws Exception {
        detail();String body=redis.opsForValue().get(key(0));
        check(put("/api/users/me/nickname").header("Authorization","Bearer "+ownerToken).contentType("application/json")
            .content("{\"nickname\":\"新昵称卖家\",\"expectedNickname\":\"redis_owner\"}"),200);
        assertEquals("新昵称卖家",detail().seller().nickname());assertEquals(body,redis.opsForValue().get(key(0)));
    }
    @Test void favoriteCountChangesAreVisibleEvenWhenBodyIsCached() throws Exception {
        assertEquals(0,detail().favoriteCount());String body=redis.opsForValue().get(key(0));
        check(put("/api/favorites/"+id).header("Authorization","Bearer "+buyerToken),200);
        assertEquals(1,detail().favoriteCount());check(delete("/api/favorites/"+id).header("Authorization","Bearer "+buyerToken),200);
        assertEquals(0,detail().favoriteCount());assertEquals(body,redis.opsForValue().get(key(0)));
    }
    @Test void brokenJsonFallsBackAndRepairsTheCache() {
        redis.opsForValue().set(key(0),"{broken",Duration.ofSeconds(60));
        assertEquals("缓存教材",detail().title());assertEquals(id,json.readTree(redis.opsForValue().get(key(0))).get("id").asLong());
    }
    @Test void foreignIdOrVersionIsNeverReturned() {
        var original=detail();var value=json.readTree(redis.opsForValue().get(key(0)));
        ((tools.jackson.databind.node.ObjectNode)value).put("id",id+99);redis.opsForValue().set(key(0),value.toString(),Duration.ofSeconds(60));
        assertEquals(original,detail());
        ((tools.jackson.databind.node.ObjectNode)value).put("id",id).put("version",99);redis.opsForValue().set(key(0),value.toString(),Duration.ofSeconds(60));
        assertEquals(original,detail());
    }
    @Test void incompleteOrNullCacheValueIsTreatedAsMiss() {
        for(String value:new String[]{"null","{}","{\"id\":"+id+",\"version\":0}"}) {
            redis.opsForValue().set(key(0),value,Duration.ofSeconds(60));assertEquals("缓存教材",detail().title());
        }
    }
    @Test void invalidAndMissingProductsDoNotCreateCacheEntries() throws Exception {
        check(get("/api/products/0"),400);check(get("/api/products/9223372036854775807"),404);
        assertFalse(redis.hasKey(prefix+"0:0"));assertFalse(redis.hasKey(prefix+"9223372036854775807:0"));
    }
    @Test void legacyProductsWithoutSellerKeepNullSellerOnHits() {
        jdbc.update("UPDATE products SET seller_id=NULL WHERE id=?",id);
        assertNull(detail().seller());assertNull(detail().seller());assertTrue(redis.hasKey(key(0)));
    }
    @Test void coverAndCategoryRoundTripWithoutChangingResponseContract() throws Exception {
        String image="7b1075d8-1e80-4343-91ed-85ec3241bf21";
        long seller=repository.findById(id).orElseThrow().seller().id();
        jdbc.update("INSERT INTO product_images(id,owner_id,width,height) VALUES(?,?,72,72)",image,seller);
        jdbc.update("UPDATE products SET cover_image_id=? WHERE id=?",image,id);
        var miss=detail();var hit=detail();assertEquals(miss,hit);assertEquals("/api/images/"+image,hit.coverImageUrl());
        var response=mvc.perform(get("/api/products/"+id)).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals(11,json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).size());
    }
    @Test void noOpAndRejectedWritesDoNotEvictValidCache() throws Exception {
        detail();String value=redis.opsForValue().get(key(0));
        check(status("ON_SALE",0),200);check(status("OFF_SHELF",99),409);
        check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+buyerToken)
            .contentType("application/json").content("{\"status\":\"OFF_SHELF\",\"version\":0}"),403);
        assertEquals(value,redis.opsForValue().get(key(0)));
    }
    @Test void writeTransactionReadsOwnChangesWithoutPublishingUncommittedCache() {
        detail();new TransactionTemplate(transactions).executeWithoutResult(state->{
            service.updateProduct(id,new UpdateProductRequest("未提交教材","中间正文",4500L,null,0L),"Bearer "+ownerToken);
            assertEquals("未提交教材",detail().title());assertFalse(redis.hasKey(key(1)));state.setRollbackOnly();
        });
        assertEquals("缓存教材",detail().title());assertFalse(redis.hasKey(key(1)));
    }
    @Test void readOnlyRollbackDoesNotPopulateCache() {
        var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);
        tx.executeWithoutResult(state->{detail();assertFalse(redis.hasKey(key(0)));state.setRollbackOnly();});
        assertFalse(redis.hasKey(key(0)));
        tx.executeWithoutResult(state->{detail();assertFalse(redis.hasKey(key(0)));});
        assertTrue(redis.hasKey(key(0)));
    }
    @Test void disabledCacheUsesTheOriginalDatabasePath() {
        var disabled=new ProductDetailCache(redis,false,prefix,60);var spy=spy(new ProductRepository(jdbc));
        var reader=new ProductService(spy,sessions,images,disabled,orders);reader.getProduct(id,null);reader.getProduct(id,null);
        verify(spy,times(2)).findById(id);verify(spy,never()).findDetailState(id);assertFalse(redis.hasKey(key(0)));
    }
    @Test void redisConnectionFailureFallsBackAndTemporarilySkipsRedis() {
        var config=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).shutdownTimeout(Duration.ZERO).build();
        var factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",1),config);
        factory.afterPropertiesSet();factory.start();
        try {
            var broken=new ProductDetailCache(new StringRedisTemplate(factory),true,prefix,60);
            var spy=spy(new ProductRepository(jdbc));var reader=new ProductService(spy,sessions,images,broken,orders);
            assertTimeout(Duration.ofSeconds(3),()->assertEquals("缓存教材",reader.getProduct(id,null).title()));
            assertFalse(broken.canRead());clearInvocations(spy);
            assertEquals("缓存教材",reader.getProduct(id,null).title());verify(spy,never()).findDetailState(id);verify(spy).findById(id);
            new TransactionTemplate(transactions).executeWithoutResult(state->broken.evictAfterCommit(id,0));
        } finally { factory.destroy(); }
    }
    @Test void expiredEntryIsRebuiltFromTheDatabase() throws Exception {
        var shortCache=new ProductDetailCache(redis,true,prefix,1);
        var reader=new ProductService(repository,sessions,images,shortCache,orders);reader.getProduct(id,null);
        assertTrue(redis.hasKey(key(0)));Thread.sleep(1200);assertFalse(redis.hasKey(key(0)));
        assertEquals("缓存教材",reader.getProduct(id,null).title());assertTrue(redis.hasKey(key(0)));
    }
    @Test void privateProductsCannotBeInsertedIntoThePublicBodyCache() throws Exception {
        check(status("OFF_SHELF",0),200);
        var privateProduct=service.getProduct(id,"Bearer "+ownerToken);cache.putPublic(privateProduct);
        assertFalse(redis.hasKey(key(1)));assertThrows(IllegalArgumentException.class,()->CachedProductDetail.from(privateProduct));
    }
}
