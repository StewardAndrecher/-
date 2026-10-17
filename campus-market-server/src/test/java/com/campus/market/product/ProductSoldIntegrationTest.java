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
        "spring.datasource.url=${LESSON43_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root", "spring.datasource.password="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON43_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson43_verify\\?.*")
class ProductSoldIntegrationTest {
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
        assertTrue(System.getenv("LESSON43_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson43_verify\\?.*"));
        jdbc.update("DELETE FROM product_publications"); jdbc.update("DELETE FROM products"); jdbc.update("DELETE FROM product_images");
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

    @Test void soldRetainsProductIdentityAndHidesFromPublicCatalog() throws Exception {
        var before=row(); var created=jdbc.queryForObject("SELECT created_at FROM products WHERE id=?",java.sql.Timestamp.class,id);
        var sold=check(status("SOLD",0),200); assertEquals("SOLD",sold.get("status").asText()); assertEquals(1,sold.get("version").asLong());
        assertEquals(before.title(),row().title());assertEquals(before.priceInCents(),row().priceInCents());assertEquals(before.seller(),row().seller());
        assertEquals(created,jdbc.queryForObject("SELECT created_at FROM products WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(0,check(get("/api/products"),200).get("total").asLong());
        assertEquals(0,check(get("/api/sellers/"+before.seller().id()+"/products"),200).get("products").get("total").asLong());
        assertEquals("SOLD",check(get("/api/products/mine").header("Authorization","Bearer "+token),200).get("records").get(0).get("status").asText());
    }
    @Test void offShelfProductCanAlsoBeMarkedSold() throws Exception {
        check(status("OFF_SHELF",0),200);check(status("SOLD",1),200);assertEquals(2,row().version());assertEquals(ProductStatus.SOLD,row().status());
    }
    @Test void soldIsTerminalAndVersionChecksStillApplyToRepeatedRequests() throws Exception {
        check(status("SOLD",0),200);
        for(String target:new String[]{"ON_SALE","OFF_SHELF"})assertEquals("PRODUCT_CONFLICT",check(status(target,1),409).get("code").asText());
        check(status("SOLD",0),409);check(status("SOLD",1),200);check(status("SOLD",1),200);
        assertEquals(ProductStatus.SOLD,row().status());assertEquals(1,row().version());
    }
    @Test void soldRejectsEditsIncludingUnchangedFieldsAndConditionalSql() throws Exception {
        check(status("SOLD",0),200);check(edit("新的标题",1),409);check(edit("原始教材",1),409);
        assertEquals(0,repository.updateOwnedProduct(id,row().seller().id(),"绕过服务的标题","原始描述",1L,null,1));
        assertEquals(0,repository.updateOwnedStatus(id,row().seller().id(),ProductStatus.ON_SALE,1));
        assertEquals("原始教材",row().title());assertEquals(1,row().version());
    }
    @Test void onlyOwnerCanMarkSoldAndLegacyOwnershipCannotBeClaimed() throws Exception {
        check(put("/api/products/"+id+"/status").contentType("application/json").content("{\"status\":\"SOLD\",\"version\":0}"),401);
        check(statusAs("SOLD",0,login("owner_02")),403);
        jdbc.update("INSERT INTO products(title,description,price_in_cents) VALUES('历史商品','',100)");
        long legacy=jdbc.queryForObject("SELECT MAX(id) FROM products",Long.class);
        check(put("/api/products/"+legacy+"/status").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"status\":\"SOLD\",\"version\":0,\"sellerId\":"+row().seller().id()+"}"),403);
        assertEquals(ProductStatus.ON_SALE,row().status());
    }
    @Test void soldDetailAndCommentsRetainOwnerOnlyAccess() throws Exception {
        check(status("SOLD",0),200);String other=login("owner_02");
        for(String suffix:new String[]{"","/comments"}){
            check(get("/api/products/"+id+suffix),404);check(get("/api/products/"+id+suffix).header("Authorization","Bearer "+other),404);
            check(get("/api/products/"+id+suffix).header("Authorization","Bearer "+token),200);
        }
    }
    @Test void favoritesBecomeUnavailableWithoutDeletingRelationship() throws Exception {
        String buyer=login("owner_02");check(put("/api/favorites/"+id).header("Authorization","Bearer "+buyer),200);
        check(status("SOLD",0),200);check(put("/api/favorites/"+id).header("Authorization","Bearer "+buyer),404);
        var page=check(get("/api/favorites").header("Authorization","Bearer "+buyer),200);
        assertEquals(1,page.get("total").asLong());assertTrue(page.get("records").get(0).get("product").isNull());
        check(delete("/api/favorites/"+id).header("Authorization","Bearer "+buyer),200);
    }
    @Test void newCommentsAreBlockedButOriginalReceiptAndWithdrawalStillWork() throws Exception {
        String buyer=login("owner_02"),key="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        String content=mapper.writeValueAsString(Map.of("content","售出前留言","requestId",key));
        var receipt=check(post("/api/products/"+id+"/comments").header("Authorization","Bearer "+buyer).contentType("application/json").content(content),201);
        check(status("SOLD",0),200);
        assertTrue(check(post("/api/products/"+id+"/comments").header("Authorization","Bearer "+buyer).contentType("application/json").content(content),200).get("replayed").asBoolean());
        check(post("/api/products/"+id+"/comments").header("Authorization","Bearer "+buyer).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("content","售出后新留言","requestId",java.util.UUID.randomUUID().toString()))),404);
        check(delete("/api/products/"+id+"/comments/"+receipt.get("comment").get("id").asLong()).header("Authorization","Bearer "+buyer),204);
    }
    @Test void publicationReplayCannotResurrectSoldProduct() throws Exception {
        String body=mapper.writeValueAsString(Map.of("requestId",java.util.UUID.randomUUID().toString(),"product",Map.of("title","可靠发布教材","description","","priceInCents",1200)));
        long published=check(post("/api/product-publications").header("Authorization","Bearer "+token).contentType("application/json").content(body),201).get("productId").asLong();
        check(put("/api/products/"+published+"/status").header("Authorization","Bearer "+token).contentType("application/json").content("{\"status\":\"SOLD\",\"version\":0}"),200);
        var replay=check(post("/api/product-publications").header("Authorization","Bearer "+token).contentType("application/json").content(body),200);
        assertEquals(published,replay.get("productId").asLong());assertTrue(replay.get("replayed").asBoolean());
        assertEquals(ProductStatus.SOLD,repository.findById(published).orElseThrow().status());
    }
    @Test void concurrentSoldRequestsHaveOneWinner() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            Callable<Integer> write=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));try{service.updateStatus(id,new UpdateProductStatusRequest(ProductStatus.SOLD,0L),"Bearer "+token);return 200;}catch(ProductConflictException e){return 409;}};
            var a=pool.submit(write);var b=pool.submit(write);assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();
            var codes=new java.util.ArrayList<>(java.util.List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)));codes.sort(Integer::compareTo);assertEquals(java.util.List.of(200,409),codes);assertEquals(1,row().version());
        }finally{go.countDown();pool.shutdownNow();}
    }
    @Test void transactionRollbackRestoresSoldStatusAndVersion() {
        var tx=new TransactionTemplate(transactions);assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(state->{
            service.updateStatus(id,new UpdateProductStatusRequest(ProductStatus.SOLD,0L),"Bearer "+token);throw new IllegalStateException("回滚验收");
        }));assertEquals(ProductStatus.ON_SALE,row().status());assertEquals(0,row().version());
    }
    @Test void checkConstraintAllowsSoldButRejectsUnrecognizedOrPaddedStates() {
        for(String invalid:new String[]{"sold","SOLD ","DELETED"})assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE products SET status=? WHERE id=?",invalid,id));
        jdbc.update("UPDATE products SET status='SOLD' WHERE id=?",id);assertEquals(ProductStatus.SOLD,row().status());
    }
}
