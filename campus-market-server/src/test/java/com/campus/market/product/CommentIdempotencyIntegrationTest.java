package com.campus.market.product;

import com.campus.market.comment.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON28_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON28_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*")
class CommentIdempotencyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommentService service;
    @Autowired CommentRepository repository;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper mapper=JsonMapper.builder().build();
    String seller,buyer;
    long product,second,buyerId;
    JsonNode check(MockHttpServletRequestBuilder request,int expected)throws Exception{
        var res=mvc.perform(request).andReturn().getResponse();assertEquals(expected,res.getStatus(),res.getContentAsString(StandardCharsets.UTF_8));
        return res.getContentAsByteArray().length==0?null:mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }
    String login(String name)throws Exception{return check(post("/api/users/login").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!"))),200).get("accessToken").asText();}
    @BeforeEach void fixtures()throws Exception{
        assertTrue(System.getenv("LESSON28_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*"));
        jdbc.update("DELETE FROM product_comments");jdbc.update("DELETE FROM product_favorites");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
        for(String name:new String[]{"owner_01","buyer_01"})check(post("/api/users/register").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        seller=login("owner_01");buyer=login("buyer_01");buyerId=jdbc.queryForObject("SELECT id FROM users WHERE username='buyer_01'",Long.class);
        product=publish("幂等教材");second=publish("另一件幂等商品");
    }
    long publish(String title)throws Exception{return check(post("/api/products").header("Authorization","Bearer "+seller).contentType("application/json").content(mapper.writeValueAsString(Map.of("title",title,"priceInCents",1250))),201).get("id").asLong();}
    MockHttpServletRequestBuilder submit(long productId,String token,String content,String key){return post("/api/products/"+productId+"/comments").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(Map.of("content",content,"requestId",key)));}
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM product_comments",Long.class);}
    String key(){return UUID.randomUUID().toString();}

    @Test void repeatedSubmissionReturnsSameCommentAnd201Then200()throws Exception{
        String key=key();var first=check(submit(product,buyer," 同一次提交 ",key),201);var replay=check(submit(product,buyer,"同一次提交",key),200);
        assertEquals(first.get("comment"),replay.get("comment"));assertFalse(first.get("replayed").asBoolean());assertTrue(replay.get("replayed").asBoolean());assertFalse(replay.get("withdrawn").asBoolean());assertEquals(key,replay.get("requestId").asText());assertEquals(1,count());
        var item=check(get("/api/products/"+product+"/comments"),200).get("records").get(0);assertFalse(item.has("requestId"));
    }
    @Test void reusedKeyWithDifferentContentConflicts()throws Exception{
        String key=key();check(submit(product,buyer,"原内容",key),201);assertEquals("COMMENT_REQUEST_CONFLICT",check(submit(product,buyer,"其他内容",key),409).get("code").asText());assertEquals(1,count());
        assertEquals("原内容",jdbc.queryForObject("SELECT content FROM product_comments",String.class));
    }
    @Test void concurrentSameKeyHasOneCreationAndOneReplay()throws Exception{
        String key=key();var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try{
            Callable<CommentSubmissionResponse> action=()->{ready.countDown();assertTrue(start.await(3,TimeUnit.SECONDS));return service.create(product,new CreateCommentRequest("并发提交",key),"Bearer "+buyer);};
            var a=pool.submit(action);var b=pool.submit(action);assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();var left=a.get(5,TimeUnit.SECONDS);var right=b.get(5,TimeUnit.SECONDS);
            assertEquals(left.comment().id(),right.comment().id());assertNotEquals(left.replayed(),right.replayed());assertEquals(1,count());
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void differentKeysAllowIntentionalSameTextSubmissions()throws Exception{
        var a=check(submit(product,buyer,"相同文字",key()),201);var b=check(submit(product,buyer,"相同文字",key()),201);assertNotEquals(a.get("comment").get("id"),b.get("comment").get("id"));assertEquals(2,count());
    }
    @Test void scopeIncludesAuthorAndProduct()throws Exception{
        String key=key();var a=check(submit(product,buyer,"买家内容",key),201);var b=check(submit(product,seller,"卖家内容",key),201);var c=check(submit(second,buyer,"另一件商品内容",key),201);
        assertNotEquals(a.get("comment").get("id"),b.get("comment").get("id"));assertNotEquals(a.get("comment").get("id"),c.get("comment").get("id"));assertEquals(3,count());
    }
    @Test void missingNullUppercaseAndMalformedKeysAreRejected()throws Exception{
        for(String fragment:new String[]{"",",\"requestId\":null",",\"requestId\":\"\"",",\"requestId\":\"bad\"",",\"requestId\":42",",\"requestId\":[]",",\"requestId\":\"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA\""})
            check(post("/api/products/"+product+"/comments").header("Authorization","Bearer "+buyer).contentType("application/json").content("{\"content\":\"有效内容\""+fragment+"}"),400);
        assertEquals(0,count());
    }
    @Test void replayOfWithdrawnCommentDoesNotResurrectIt()throws Exception{
        String key=key();var a=check(submit(product,buyer,"撤回后重试",key),201);long id=a.get("comment").get("id").asLong();check(delete("/api/products/"+product+"/comments/"+id).header("Authorization","Bearer "+buyer),204);
        var replay=check(submit(product,buyer,"撤回后重试",key),200);assertTrue(replay.get("withdrawn").asBoolean());assertEquals(id,replay.get("comment").get("id").asLong());assertEquals(1,count());assertEquals(0,check(get("/api/products/"+product+"/comments"),200).get("total").asLong());
    }
    @Test void completedSubmissionCanBeConfirmedAfterProductIsOffShelf()throws Exception{
        String key=key();var a=check(submit(product,buyer,"下架前留言",key),201);jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=?",product);
        assertEquals(a.get("comment"),check(submit(product,buyer,"下架前留言",key),200).get("comment"));check(submit(product,buyer,"新留言",key()),404);assertEquals(1,count());
    }
    @Test void replayStillRequiresAnActiveSession()throws Exception{
        String key=key();check(submit(product,buyer,"会话检查",key),201);check(post("/api/users/logout").header("Authorization","Bearer "+buyer),204);check(submit(product,buyer,"会话检查",key),401);
        buyer=login("buyer_01");assertTrue(check(submit(product,buyer,"会话检查",key),200).get("replayed").asBoolean());assertEquals(1,count());
    }
    @Test void databaseUniquenessAndRequestFormatAreEnforced()throws Exception{
        String key=key();repository.insert(product,buyerId,"数据库唯一键",key);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->repository.insert(product,buyerId,"数据库唯一键",key));
        assertThrows(org.springframework.dao.DataAccessException.class,()->repository.insert(product,buyerId,"格式错误","bad"));assertEquals(1,count());
    }
    @Test void rollbackDoesNotConsumeSubmissionKey()throws Exception{
        String key=key();assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{service.create(product,new CreateCommentRequest("事务回滚",key),"Bearer "+buyer);throw new IllegalStateException("失败");}));assertEquals(0,count());
        assertFalse(check(submit(product,buyer,"事务回滚",key),201).get("replayed").asBoolean());assertEquals(1,count());
    }
    @Test void replaySeesCommittedResultEvenWithOlderTransactionSnapshot()throws Exception{
        String key=key();var pool=Executors.newSingleThreadExecutor();
        try{new TransactionTemplate(transactions).executeWithoutResult(tx->{
            assertEquals(0,count());
            try{var first=pool.submit(()->service.create(product,new CreateCommentRequest("旧快照重试",key),"Bearer "+buyer)).get(5,TimeUnit.SECONDS);var replay=service.create(product,new CreateCommentRequest("旧快照重试",key),"Bearer "+buyer);assertTrue(replay.replayed());assertEquals(first.comment().id(),replay.comment().id());}
            catch(Exception e){throw new IllegalStateException(e);}
        });assertEquals(1,count());}finally{pool.shutdownNow();}
    }
    @Test void replayAndConflictResponsesAreNotCached()throws Exception{
        String key=key();check(submit(product,buyer,"缓存检查",key),201);var replay=mvc.perform(submit(product,buyer,"缓存检查",key)).andReturn().getResponse();assertEquals(200,replay.getStatus());assertEquals("no-store",replay.getHeader("Cache-Control"));
        var conflict=mvc.perform(submit(product,buyer,"另一个内容",key)).andReturn().getResponse();assertEquals(409,conflict.getStatus());assertEquals("no-store",conflict.getHeader("Cache-Control"));
    }
}
