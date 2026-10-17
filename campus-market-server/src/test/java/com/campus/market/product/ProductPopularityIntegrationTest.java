package com.campus.market.product;

import com.campus.market.favorite.FavoriteService;
import org.junit.jupiter.api.*;
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
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON28_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON28_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*")
class ProductPopularityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FavoriteService favorites;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper json = JsonMapper.builder().build();
    String a, b, c;
    long userA, userB, userC, first, second, daily;
    JsonNode check(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    String login(String name) throws Exception {
        return check(post("/api/users/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", name, "password", "Campus2026!"))),200)
                .get("accessToken").asText();
    }
    JsonNode publish(String title, String category) throws Exception {
        return check(post("/api/products").header("Authorization", "Bearer " + a)
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "title",title,"priceInCents",1250,"categoryCode",category))),201);
    }
    JsonNode product(long id) throws Exception { return check(get("/api/products/" + id),200); }
    long count(long id) throws Exception { return product(id).get("favoriteCount").asLong(); }
    void add(long id, String token) throws Exception {
        check(put("/api/favorites/" + id).header("Authorization", "Bearer " + token),200);
    }
    List<Long> ids(JsonNode page) {
        var result = new ArrayList<Long>();page.get("records").forEach(p -> result.add(p.get("id").asLong()));return result;
    }
    @BeforeEach void fixtures() throws Exception {
        assertTrue(System.getenv("LESSON28_DB_URL").contains("/campus_lesson28_verify?"));
        for (String table: List.of("product_comments","product_favorites","products","product_images","login_sessions","users"))
            jdbc.update("DELETE FROM " + table);
        for (String name: List.of("owner_01","buyer_01","buyer_02"))
            check(post("/api/users/register").contentType("application/json").content(json.writeValueAsString(
                    Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        a=login("owner_01");b=login("buyer_01");c=login("buyer_02");
        userA=jdbc.queryForObject("SELECT id FROM users WHERE username='owner_01'",Long.class);
        userB=jdbc.queryForObject("SELECT id FROM users WHERE username='buyer_01'",Long.class);
        userC=jdbc.queryForObject("SELECT id FROM users WHERE username='buyer_02'",Long.class);
        first=publish("教材100%甲","BOOK").get("id").asLong();
        second=publish("教材100%乙","BOOK").get("id").asLong();
        daily=publish("日用台灯","DAILY").get("id").asLong();
        jdbc.update("UPDATE products SET created_at='2026-01-01 10:00:00'");
    }
    @Test void publicResponsesReturnZeroAndOnlyAggregateInformation() throws Exception {
        assertEquals(0,count(first));
        var page=check(get("/api/products"),200);assertEquals(3,page.get("total").asInt());
        page.get("records").forEach(row -> {assertEquals(0,row.get("favoriteCount").asLong());
            assertFalse(row.has("userIds"));assertFalse(row.has("favoriteUsers"));assertFalse(row.has("accessToken"));});
        assertEquals(0,publish("新商品","OTHER").get("favoriteCount").asLong());
    }
    @Test void duplicateFavoriteAndRemovalCountAccountsAndPreserveVersion() throws Exception {
        add(first,b);add(first,b);assertEquals(1,count(first));
        add(first,c);assertEquals(2,count(first));
        check(delete("/api/favorites/"+first).header("Authorization","Bearer "+b),200);
        check(delete("/api/favorites/"+first).header("Authorization","Bearer "+b),200);
        assertEquals(1,count(first));assertEquals(0,product(first).get("version").asLong());
    }
    @Test void countsArePerProductAndAgreeWithDatabase() throws Exception {
        add(first,a);add(first,b);add(second,c);
        assertEquals(2,count(first));assertEquals(1,count(second));assertEquals(0,count(daily));
        for(long id:List.of(first,second,daily))assertEquals(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorites WHERE product_id=?",Long.class,id),count(id));
    }
    @Test void popularSortUsesCountThenTimestampThenId() throws Exception {
        add(first,b);add(second,b);add(second,c);
        assertEquals(List.of(second,first,daily),ids(check(get("/api/products").param("sort","MOST_FAVORITED"),200)));
        add(first,c);assertEquals(List.of(second,first,daily),ids(check(get("/api/products").param("sort","MOST_FAVORITED"),200)));
        jdbc.update("UPDATE products SET created_at='2026-01-02 10:00:00' WHERE id=?",first);
        assertEquals(List.of(first,second,daily),ids(check(get("/api/products").param("sort","MOST_FAVORITED"),200)));
    }
    @Test void tiedPopularityPaginationHasNoMissingOrDuplicateRowsWhenDataIsUnchanged() throws Exception {
        var found=new ArrayList<Long>();
        for(int page=1;page<=3;page++)found.addAll(ids(check(get("/api/products").param("sort","MOST_FAVORITED")
                .param("page",""+page).param("size","1"),200)));
        assertEquals(List.of(daily,second,first),found);assertEquals(3,new HashSet<>(found).size());
        assertTrue(ids(check(get("/api/products").param("sort","MOST_FAVORITED").param("page","9"),200)).isEmpty());
    }
    @Test void popularityCombinesCategoryAndLiteralSearch() throws Exception {
        add(daily,a);add(daily,b);add(daily,c);add(first,b);
        var page=check(get("/api/products").param("sort","MOST_FAVORITED").param("category","BOOK").param("keyword","100%"),200);
        assertEquals(2,page.get("total").asInt());assertEquals(List.of(first,second),ids(page));
        assertEquals(0,check(get("/api/products").param("sort","MOST_FAVORITED").param("category","SPORT"),200).get("total").asInt());
    }
    @Test void hiddenProductPopularityIsNotExposedToOtherUsers() throws Exception {
        add(first,b);jdbc.update("UPDATE products SET status='OFF_SHELF' WHERE id=?",first);
        check(get("/api/products/"+first),404);check(get("/api/products/"+first).header("Authorization","Bearer "+b),404);
        assertEquals(1,check(get("/api/products/"+first).header("Authorization","Bearer "+a),200).get("favoriteCount").asLong());
        assertFalse(ids(check(get("/api/products").param("sort","MOST_FAVORITED"),200)).contains(first));
        assertTrue(check(get("/api/favorites").header("Authorization","Bearer "+b),200).get("records").get(0).get("product").isNull());
    }
    @Test void myProductsAndFavoriteNestedProductsUseSameCount() throws Exception {
        add(first,a);add(first,b);
        var mine=check(get("/api/products/mine").header("Authorization","Bearer "+a),200);
        for(var row:mine.get("records"))if(row.get("id").asLong()==first)assertEquals(2,row.get("favoriteCount").asLong());
        assertEquals(2,check(get("/api/favorites").header("Authorization","Bearer "+b),200)
                .get("records").get(0).get("product").get("favoriteCount").asLong());
    }
    @Test void editingProductDoesNotResetItsPopularity() throws Exception {
        add(first,b);
        var result=check(put("/api/products/"+first).header("Authorization","Bearer "+a).contentType("application/json")
                .content(json.writeValueAsString(Map.of("title","已修改教材","description","","priceInCents",1300,"version",0))),200);
        assertEquals(1,result.get("favoriteCount").asLong());assertEquals(1,result.get("version").asLong());
    }
    @Test void simultaneousDuplicateFavoritesNeverOvercount() throws Exception {
        var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);var ready=new CountDownLatch(6);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(int n=0;n<6;n++)futures.add(pool.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return favorites.add(first,"Bearer "+b).favorite();}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();for(var future:futures)assertTrue(future.get(15,TimeUnit.SECONDS));
            assertEquals(1,count(first));assertEquals(0,product(first).get("version").asLong());
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void rolledBackFavoriteDoesNotLeaveCounterDrift() throws Exception {
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(?,?)",userB,first);
            throw new IllegalStateException("rollback test");
        }));assertEquals(0,count(first));
    }
    @Test void popularityReadDoesNotModifyRowsAndSortIsWhitelisted() throws Exception {
        add(first,b);var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");
        var beforeFavorites=jdbc.queryForList("SELECT * FROM product_favorites ORDER BY user_id,product_id");
        check(get("/api/products").param("sort","MOST_FAVORITED"),200);product(first);
        for(String code:List.of("most_favorited","MOST_FAVORITED ","favorite_count DESC","MOST_FAVORITED; DROP TABLE products"))
            check(get("/api/products").param("sort",code),400);
        assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));
        assertEquals(beforeFavorites,jdbc.queryForList("SELECT * FROM product_favorites ORDER BY user_id,product_id"));
    }
    @Test void existingProductForeignKeyIndexSupportsCountLookup() {
        assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='product_favorites'
                  AND column_name='product_id' AND seq_in_index=1
                """,Integer.class)>0);
        assertFalse(jdbc.queryForList("EXPLAIN SELECT COUNT(*) FROM product_favorites WHERE product_id=?",first).isEmpty());
    }
}
