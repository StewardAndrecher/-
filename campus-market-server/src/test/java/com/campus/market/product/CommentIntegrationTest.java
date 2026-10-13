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
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON27_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON27_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson27_verify\\?.*")
class CommentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommentService service;
    @Autowired ProductRepository products;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper mapper=JsonMapper.builder().build();
    String seller,buyer;
    long product,second,sellerId,buyerId;
    JsonNode check(MockHttpServletRequestBuilder request,int expected)throws Exception{
        var res=mvc.perform(request).andReturn().getResponse();
        assertEquals(expected,res.getStatus(),res.getContentAsString(StandardCharsets.UTF_8));
        var node=res.getContentAsByteArray().length==0?null:mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        return node!=null && node.has("comment") ? node.get("comment") : node;
    }
    String login(String username)throws Exception{
        return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(
                Map.of("username",username,"password","Campus2026!"))),200).get("accessToken").asText();
    }
    long publish(String title)throws Exception{
        return check(post("/api/products").header("Authorization","Bearer "+seller).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title",title,"priceInCents",1250))),201).get("id").asLong();
    }
    @BeforeEach void fixtures()throws Exception{
        assertTrue(System.getenv("LESSON27_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson27_verify\\?.*"));
        jdbc.update("DELETE FROM product_comments");jdbc.update("DELETE FROM product_favorites");jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM product_images");jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
        for(String name:new String[]{"owner_01","buyer_01"})check(post("/api/users/register").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        seller=login("owner_01");buyer=login("buyer_01");
        sellerId=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_01'",Long.class);
        buyerId=jdbc.queryForObject("SELECT id FROM users WHERE username='buyer_01'",Long.class);
        product=publish("留言验收台灯");second=publish("另一件留言商品");
    }
    String path(){return "/api/products/"+product+"/comments";}
    MockHttpServletRequestBuilder create(String content,String token){
        return post(path()).header("Authorization","Bearer "+token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("content",content,"requestId",java.util.UUID.randomUUID().toString())));
    }
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM product_comments",Long.class);}

    @Test void anonymousCanReadAndSellerMarkerComesFromDatabase()throws Exception{
        var question=check(create("  台灯能正常使用吗？  ",buyer),201);
        assertEquals("台灯能正常使用吗？",question.get("content").asText());assertFalse(question.get("seller").asBoolean());
        assertEquals(buyerId,question.get("author").get("id").asLong());assertEquals(2,question.get("author").size());
        assertTrue(check(create("可以正常使用。",seller),201).get("seller").asBoolean());
        var page=check(get(path()),200);assertEquals(2,page.get("total").asLong());assertEquals(2,page.get("records").size());
        assertEquals(0,products.findById(product).orElseThrow().version());
    }
    @Test void ignoresForgedAuthorSellerAndProductFields()throws Exception{
        var note=check(post(path()).header("Authorization","Bearer "+buyer).contentType("application/json").content(
                mapper.writeValueAsString(Map.of("content","伪造身份测试","authorId",sellerId,"seller",true,"productId",second,"requestId",java.util.UUID.randomUUID().toString()))),201);
        assertEquals(buyerId,note.get("author").get("id").asLong());assertFalse(note.get("seller").asBoolean());assertEquals(product,note.get("productId").asLong());
    }
    @Test void requiresLoginForCreateAndWithdrawIncludingExpiredAndRevokedSessions()throws Exception{
        long id=check(create("会话权限测试",buyer),201).get("id").asLong();
        check(post(path()).contentType("application/json").content("{\"content\":\"未登录\"}"),401);
        check(delete(path()+"/"+id),401);
        check(post("/api/users/logout").header("Authorization","Bearer "+buyer),204);
        check(create("已退出",buyer),401);check(delete(path()+"/"+id).header("Authorization","Bearer "+buyer),401);
        buyer=login("buyer_01");jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");
        check(create("已过期",buyer),401);assertEquals(1,count());assertEquals(1,check(get(path()),200).get("total").asLong());
    }
    @Test void validatesWhitespaceLengthNullAndMalformedBodies()throws Exception{
        for(String content:new String[]{""," \n\t ","a".repeat(501),"😀".repeat(501)})check(create(content,buyer),400);
        for(String body:new String[]{"{}","{\"content\":null}","{"})check(post(path()).header("Authorization","Bearer "+buyer)
                .contentType("application/json").content(body),400);
        assertEquals(0,count());
        check(create("😀".repeat(500),buyer),201);assertEquals(1,count());
    }
    @Test void paginatesInStableNewestFirstOrderAndScopesToProduct()throws Exception{
        long a=check(create("第一条",buyer),201).get("id").asLong();long b=check(create("第二条",seller),201).get("id").asLong();
        var p1=check(get(path()).param("size","1"),200);var p2=check(get(path()).param("size","1").param("page","2"),200);
        assertEquals(2,p1.get("total").asLong());assertEquals(b,p1.get("records").get(0).get("id").asLong());assertEquals(a,p2.get("records").get(0).get("id").asLong());
        assertEquals(0,check(get("/api/products/"+second+"/comments"),200).get("total").asLong());
        assertEquals(0,check(get(path()).param("page","99"),200).get("records").size());
    }
    @Test void authorCanSoftWithdrawRepeatedlyWithoutChangingProduct()throws Exception{
        long id=check(create("可以撤回的留言",buyer),201).get("id").asLong();
        check(delete(path()+"/"+id).header("Authorization","Bearer "+buyer),204);check(delete(path()+"/"+id).header("Authorization","Bearer "+buyer),204);
        assertEquals(1,count());assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM product_comments WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT is_deleted FROM product_comments WHERE id=?",Integer.class,id));
        assertEquals("可以撤回的留言",jdbc.queryForObject("SELECT content FROM product_comments WHERE id=?",String.class,id));
        assertEquals(0,check(get(path()),200).get("total").asLong());assertEquals(0,products.findById(product).orElseThrow().version());
    }
    @Test void sellerCannotWithdrawBuyersCommentAndCrossProductPathIsRejected()throws Exception{
        long id=check(create("买家的留言",buyer),201).get("id").asLong();
        assertEquals("COMMENT_FORBIDDEN",check(delete(path()+"/"+id).header("Authorization","Bearer "+seller),403).get("code").asText());
        check(delete("/api/products/"+second+"/comments/"+id).header("Authorization","Bearer "+buyer),404);
        assertEquals(1,check(get(path()),200).get("total").asLong());
    }
    @Test void offShelfPreventsNewNotesAndHidesNotesFromPublicButAuthorCanWithdraw()throws Exception{
        long id=check(create("下架前留言",buyer),201).get("id").asLong();jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=?",product);
        check(get(path()),404);check(get(path()).header("Authorization","Bearer "+buyer),404);
        assertEquals(1,check(get(path()).header("Authorization","Bearer "+seller),200).get("total").asLong());
        check(create("下架后买家留言",buyer),404);check(create("下架后卖家留言",seller),404);
        check(delete(path()+"/"+id).header("Authorization","Bearer "+buyer),204);assertEquals(1,count());
    }
    @Test void validatesIdsPaginationAndMissingProducts()throws Exception{
        for(String[] pair:new String[][]{{"page","0"},{"size","0"},{"size","51"}})check(get(path()).param(pair[0],pair[1]),400);
        check(get("/api/products/0/comments"),400);check(get("/api/products/999999999/comments"),404);
        check(post("/api/products/999999999/comments").header("Authorization","Bearer "+buyer).contentType("application/json").content(mapper.writeValueAsString(Map.of("content","不存在","requestId",java.util.UUID.randomUUID().toString()))),404);
        check(delete(path()+"/0").header("Authorization","Bearer "+buyer),400);
        check(delete(path()+"/999999999").header("Authorization","Bearer "+buyer),404);assertEquals(0,count());
    }
    @Test void htmlLikeAndSqlLikeContentAreStoredAsPlainText()throws Exception{
        String content="<b>标题</b> <img src=x onerror=alert(1)> '; DROP TABLE users; --";
        var note=check(create(content,buyer),201);assertEquals(content,note.get("content").asText());
        assertEquals(content,check(get(path()),200).get("records").get(0).get("content").asText());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class));
    }
    @Test void transactionRollbackAndForeignKeysProtectData()throws Exception{
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            service.create(product,new CreateCommentRequest("回滚留言",java.util.UUID.randomUUID().toString()),"Bearer "+buyer);throw new IllegalStateException("模拟失败");}));assertEquals(0,count());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1,jdbc.queryForObject("SELECT @@foreign_key_checks",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id=999999999",Long.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_comments(product_id,author_id,content,request_id) VALUES(?,?,?,?)",999999999,buyerId,"错误商品",java.util.UUID.randomUUID().toString()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_comments(product_id,author_id,content,request_id) VALUES(?,?,?,?)",product,999999999,"错误作者",java.util.UUID.randomUUID().toString()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_comments(product_id,author_id,content,request_id) VALUES(?,?,?,?)",product,buyerId,"",java.util.UUID.randomUUID().toString()));
    }
    @Test void createWaitsForStatusTransactionThenRejectsNewlyOffShelfProduct()throws Exception{
        var executor=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try{
            var holder=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                products.findByIdForUpdate(product).orElseThrow();jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=?",product);locked.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("锁测试超时");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertTrue(locked.await(3,TimeUnit.SECONDS));var writer=executor.submit(()->{started.countDown();return service.create(product,new CreateCommentRequest("并发留言",java.util.UUID.randomUUID().toString()),"Bearer "+buyer);});
            assertTrue(started.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->writer.get(250,TimeUnit.MILLISECONDS));release.countDown();holder.get(5,TimeUnit.SECONDS);
            var error=assertThrows(ExecutionException.class,()->writer.get(5,TimeUnit.SECONDS));assertInstanceOf(ProductNotFoundException.class,error.getCause());assertEquals(0,count());
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void responsesDisableCachingAndCorsAllowsPostAndDelete()throws Exception{
        var response=mvc.perform(get(path())).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));
        for(String method:new String[]{"POST","DELETE"}){
            var cors=mvc.perform(options(path()+(method.equals("DELETE")?"/1":"")).header("Origin","http://localhost:5173")
                    .header("Access-Control-Request-Method",method).header("Access-Control-Request-Headers","authorization,content-type")).andReturn().getResponse();
            assertEquals(200,cors.getStatus());assertTrue(cors.getHeader("Access-Control-Allow-Methods").contains(method));
        }
    }
}
