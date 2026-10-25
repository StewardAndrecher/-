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
        "spring.datasource.url=${LESSON21_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root", "spring.datasource.password="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON21_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson21_verify\\?.*")
class ProductOwnershipIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private long sellerId;
    private long otherId;
    private long legacyId;
    private String token;

    private JsonNode check(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
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
        String url = System.getenv("LESSON21_DB_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson21_verify\\?.*"));
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
    }

    @Test
    void rejectsMissingMalformedExpiredAndRevokedSessionsWithoutInserting() throws Exception {
        for (String header : new String[]{"", "Basic test", "Bearer short", "Bearer " + "A".repeat(43)}) {
            var request = publish("无权限台灯");
            if (!header.isEmpty()) request.header("Authorization", header);
            assertEquals("LOGIN_REQUIRED", check(request, 401).get("code").asText());
            assertEquals(1, count());
        }
        check(post("/api/users/logout").header("Authorization", "Bearer " + token), 204);
        check(publish("已退出台灯").header("Authorization", "Bearer " + token), 401);
        token = login();
        jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)");
        check(publish("已过期台灯").header("Authorization", "Bearer " + token), 401);
        assertEquals(1, count());
    }

    @Test
    void derivesOwnerFromSessionEvenIfClientSuppliesAnotherSeller() throws Exception {
        var body = Map.of("title", "闲置教材", "description", "正常使用", "priceInCents", 3500,
                "sellerId", otherId, "userId", otherId,
                "seller", Map.of("id", otherId, "nickname", "伪造昵称"));
        var product = check(post("/api/products").contentType("application/json")
                .header("Authorization", "Bearer " + token).content(mapper.writeValueAsString(body)), 201);
        assertEquals(sellerId, product.get("seller").get("id").asLong());
        assertEquals("测试卖家甲", product.get("seller").get("nickname").asText());
        assertEquals(5, product.get("seller").size());
        assertEquals(sellerId, jdbc.queryForObject("SELECT seller_id FROM products WHERE id=?",
                Long.class, product.get("id").asLong()));
        assertEquals(2, count());
    }

    @Test
    void keepsBrowsingPublicAndIncludesLegacyAndCurrentSellerData() throws Exception {
        var created = check(publish("台灯%_特价").header("Authorization", "Bearer " + token), 201);
        var legacy = check(get("/api/products/" + legacyId), 200);
        assertTrue(legacy.get("seller").isNull());
        assertEquals("历史台灯", legacy.get("title").asText());
        var detail = check(get("/api/products/" + created.get("id").asLong()), 200);
        assertEquals(sellerId, detail.get("seller").get("id").asLong());
        var page = check(get("/api/products").param("size", "1"), 200);
        assertEquals(2, page.get("total").asLong());
        assertEquals(created.get("id").asLong(), page.get("records").get(0).get("id").asLong());
        var filtered = check(get("/api/products").param("keyword", "%_"), 200);
        assertEquals(1, filtered.get("total").asLong());
        check(get("/api/products/999999999"), 404);
        var cors = mvc.perform(options("/api/products").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")).andReturn().getResponse();
        assertEquals(200, cors.getStatus());
        assertTrue(cors.getHeader("Access-Control-Allow-Headers").toLowerCase().contains("authorization"));
    }

    @Test
    void preservesValidationAndDoesNotWriteInvalidProducts() throws Exception {
        for (String json : new String[]{
                "{\"title\":\"a\",\"priceInCents\":1}",
                "{\"title\":\"有效标题\",\"priceInCents\":0}",
                "{\"title\":\"有效标题\",\"priceInCents\":100000000}",
                "{\"title\":\"有效标题\"}",
                mapper.writeValueAsString(Map.of("title", "有效标题", "description", "a".repeat(501), "priceInCents", 1))}) {
            check(post("/api/products").contentType("application/json")
                    .header("Authorization", "Bearer " + token).content(json), 400);
            assertEquals(1, count());
        }
    }

    @Test
    void databaseRejectsMissingSellerAndProtectsProductOwnership() throws Exception {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO products(title,price_in_cents,seller_id) VALUES(?,?,?)", "不存在的卖家", 1, 999999999));
        check(publish("有归属台灯").header("Authorization", "Bearer " + token), 201);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM users WHERE id=?", sellerId));
        assertEquals(2, count());
    }
}
