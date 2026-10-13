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
        "spring.datasource.url=${LESSON22_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root", "spring.datasource.password="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON22_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson22_verify\\?.*")
class ProductEditIntegrationTest {
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
        String url = System.getenv("LESSON22_DB_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson22_verify\\?.*"));
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

    private MockHttpServletRequestBuilder edit(String body) {
        return put("/api/products/" + productId).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body);
    }

    private String validBody() {
        return mapper.writeValueAsString(Map.of("title", "修改后的台灯", "description", "成色良好", "priceInCents", 1250));
    }

    private void assertOriginal() {
        var row = repository.findById(productId).orElseThrow();
        assertEquals("待编辑台灯", row.title());
        assertEquals("测试商品", row.description());
        assertEquals(3500, row.priceInCents());
        assertEquals(sellerId, row.seller().id());
        assertEquals(2, count());
    }

    @Test
    void ownerCanUpdateFieldsButCannotTransferOrChangeIdentity() throws Exception {
        var createdAt = jdbc.queryForObject("SELECT created_at FROM products WHERE id=?", java.sql.Timestamp.class, productId);
        var body = mapper.writeValueAsString(Map.of("id", 999999, "sellerId", otherId,
                "seller", Map.of("id", otherId, "nickname", "伪造卖家"),
                "title", "  修改后的台灯  ", "description", "  成色良好  ", "priceInCents", 1250));
        var updated = check(edit(body), 200);
        assertEquals(productId, updated.get("id").asLong());
        assertEquals("修改后的台灯", updated.get("title").asText());
        assertEquals("成色良好", updated.get("description").asText());
        assertEquals(1250, updated.get("priceInCents").asLong());
        assertEquals(sellerId, updated.get("seller").get("id").asLong());
        assertEquals(2, updated.get("seller").size());
        assertEquals(createdAt, jdbc.queryForObject("SELECT created_at FROM products WHERE id=?", java.sql.Timestamp.class, productId));
        assertEquals(2, count());
        assertEquals("修改后的台灯", check(get("/api/products/" + productId), 200).get("title").asText());
        assertEquals(1, check(get("/api/products").param("keyword", "修改后的"), 200).get("total").asLong());
    }

    @Test
    void unchangedSaveSucceedsWithoutDuplicatingProduct() throws Exception {
        var body = mapper.writeValueAsString(Map.of("title", "待编辑台灯", "description", "测试商品", "priceInCents", 3500));
        check(edit(body), 200);
        check(edit(body), 200);
        assertOriginal();
    }

    @Test
    void rejectsStrangerEvenWhenRequestClaimsToBeOwner() throws Exception {
        String strangerToken = check(post("/api/users/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", "owner_02", "password", "Campus2026!"))), 200)
                .get("accessToken").asText();
        var body = mapper.writeValueAsString(Map.of("title", "越权修改", "priceInCents", 1, "sellerId", sellerId));
        var denied = check(put("/api/products/" + productId).contentType("application/json")
                .header("Authorization", "Bearer " + strangerToken).content(body), 403);
        assertEquals("PRODUCT_FORBIDDEN", denied.get("code").asText());
        check(get("/api/users/me").header("Authorization", "Bearer " + strangerToken), 200);
        assertOriginal();
    }

    @Test
    void rejectsMissingUnknownRevokedAndExpiredSessions() throws Exception {
        check(put("/api/products/" + productId).contentType("application/json").content(validBody()), 401);
        check(put("/api/products/" + productId).contentType("application/json")
                .header("Authorization", "Bearer " + "A".repeat(43)).content(validBody()), 401);
        check(post("/api/users/logout").header("Authorization", "Bearer " + token), 204);
        check(edit(validBody()), 401);
        token = login();
        jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)");
        check(edit(validBody()), 401);
        assertOriginal();
    }

    @Test
    void handlesLegacyMissingInvalidAndMalformedResources() throws Exception {
        check(put("/api/products/" + legacyId).contentType("application/json")
                .header("Authorization", "Bearer " + token).content(validBody()), 403);
        check(put("/api/products/999999999").contentType("application/json")
                .header("Authorization", "Bearer " + token).content(validBody()), 404);
        check(put("/api/products/0").contentType("application/json")
                .header("Authorization", "Bearer " + token).content(validBody()), 400);
        check(edit("{"), 400);
        assertOriginal();
    }

    @Test
    void rejectsInvalidFieldsAndAllowsClearingDescription() throws Exception {
        for (String json : new String[]{
                "{\"title\":\"a\",\"priceInCents\":1}",
                "{\"title\":\"有效标题\",\"priceInCents\":0}",
                "{\"title\":\"有效标题\",\"priceInCents\":100000000}",
                "{\"title\":\"有效标题\"}",
                mapper.writeValueAsString(Map.of("title", "有效标题", "description", "a".repeat(501), "priceInCents", 1))}) {
            check(edit(json), 400);
            assertOriginal();
        }
        var response = mvc.perform(ProductTestRequests.withCurrentVersion(edit("{\"title\":\"有效标题\",\"priceInCents\":1}"), jdbc)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("", mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("description").asText());
        var cors = mvc.perform(options("/api/products/" + productId).header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type")).andReturn().getResponse();
        assertEquals(200, cors.getStatus());
        assertTrue(cors.getHeader("Access-Control-Allow-Methods").contains("PUT"));
    }

    @Test
    void transactionRollbackRestoresOriginalProduct() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            service.updateProduct(productId, new UpdateProductRequest("事务中修改", "测试", 1L, null, 0L), "Bearer " + token);
            throw new IllegalStateException("模拟后续业务步骤失败");
        }));
        assertOriginal();
    }

    @Test
    void concurrentEditWaitsForLockedProduct() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                repository.findByIdForUpdate(productId).orElseThrow();
                locked.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("锁测试超时"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var editor = executor.submit(() -> {
                started.countDown();
                return service.updateProduct(productId, new UpdateProductRequest("并发修改台灯", "测试", 1250L, null, 0L), "Bearer " + token);
            });
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> editor.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("并发修改台灯", editor.get(5, java.util.concurrent.TimeUnit.SECONDS).title());
        } finally {
            release.countDown(); executor.shutdownNow();
        }
    }
}
