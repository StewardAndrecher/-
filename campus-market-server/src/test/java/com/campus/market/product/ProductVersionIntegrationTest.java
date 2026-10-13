package com.campus.market.product;

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

@SpringBootTest(properties = {
        "spring.datasource.url=${LESSON25_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root", "spring.datasource.password="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON25_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson25_verify\\?.*")
class ProductVersionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository repository;
    @Autowired ProductService service;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper mapper = JsonMapper.builder().build();
    long id;
    String token;

    JsonNode check(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        return response.getContentAsByteArray().length == 0 ? null
                : mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    String login(String username) throws Exception {
        return check(post("/api/users/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", username, "password", "Campus2026!"))),200)
                .get("accessToken").asText();
    }
    @BeforeEach void fixtures() throws Exception {
        assertTrue(System.getenv("LESSON25_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson25_verify\\?.*"));
        jdbc.update("DELETE FROM products"); jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM login_sessions"); jdbc.update("DELETE FROM users");
        for (String name : new String[]{"owner_01", "owner_02"}) {
            check(post("/api/users/register").contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        }
        token = login("owner_01");
        var created = check(post("/api/products").header("Authorization","Bearer " + token).contentType("application/json")
                .content("{\"title\":\"原始教材\",\"description\":\"原始描述\",\"priceInCents\":3500}"),201);
        id = created.get("id").asLong(); assertEquals(0, created.get("version").asLong());
    }
    MockHttpServletRequestBuilder edit(String title, long version) {
        return editAs(title, version, token);
    }
    MockHttpServletRequestBuilder editAs(String title, long version, String actorToken) {
        return put("/api/products/" + id).header("Authorization","Bearer " + actorToken).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title",title,"description","原始描述","priceInCents",3500,"version",version)));
    }
    MockHttpServletRequestBuilder status(String state, long version) {
        return statusAs(state, version, token);
    }
    MockHttpServletRequestBuilder statusAs(String state, long version, String actorToken) {
        return put("/api/products/" + id + "/status").header("Authorization","Bearer " + actorToken).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("status",state,"version",version)));
    }
    ProductResponse row() { return repository.findById(id).orElseThrow(); }
    @Test void publicAndOwnerResponsesContainVersion() throws Exception {
        assertEquals(0, check(get("/api/products/"+id),200).get("version").asLong());
        assertEquals(0, check(get("/api/products"),200).get("records").get(0).get("version").asLong());
        assertEquals(0, check(get("/api/products/mine").header("Authorization","Bearer "+token),200)
                .get("records").get(0).get("version").asLong());
    }
    @Test void staleEditorCannotOverwriteNewerFields() throws Exception {
        check(edit("第一位编辑者",0),200);
        assertEquals("PRODUCT_CONFLICT",check(edit("旧表单覆盖",0),409).get("code").asText());
        assertEquals("第一位编辑者",row().title()); assertEquals(1,row().version());
        check(get("/api/users/me").header("Authorization","Bearer "+token),200);
    }
    @Test void editsAndStatusShareVersion() throws Exception {
        check(edit("新版教材",0),200); check(status("OFF_SHELF",0),409);
        check(status("OFF_SHELF",1),200); check(edit("旧编辑表单",1),409);
        assertEquals(ProductStatus.OFF_SHELF,row().status()); assertEquals("新版教材",row().title()); assertEquals(2,row().version());
        check(edit("下架后重新加载编辑",2),200); assertEquals(3,row().version()); assertEquals(ProductStatus.OFF_SHELF,row().status());
    }
    @Test void currentNoOpDoesNotIncrementButStaleNoOpConflicts() throws Exception {
        check(edit("原始教材",0),200); check(status("ON_SALE",0),200); assertEquals(0,row().version());
        check(edit("新版教材",0),200); check(edit("新版教材",0),409); check(status("ON_SALE",0),409);
        check(edit("新版教材",1),200); check(status("ON_SALE",1),200); assertEquals(1,row().version());
    }
    @Test void rejectsMissingNegativeOversizedAndNonIntegerVersions() throws Exception {
        for (String value : new String[]{null,"null","-1","9007199254740992","0.5","\"0\"","true","[]","{}","9223372036854775808"}) {
            String fragment = value == null ? "" : ",\"version\":"+value;
            for (String suffix : new String[]{"","/status"}) {
                String fields = suffix.isEmpty() ? "\"title\":\"新版教材\",\"priceInCents\":3500" : "\"status\":\"OFF_SHELF\"";
                check(put("/api/products/"+id+suffix).header("Authorization","Bearer "+token).contentType("application/json")
                        .content("{"+fields+fragment+"}"),400);
            }
            assertEquals("原始教材",row().title()); assertEquals(0,row().version());
        }
    }
    @Test void foreignOwnerAndMissingLoginCannotUseVersionsToBypassAuthorization() throws Exception {
        check(edit("新版教材",0),200);
        String other=login("owner_02");
        check(editAs("越权覆盖",0,other),403);
        check(statusAs("OFF_SHELF",0,other),403);
        check(editAs("未登录覆盖",0,"A".repeat(43)),401);
        assertEquals("新版教材",row().title()); assertEquals(1,row().version());
    }
    @Test void conditionalSqlAlsoRejectsStaleVersionsAndPreservesCover() {
        long owner = row().seller().id(); String imageId="7b1075d8-1e80-4343-91ed-85ec3241bf21";
        jdbc.update("INSERT INTO product_images(id,owner_id,width,height) VALUES(?,?,72,72)",imageId,owner);
        jdbc.update("UPDATE products SET cover_image_id=? WHERE id=?",imageId,id);
        assertEquals(1,repository.updateOwnedProduct(id,owner,"CAS更新教材","原始描述",1250,imageId,0));
        assertEquals(0,repository.updateOwnedProduct(id,owner,"旧版本SQL覆盖","原始描述",1,null,0));
        assertEquals(0,repository.updateOwnedStatus(id,owner,ProductStatus.OFF_SHELF,0));
        assertEquals(1,row().version()); assertEquals(imageId,row().coverImageId()); assertEquals("CAS更新教材",row().title());
    }
    @Test void coverEditsAlsoIncrementSharedVersion() throws Exception {
        String imageId="7b1075d8-1e80-4343-91ed-85ec3241bf21";
        jdbc.update("INSERT INTO product_images(id,owner_id,width,height) VALUES(?,?,72,72)",imageId,row().seller().id());
        check(put("/api/products/"+id).header("Authorization","Bearer "+token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title","原始教材","description","原始描述","priceInCents",3500,"coverImageId",imageId,"version",0))),200);
        check(edit("旧表单无封面字段",0),409); assertEquals(imageId,row().coverImageId()); assertEquals(1,row().version());
        check(edit("新版教材",1),200); assertEquals(imageId,row().coverImageId()); assertEquals(2,row().version());
    }
    @Test void concurrentEditorsHaveExactlyOneWinner() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try {
            Callable<Integer> writer=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
                try {service.updateProduct(id,new UpdateProductRequest("并发教材"+Thread.currentThread().getId(),"原始描述",3500L,null,0L),"Bearer "+token);return 200;}
                catch(ProductConflictException expected){return 409;}};
            var a=pool.submit(writer);var b=pool.submit(writer);assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();
            var codes=new java.util.ArrayList<>(java.util.List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)));codes.sort(Integer::compareTo);
            assertEquals(java.util.List.of(200,409),codes);assertEquals(1,row().version());
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void rollbackRestoresFieldsStatusAndVersionTogether() {
        var tx=new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(state->{
            service.updateProduct(id,new UpdateProductRequest("事务修改教材","原始描述",3500L,null,0L),"Bearer "+token);
            service.updateStatus(id,new UpdateProductStatusRequest(ProductStatus.OFF_SHELF,1L),"Bearer "+token);
            throw new IllegalStateException("模拟失败");
        }));
        assertEquals("原始教材",row().title());assertEquals(ProductStatus.ON_SALE,row().status());assertEquals(0,row().version());
    }
    @Test void conflictUsesNoStoreAndReadableJson() throws Exception {
        check(edit("新版教材",0),200);
        var response=mvc.perform(edit("旧表单",0)).andReturn().getResponse();
        assertEquals(409,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("PRODUCT_CONFLICT",mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("code").asText());
    }
    @Test void databaseRestrictsVersionsAndIncrementCannotOverflow() {
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE products SET version=-1 WHERE id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE products SET version=9007199254740992 WHERE id=?",id));
        jdbc.update("UPDATE products SET version=9007199254740991 WHERE id=?",id);
        assertThrows(ProductConflictException.class,()->service.updateProduct(id,
                new UpdateProductRequest("超过上限教材","原始描述",3500L,null,9_007_199_254_740_991L),"Bearer "+token));
        assertEquals(9_007_199_254_740_991L,row().version());assertEquals("原始教材",row().title());
    }
}
