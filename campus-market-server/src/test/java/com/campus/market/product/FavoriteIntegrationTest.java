package com.campus.market.product;

import com.campus.market.favorite.FavoriteService;
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

@SpringBootTest(properties={"spring.datasource.url=${LESSON26_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON26_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson26_verify\\?.*")
class FavoriteIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FavoriteService service;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper mapper=JsonMapper.builder().build();
    String a,b;
    long first,second,userA,userB;
    JsonNode check(MockHttpServletRequestBuilder request,int expected)throws Exception{
        var res=mvc.perform(request).andReturn().getResponse();
        assertEquals(expected,res.getStatus(),res.getContentAsString(StandardCharsets.UTF_8));
        return res.getContentAsByteArray().length==0?null:mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }
    String login(String username)throws Exception{
        return check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(
                Map.of("username",username,"password","Campus2026!"))),200).get("accessToken").asText();
    }
    long publish(String title)throws Exception{
        return check(post("/api/products").header("Authorization","Bearer "+a).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title",title,"priceInCents",1250))),201).get("id").asLong();
    }
    @BeforeEach void fixtures()throws Exception{
        assertTrue(System.getenv("LESSON26_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson26_verify\\?.*"));
        jdbc.update("DELETE FROM product_favorites");jdbc.update("DELETE FROM products");jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
        for(String name:new String[]{"owner_01","owner_02"})check(post("/api/users/register").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        a=login("owner_01");b=login("owner_02");
        userA=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_01'",Long.class);
        userB=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_02'",Long.class);
        first=publish("收藏教材甲");second=publish("收藏台灯乙");
    }
    MockHttpServletRequestBuilder add(long id,String token){return put("/api/favorites/"+id).header("Authorization","Bearer "+token);}
    MockHttpServletRequestBuilder remove(long id,String token){return delete("/api/favorites/"+id).header("Authorization","Bearer "+token);}
    MockHttpServletRequestBuilder page(String token){return get("/api/favorites").header("Authorization","Bearer "+token);}
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM product_favorites",Long.class);}

    @Test void duplicateAddAndDeleteAreIdempotentAndDoNotEditProduct()throws Exception{
        check(add(first,a),200);var created=jdbc.queryForObject("SELECT created_at FROM product_favorites",java.sql.Timestamp.class);
        check(add(first,a),200);assertEquals(1,count());assertEquals(created,jdbc.queryForObject("SELECT created_at FROM product_favorites",java.sql.Timestamp.class));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM products WHERE id=?",Long.class,first));
        assertFalse(check(remove(first,a),200).get("favorite").asBoolean());check(remove(first,a),200);assertEquals(0,count());
    }
    @Test void currentUserIsDerivedFromTokenEvenWhenRequestSpoofsOwner()throws Exception{
        check(add(first,a).contentType("application/json").content("{\"userId\":"+userB+"}"),200);
        assertEquals(1,check(page(a).param("userId",String.valueOf(userB)),200).get("total").asLong());
        assertEquals(0,check(page(b).param("userId",String.valueOf(userA)),200).get("total").asLong());
        check(remove(first,b),200);assertEquals(1,count());
        check(add(first,b),200);assertEquals(2,count());check(remove(first,a),200);assertEquals(1,check(page(b),200).get("total").asLong());
    }
    @Test void anotherSessionOfSameAccountReadsPersistedFavorites()throws Exception{
        check(add(first,a),200);check(post("/api/users/logout").header("Authorization","Bearer "+a),204);
        String device=login("owner_01");assertEquals(first,check(page(device),200).get("records").get(0).get("productId").asLong());
        assertEquals(1,count());
    }
    @Test void paginatesEntireCollectionInStableOrder()throws Exception{
        check(add(first,a),200);check(add(second,a),200);
        var p1=check(page(a).param("size","1"),200);var p2=check(page(a).param("size","1").param("page","2"),200);
        assertEquals(2,p1.get("total").asLong());assertEquals(second,p1.get("records").get(0).get("productId").asLong());
        assertEquals(first,p2.get("records").get(0).get("productId").asLong());
        assertEquals(0,check(page(a).param("page","99"),200).get("records").size());
    }
    @Test void unavailableProductsAreRedactedButCanStillBeRemoved()throws Exception{
        check(add(first,b),200);jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=?",first);
        var favorite=check(page(b),200).get("records").get(0);assertTrue(favorite.get("product").isNull());assertEquals(first,favorite.get("productId").asLong());
        assertEquals(2,favorite.size());check(add(first,b),404);assertEquals(1,count());check(remove(first,b),200);assertEquals(0,count());
    }
    @Test void unknownProductsAndInvalidIdsCannotCreateRelationships()throws Exception{
        check(add(999999999,a),404);check(remove(999999999,a),200);
        for(long id:new long[]{0,-1,9007199254740992L}){check(add(id,a),400);check(remove(id,a),400);}
        assertEquals(0,count());
    }
    @Test void selectedStatesAreScopedBoundedAndOnlyIncludeRequestedIds()throws Exception{
        check(add(first,a),200);check(add(second,b),200);
        var ids=check(get("/api/favorites/ids").header("Authorization","Bearer "+a).param("productIds",first+","+second),200);
        assertEquals(1,ids.size());assertEquals(first,ids.get(0).asLong());
        assertEquals(0,check(get("/api/favorites/ids").header("Authorization","Bearer "+a),200).size());
        check(get("/api/favorites/ids").header("Authorization","Bearer "+a).param("productIds",String.join(",",java.util.Collections.nCopies(51,"1"))),400);
        check(get("/api/favorites/ids").header("Authorization","Bearer "+a).param("productIds","0"),400);
        check(get("/api/favorites/ids").header("Authorization","Bearer "+a).param("productIds","bad"),400);
        for(String[] pair:new String[][]{{"page","0"},{"size","0"},{"size","51"}})check(page(a).param(pair[0],pair[1]),400);
    }
    @Test void missingExpiredAndRevokedSessionsCannotReadOrWrite()throws Exception{
        check(get("/api/favorites"),401);check(get("/api/favorites/ids"),401);check(put("/api/favorites/"+first),401);check(delete("/api/favorites/"+first),401);
        check(post("/api/users/logout").header("Authorization","Bearer "+a),204);check(add(first,a),401);check(page(a),401);
        a=login("owner_01");jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");
        check(add(first,a),401);check(remove(first,a),401);check(page(a),401);assertEquals(0,count());
    }
    @Test void concurrentAddsProduceOneRelationship()throws Exception{
        var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try{
            Callable<Boolean> task=()->{ready.countDown();assertTrue(start.await(3,TimeUnit.SECONDS));return service.add(first,"Bearer "+a).favorite();};
            var left=executor.submit(task);var right=executor.submit(task);assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();
            assertTrue(left.get(5,TimeUnit.SECONDS));assertTrue(right.get(5,TimeUnit.SECONDS));assertEquals(1,count());
        }finally{start.countDown();executor.shutdownNow();}
    }
    @Test void transactionRollbackRestoresFavoriteRelationships(){
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            service.add(first,"Bearer "+a);throw new IllegalStateException("模拟失败");}));assertEquals(0,count());
    }
    @Test void foreignKeysPreventInvalidRelationshipsAndProductDeletionCleansFavorites()throws Exception{
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(?,?)",999999999,first));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(?,?)",userA,999999999));
        check(add(first,a),200);jdbc.update("DELETE FROM products WHERE id=?",first);assertEquals(0,count());
    }
    @Test void responsesDisableCachingAndCorsAllowsDelete()throws Exception{
        var response=mvc.perform(page(a)).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));
        var cors=mvc.perform(options("/api/favorites/"+first).header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","DELETE").header("Access-Control-Request-Headers","authorization")).andReturn().getResponse();
        assertEquals(200,cors.getStatus());assertTrue(cors.getHeader("Access-Control-Allow-Methods").contains("DELETE"));
    }
}
