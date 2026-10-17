package com.campus.market.seller;

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
class SellerStoreIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    long owner,other,empty,first,second,hidden,foreign,legacy;
    String token;
    JsonNode check(MockHttpServletRequestBuilder request,int status)throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();
        assertEquals(status,response.getStatus(),response.getContentAsString(StandardCharsets.UTF_8));
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    MockHttpServletRequestBuilder store(long id) { return get("/api/sellers/"+id+"/products"); }
    long insert(String title,Long seller,long price,String status,String date) {
        jdbc.update("INSERT INTO products(title,description,price_in_cents,seller_id,status,created_at) VALUES(?,'主页测试',?,?,?,?)",title,price,seller,status,date);
        return jdbc.queryForObject("SELECT id FROM products WHERE title=?",Long.class,title);
    }
    List<Long> ids(JsonNode response) {
        var result=new ArrayList<Long>();response.get("products").get("records").forEach(row->result.add(row.get("id").asLong()));return result;
    }
    @BeforeEach void fixtures()throws Exception {
        assertTrue(System.getenv("LESSON28_DB_URL").contains("/campus_lesson28_verify?"));
        for(String table:List.of("product_comments","product_favorites","products","product_images","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        for(String name:List.of("seller_01","seller_02","seller_03"))check(post("/api/users/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password","Campus2026!","nickname","同学"+name.substring(name.length()-1)))),201);
        owner=jdbc.queryForObject("SELECT id FROM users WHERE username='seller_01'",Long.class);
        other=jdbc.queryForObject("SELECT id FROM users WHERE username='seller_02'",Long.class);
        empty=jdbc.queryForObject("SELECT id FROM users WHERE username='seller_03'",Long.class);
        token=check(post("/api/users/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","seller_01","password","Campus2026!"))),200).get("accessToken").asText();
        first=insert("主页教材甲",owner,1000,"ON_SALE","2026-01-01 10:00:00");
        second=insert("主页台灯乙",owner,2500,"ON_SALE","2026-01-02 10:00:00");
        hidden=insert("主页下架物品",owner,1,"OFF_SHELF","2026-01-03 10:00:00");
        foreign=insert("另一卖家物品",other,10,"ON_SALE","2026-01-04 10:00:00");
        legacy=insert("历史无卖家物品",null,10,"ON_SALE","2026-01-04 10:00:00");
    }
    @Test void anonymousStoreExposesOnlyPublicSellerFieldsAndOnSaleItems()throws Exception {
        var result=check(store(owner),200);assertEquals(2,result.size());assertEquals(2,result.get("seller").size());
        assertEquals(owner,result.get("seller").get("id").asLong());assertEquals("同学1",result.get("seller").get("nickname").asText());
        for(String field:List.of("username","password","passwordHash","accessToken","createdAt"))assertFalse(result.get("seller").has(field));
        assertEquals(List.of(second,first),ids(result));assertEquals(2,result.get("products").get("total").asInt());
        for(var product:result.get("products").get("records"))assertEquals(owner,product.get("seller").get("id").asLong());
    }
    @Test void successIsNotCachedAndDoesNotCreateSessionCookie()throws Exception {
        var response=mvc.perform(store(owner)).andReturn().getResponse();assertEquals(200,response.getStatus());
        assertEquals("no-store",response.getHeader("Cache-Control"));assertFalse(response.containsHeader("Set-Cookie"));
    }
    @Test void evenOwnersCannotReadHiddenItemsThroughPublicStore()throws Exception {
        assertEquals(List.of(second,first),ids(check(store(owner).header("Authorization","Bearer "+token),200)));
        assertEquals(3,check(get("/api/products/mine").header("Authorization","Bearer "+token),200).get("total").asInt());
    }
    @Test void otherSellerAndLegacyProductsAreExcluded()throws Exception {
        assertEquals(List.of(foreign),ids(check(store(other),200)));
        assertFalse(ids(check(store(owner),200)).contains(legacy));assertFalse(ids(check(store(owner),200)).contains(foreign));
    }
    @Test void existingSellerWithoutProductsHasSuccessfulEmptyStore()throws Exception {
        var result=check(store(empty),200);assertEquals(empty,result.get("seller").get("id").asLong());
        assertEquals(0,result.get("products").get("total").asInt());assertTrue(ids(result).isEmpty());
    }
    @Test void missingSellerReturnsSpecific404()throws Exception {
        assertEquals("SELLER_NOT_FOUND",check(store(999999999),404).get("code").asText());
        assertEquals("no-store",mvc.perform(store(999999999)).andReturn().getResponse().getHeader("Cache-Control"));
    }
    @Test void invalidSellerIdsReturn400InsteadOfDatabaseErrors()throws Exception {
        for(long id:List.of(0L,-1L,9007199254740992L))assertEquals("INVALID_SELLER",check(store(id),400).get("code").asText());
        check(get("/api/sellers/bad/products"),400);check(get("/api/sellers/9223372036854775808/products"),400);
    }
    @Test void paginationIsValidatedAndBindsIntegerParameters()throws Exception {
        for(var values:List.of(new int[]{0,5},new int[]{-1,5},new int[]{1,0},new int[]{1,51}))check(store(owner).param("page",""+values[0]).param("size",""+values[1]),400);
        check(store(owner).param("page","1 OR 1=1"),400);
    }
    @Test void defaultAndExplicitPaginationHaveNoDuplicates()throws Exception {
        var one=check(store(owner).param("size","1"),200);var two=check(store(owner).param("size","1").param("page","2"),200);
        assertEquals(List.of(second),ids(one));assertEquals(List.of(first),ids(two));assertEquals(2,two.get("products").get("total").asInt());
        assertTrue(ids(check(store(owner).param("page","99"),200)).isEmpty());
    }
    @Test void priceAndPopularitySortsAreScopedToSeller()throws Exception {
        assertEquals(List.of(first,second),ids(check(store(owner).param("sort","PRICE_ASC"),200)));
        assertEquals(List.of(second,first),ids(check(store(owner).param("sort","PRICE_DESC"),200)));
        jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(?,?)",other,first);
        var result=check(store(owner).param("sort","MOST_FAVORITED"),200);assertEquals(List.of(first,second),ids(result));
        assertEquals(1,result.get("products").get("records").get(0).get("favoriteCount").asLong());
    }
    @Test void sortSqlFragmentsAndSpoofedFilterCannotExpandStore()throws Exception {
        for(String sort:List.of("most_favorited","MOST_FAVORITED; DROP TABLE products","p.id DESC"))check(store(owner).param("sort",sort),400);
        assertEquals(List.of(second,first),ids(check(store(owner).param("sellerId",""+other).param("status","OFF_SHELF"),200)));
    }
    @Test void tiedSortsHaveDeterministicIdOrder()throws Exception {
        jdbc.update("UPDATE products SET price_in_cents=1000,created_at='2026-01-01 10:00:00' WHERE seller_id=?",owner);
        for(String sort:List.of("NEWEST","PRICE_ASC","PRICE_DESC","MOST_FAVORITED"))assertEquals(List.of(second,first),ids(check(store(owner).param("sort",sort),200)));
    }
    @Test void nicknameAndAvailabilityChangesAppearOnNextQuery()throws Exception {
        jdbc.update("UPDATE users SET nickname='新的公开昵称' WHERE id=?",owner);jdbc.update("UPDATE products SET status='OFF_SHELF' WHERE id=?",second);
        var result=check(store(owner),200);assertEquals("新的公开昵称",result.get("seller").get("nickname").asText());
        assertEquals(List.of(first),ids(result));assertEquals("新的公开昵称",result.get("products").get("records").get(0).get("seller").get("nickname").asText());
        assertEquals(1,result.get("products").get("total").asInt());
    }
    @Test void publicReadWorksWithInvalidTokenAndDoesNotWriteBusinessRows()throws Exception {
        var before=jdbc.queryForList("SELECT * FROM users ORDER BY id");var beforeProducts=jdbc.queryForList("SELECT * FROM products ORDER BY id");
        check(store(owner).header("Authorization","Bearer invalid"),200);
        assertEquals(before,jdbc.queryForList("SELECT * FROM users ORDER BY id"));assertEquals(beforeProducts,jdbc.queryForList("SELECT * FROM products ORDER BY id"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product_favorites",Integer.class));
    }
    @Test void browserCorsAllowsLocalDevelopmentAndRejectsForeignOrigin()throws Exception {
        var response=mvc.perform(store(owner).header("Origin","http://localhost:5173")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("http://localhost:5173",response.getHeader("Access-Control-Allow-Origin"));
        assertEquals(403,mvc.perform(store(owner).header("Origin","https://foreign.example")).andReturn().getResponse().getStatus());
    }
    @Test void publicStoreRejectsWrites()throws Exception {
        assertEquals(405,mvc.perform(post("/api/sellers/"+owner+"/products")).andReturn().getResponse().getStatus());
    }
}
