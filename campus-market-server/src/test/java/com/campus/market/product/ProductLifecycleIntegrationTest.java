package com.campus.market.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

// 只允许在临时测试库执行，绝不清理开发项目中的数据。
@SpringBootTest(properties = {
        "spring.datasource.url=${LESSON23_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root", "spring.datasource.password="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON23_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson23_verify\\?.*")
class ProductLifecycleIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductRepository repository;
    @Autowired private ProductService service;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private long sellerId;
    private long otherId;
    private long legacyId;
    private String token;
    private long productId;

    private JsonNode check(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(ProductTestRequests.withCurrentVersion(request, jdbc)).andReturn().getResponse();
        assertEquals(status, response.getStatus(), "接口状态码不符合预期");
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(text.contains("password"));
        assertFalse(text.contains("token_hash"));
        return text.isBlank() ? null : mapper.readTree(text);
    }

    private String login() throws Exception {
        return check(post("/api/users/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of(
                        "username", "owner_01", "password", "Campus2026!"))), 200)
                .get("accessToken").asText();
    }

    private MockHttpServletRequestBuilder publish(String title) {
        return post("/api/products").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of(
                        "title", title, "description", "测试商品", "priceInCents", 3500)));
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
    }

    @BeforeEach
    void prepareIsolatedFixtures() throws Exception {
        String url = System.getenv("LESSON23_DB_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson23_verify\\?.*"));
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM login_sessions");
        jdbc.update("DELETE FROM users");
        var a = check(post("/api/users/register").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", "owner_01",
                        "password", "Campus2026!", "nickname", "测试卖家甲"))), 201);
        var b = check(post("/api/users/register").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", "owner_02",
                        "password", "Campus2026!", "nickname", "测试卖家乙"))), 201);
        sellerId = a.get("id").asLong(); otherId = b.get("id").asLong();
        jdbc.update("INSERT INTO products(title,description,price_in_cents) VALUES(?,?,?)",
                "历史台灯", "迁移前商品", 900);
        legacyId = jdbc.queryForObject("SELECT MAX(id) FROM products", Long.class);
        token = login();
        productId = check(publish("待编辑台灯").header("Authorization", "Bearer " + token), 201).get("id").asLong();
    }

    private MockHttpServletRequestBuilder status(String body) {
        return put("/api/products/" + productId + "/status").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body);
    }
    private String otherToken() throws Exception {
        return check(post("/api/users/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", "owner_02", "password", "Campus2026!"))), 200)
                .get("accessToken").asText();
    }
    @Test
    void mineIsScopedToTokenAndPaginatesBothStates() throws Exception {
        var mine=check(get("/api/products/mine").header("Authorization", "Bearer " + token)
                .param("userId", String.valueOf(otherId)), 200);
        assertEquals(1,mine.get("total").asLong());
        assertEquals(sellerId,mine.get("records").get(0).get("seller").get("id").asLong());
        check(publish("其他卖家的台灯").header("Authorization", "Bearer " + otherToken()),201);
        long second=check(publish("自己的下架教材").header("Authorization", "Bearer " + token),201).get("id").asLong();
        check(put("/api/products/"+second+"/status").contentType("application/json")
                .header("Authorization","Bearer "+token).content("{\"status\":\"OFF_SHELF\"}"),200);
        var firstPage=check(get("/api/products/mine").header("Authorization","Bearer "+token).param("size","1"),200);
        assertEquals(2,firstPage.get("total").asLong());
        assertEquals(second,firstPage.get("records").get(0).get("id").asLong());
        assertEquals("OFF_SHELF",firstPage.get("records").get(0).get("status").asText());
        var secondPage=check(get("/api/products/mine").header("Authorization","Bearer "+token).param("size","1").param("page","2"),200);
        assertEquals(productId,secondPage.get("records").get(0).get("id").asLong());
        check(get("/api/products/mine").header("Authorization","Bearer "+token).param("page","0"),400);
        check(get("/api/products/mine").header("Authorization","Bearer "+token).param("size","51"),400);
    }
    @Test
    void ownerCanTakeDownEditAndRelistWhilePublicAccessIsFiltered() throws Exception {
        var createdAt=jdbc.queryForObject("SELECT created_at FROM products WHERE id=?",java.sql.Timestamp.class,productId);
        check(get("/api/products/"+productId),200);
        for(int i=0;i<2;i++) assertEquals("OFF_SHELF",check(status("{\"status\":\"OFF_SHELF\",\"sellerId\":"+otherId+"}"),200).get("status").asText());
        assertEquals(2,count());
        assertEquals(1,check(get("/api/products"),200).get("total").asLong());
        assertEquals(0,check(get("/api/products").param("keyword","待编辑"),200).get("total").asLong());
        check(get("/api/products/"+productId),404);
        check(get("/api/products/"+productId).header("Authorization","Bearer "+otherToken()),404);
        assertEquals("OFF_SHELF",check(get("/api/products/"+productId).header("Authorization","Bearer "+token),200).get("status").asText());
        var updated=check(put("/api/products/"+productId).contentType("application/json")
                .header("Authorization","Bearer "+token).content("{\"title\":\"下架后编辑台灯\",\"priceInCents\":1250}"),200);
        assertEquals("OFF_SHELF",updated.get("status").asText());
        check(status("{\"status\":\"ON_SALE\"}"),200);
        assertEquals(1250,check(get("/api/products/"+productId),200).get("priceInCents").asLong());
        assertEquals(2,check(get("/api/products"),200).get("total").asLong());
        assertEquals(sellerId,repository.findById(productId).orElseThrow().seller().id());
        assertEquals(createdAt,jdbc.queryForObject("SELECT created_at FROM products WHERE id=?",java.sql.Timestamp.class,productId));
    }
    @Test
    void rejectsUnauthorizedAndUnownedStatusChanges() throws Exception {
        check(get("/api/products/mine"),401);
        check(put("/api/products/"+productId+"/status").contentType("application/json").content("{\"status\":\"OFF_SHELF\"}"),401);
        var denied=check(put("/api/products/"+productId+"/status").header("Authorization","Bearer "+otherToken())
                .contentType("application/json").content("{\"status\":\"OFF_SHELF\",\"sellerId\":"+sellerId+"}"),403);
        assertEquals("PRODUCT_FORBIDDEN",denied.get("code").asText());
        for(long id:new long[]{legacyId,999999999,0}) {
            check(put("/api/products/"+id+"/status").header("Authorization","Bearer "+token)
                    .contentType("application/json").content("{\"status\":\"OFF_SHELF\"}"),id==legacyId?403:id==0?400:404);
        }
        assertEquals(ProductStatus.ON_SALE,repository.findById(productId).orElseThrow().status());
    }
    @Test
    void validatesStatusValuesWithoutChangingData() throws Exception {
        for(String body:new String[]{"{}","{\"status\":null}","{\"status\":\"DELETED\"}","{"}) {
            check(status(body),400);
            assertEquals(ProductStatus.ON_SALE,repository.findById(productId).orElseThrow().status());
        }
        assertEquals(2,count());
    }
    @Test
    void expiredAndRevokedSessionsCannotManageProducts() throws Exception {
        check(post("/api/users/logout").header("Authorization","Bearer "+token),204);
        check(status("{\"status\":\"OFF_SHELF\"}"),401);
        check(get("/api/products/mine").header("Authorization","Bearer "+token),401);
        token=login();
        jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");
        check(status("{\"status\":\"OFF_SHELF\"}"),401);
        check(get("/api/products/mine").header("Authorization","Bearer "+token),401);
        assertEquals(ProductStatus.ON_SALE,repository.findById(productId).orElseThrow().status());
    }
    @Test
    void transactionRollbackRestoresProductStatus() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(state->{
            service.updateStatus(productId,new UpdateProductStatusRequest(ProductStatus.OFF_SHELF, 0L),"Bearer "+token);
            throw new IllegalStateException("模拟后续业务失败");
        }));
        assertEquals(ProductStatus.ON_SALE,repository.findById(productId).orElseThrow().status());
    }
    @Test
    void databaseRestrictsStatusAndNewProductsDefaultToOnSale() throws Exception {
        for (String invalid : new String[]{"BROKEN", "on_sale", "ON_SALE "}) {
            var failure = assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.update("UPDATE products SET status=? WHERE id=?", invalid, productId));
            assertTrue(failure.getMostSpecificCause().getMessage().contains("chk_products_status"));
            assertEquals(ProductStatus.ON_SALE, repository.findById(productId).orElseThrow().status());
        }
        assertEquals("ON_SALE",check(publish("新发布教材").header("Authorization","Bearer "+token),201).get("status").asText());
    }
    @Test
    void supportsCorsAndDoesNotCacheAuthenticatedResponses() throws Exception {
        for(String path:new String[]{"/api/products/mine","/api/products/"+productId}) {
            var response=mvc.perform(get(path).header("Authorization","Bearer "+token)).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        }
        var cors=mvc.perform(options("/api/products/"+productId+"/status").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","authorization,content-type")).andReturn().getResponse();
        assertEquals(200,cors.getStatus());assertTrue(cors.getHeader("Access-Control-Allow-Methods").contains("PUT"));
    }
}
