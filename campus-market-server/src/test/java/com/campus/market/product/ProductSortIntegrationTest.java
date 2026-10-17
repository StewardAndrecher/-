package com.campus.market.product;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON28_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON28_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*")
class ProductSortIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductService service;
    final JsonMapper json=JsonMapper.builder().build();
    long low, same, high, hidden, daily;
    JsonNode check(MockHttpServletRequestBuilder req,int status)throws Exception {
        var r=mvc.perform(req).andReturn().getResponse();
        assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));
        return r.getContentAsByteArray().length==0?null:json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    long insert(String title,long price,String category,String date,String status) {
        jdbc.update("INSERT INTO products(title,description,price_in_cents,category_code,created_at,status) VALUES(?,'排序测试',?,?,?,?)",title,price,category,date,status);
        return jdbc.queryForObject("SELECT id FROM products WHERE title=?",Long.class,title);
    }
    @BeforeEach void fixtures() {
        assertTrue(System.getenv("LESSON28_DB_URL").contains("/campus_lesson28_verify?"));
        for(String table:List.of("product_comments","product_favorites","products"))jdbc.update("DELETE FROM "+table);
        low=insert("教材100%甲",1200,"BOOK","2026-01-03 10:00:00","ON_SALE");
        same=insert("教材100%乙",1200,"BOOK","2026-01-02 10:00:00","ON_SALE");
        high=insert("教材100X丙",5800,"BOOK","2026-01-03 10:00:00","ON_SALE");
        hidden=insert("教材下架",1,"BOOK","2026-01-05 10:00:00","OFF_SHELF");
        daily=insert("生活台灯",3500,"DAILY","2026-01-01 10:00:00","ON_SALE");
    }
    List<Long> ids(JsonNode page) {
        var ids=new ArrayList<Long>();page.get("records").forEach(row->ids.add(row.get("id").asLong()));return ids;
    }
    @Test void catalogIsPublicChineseCanonicalAndNotCached()throws Exception {
        var r=mvc.perform(get("/api/product-sorts").header("Origin","http://localhost:5173")).andReturn().getResponse();
        assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));
        assertEquals("http://localhost:5173",r.getHeader("Access-Control-Allow-Origin"));
        var list=json.readTree(r.getContentAsString(StandardCharsets.UTF_8));assertEquals(4,list.size());
        assertEquals("NEWEST",list.get(0).get("code").asText());assertEquals("价格从低到高",list.get(1).get("name").asText());
        assertFalse(r.containsHeader("Set-Cookie"));
    }
    @Test void catalogRejectsWritesAndForeignBrowserOrigin()throws Exception {
        assertEquals(405,mvc.perform(post("/api/product-sorts")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/product-sorts").header("Origin","https://foreign.example")).andReturn().getResponse().getStatus());
    }
    @Test void omittedAndEmptySortUseTimestampThenIdDescending()throws Exception {
        assertEquals(List.of(high,low,same,daily),ids(check(get("/api/products"),200)));
        assertEquals(List.of(high,low,same,daily),ids(check(get("/api/products").param("sort",""),200)));
        assertEquals(List.of(high,low,same,daily),ids(check(get("/api/products").param("sort","NEWEST"),200)));
    }
    @Test void priceAscendingHasDeterministicSamePriceOrder()throws Exception {
        var p=check(get("/api/products").param("sort","PRICE_ASC"),200);
        assertEquals(4,p.get("total").asInt());assertEquals(List.of(same,low,daily,high),ids(p));
    }
    @Test void priceDescendingHasDeterministicSamePriceOrder()throws Exception {
        assertEquals(List.of(high,daily,same,low),ids(check(get("/api/products").param("sort","PRICE_DESC"),200)));
    }
    @Test void ascendingPagesHaveNoDuplicateOrMissingTiedRows()throws Exception {
        var found=new ArrayList<Long>();
        for(int page=1;page<=4;page++) {
            var p=check(get("/api/products").param("sort","PRICE_ASC").param("page",""+page).param("size","1"),200);
            assertEquals(4,p.get("total").asInt());found.addAll(ids(p));
        }
        assertEquals(List.of(same,low,daily,high),found);assertEquals(4,new HashSet<>(found).size());
    }
    @Test void descendingPagesAndOutOfRangePageAreConsistent()throws Exception {
        var first=check(get("/api/products").param("sort","PRICE_DESC").param("size","2"),200);
        var second=check(get("/api/products").param("sort","PRICE_DESC").param("size","2").param("page","2"),200);
        assertEquals(List.of(high,daily),ids(first));assertEquals(List.of(same,low),ids(second));
        var empty=check(get("/api/products").param("sort","PRICE_DESC").param("page","8"),200);
        assertEquals(4,empty.get("total").asInt());assertTrue(ids(empty).isEmpty());
    }
    @Test void keywordCategoryAndSortCombineWithLiteralWildcard()throws Exception {
        var p=check(get("/api/products").param("category","BOOK").param("keyword","100%").param("sort","PRICE_DESC"),200);
        assertEquals(2,p.get("total").asInt());assertEquals(List.of(same,low),ids(p));
        assertEquals(List.of(same,low,high),ids(check(get("/api/products").param("category","BOOK").param("sort","PRICE_ASC"),200)));
        assertEquals(0,check(get("/api/products").param("category","SPORT").param("sort","PRICE_ASC"),200).get("total").asInt());
    }
    @Test void unknownLowercaseWhitespaceAndSqlFragmentsAreRejected()throws Exception {
        for(String code:List.of("price_asc","PRICE_ASC ","RELEVANCE","p.price_in_cents DESC","NEWEST; DROP TABLE products"))
            check(get("/api/products").param("sort",code),400);
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
    }
    @Test void legacyServiceOverloadsContinueToUseNewest() {
        assertEquals(List.of(high,low,same,daily),service.listProducts(1,10,"").records().stream().map(ProductResponse::id).toList());
        assertEquals(List.of(high,low,same),service.listProducts(1,10,"","BOOK").records().stream().map(ProductResponse::id).toList());
    }
    @Test void extremeCentAmountsAreSortedAsNumbers()throws Exception {
        long min=insert("极低价格",1,"OTHER","2026-01-01 10:00:00","ON_SALE");
        long max=insert("极高价格",99999999,"OTHER","2026-01-01 10:00:00","ON_SALE");
        var p=check(get("/api/products").param("sort","PRICE_ASC"),200);
        assertEquals(min,ids(p).get(0));assertEquals(max,ids(p).get(ids(p).size()-1));
    }
    @Test void sortingDoesNotChangeBusinessRowsOrVersions()throws Exception {
        var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");
        for(String code:List.of("NEWEST","PRICE_ASC","PRICE_DESC"))check(get("/api/products").param("sort",code),200);
        assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));
    }
}
