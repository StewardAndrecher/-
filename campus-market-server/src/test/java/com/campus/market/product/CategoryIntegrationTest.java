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
class CategoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    String token, otherToken;
    JsonNode check(MockHttpServletRequestBuilder request,int status)throws Exception {
        var r=mvc.perform(request).andReturn().getResponse();
        assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));
        return r.getContentAsByteArray().length==0 ? null : json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    @BeforeEach void fixtures()throws Exception {
        assertTrue(System.getenv("LESSON28_DB_URL").contains("/campus_lesson28_verify?"));
        for(String table:List.of("product_comments","product_favorites","products","product_images","login_sessions","users"))
            jdbc.update("DELETE FROM "+table);
        var tokens=new ArrayList<String>();
        for(String name:List.of("category_01","category_02")) {
            check(post("/api/users/register").contentType("application/json").content(json.writeValueAsString(
                    Map.of("username",name,"password","Campus2026!","nickname",name))),201);
            tokens.add(check(post("/api/users/login").contentType("application/json").content(json.writeValueAsString(
                    Map.of("username",name,"password","Campus2026!"))),200).get("accessToken").asText());
        }
        token=tokens.get(0);otherToken=tokens.get(1);
    }
    JsonNode create(String title,String category)throws Exception {
        var body=new HashMap<String,Object>(Map.of("title",title,"description","分类测试","priceInCents",1200));
        if(category!=null)body.put("categoryCode",category);
        return check(post("/api/products").header("Authorization","Bearer "+token).contentType("application/json")
                .content(json.writeValueAsString(body)),201);
    }
    MockHttpServletRequestBuilder edit(JsonNode item,String category,long version,String actor) {
        var body=new HashMap<String,Object>(Map.of("title",item.get("title").asText(),"description","分类测试",
                "priceInCents",1200,"version",version));
        if(category!=null)body.put("categoryCode",category);
        return put("/api/products/"+item.get("id").asLong()).header("Authorization","Bearer "+actor)
                .contentType("application/json").content(json.writeValueAsString(body));
    }
    @Test void catalogIsPublicCanonicalAndNotCached()throws Exception {
        var r=mvc.perform(get("/api/categories").header("Origin","http://localhost:5173")).andReturn().getResponse();
        assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));
        assertEquals("http://localhost:5173",r.getHeader("Access-Control-Allow-Origin"));
        var items=json.readTree(r.getContentAsString(StandardCharsets.UTF_8));assertEquals(6,items.size());
        assertEquals("UNCLASSIFIED",items.get(0).get("code").asText());assertEquals("教材书籍",items.get(1).get("name").asText());
        assertEquals(405,mvc.perform(post("/api/categories")).andReturn().getResponse().getStatus());
    }
    @Test void omittedCategoryAndDatabaseDefaultAreUnclassified()throws Exception {
        assertEquals("UNCLASSIFIED",create("旧版发布商品",null).get("category").get("code").asText());
        jdbc.update("INSERT INTO products(title,description,price_in_cents) VALUES('历史商品','保留',3500)");
        var page=check(get("/api/products").param("category","UNCLASSIFIED"),200);
        assertEquals(2,page.get("total").asInt());assertEquals("未分类",page.get("records").get(0).get("category").get("name").asText());
    }
    @Test void categoryIsReturnedByDetailMineAndFavorites()throws Exception {
        var item=create("数码测试商品","DIGITAL");long id=item.get("id").asLong();
        assertEquals("数码电子",item.get("category").get("name").asText());
        assertEquals("DIGITAL",check(get("/api/products/"+id),200).get("category").get("code").asText());
        assertEquals("DIGITAL",check(get("/api/products/mine").header("Authorization","Bearer "+token),200)
                .get("records").get(0).get("category").get("code").asText());
        check(put("/api/favorites/"+id).header("Authorization","Bearer "+token),200);
        assertEquals("DIGITAL",check(get("/api/favorites").header("Authorization","Bearer "+token),200)
                .get("records").get(0).get("product").get("category").get("code").asText());
    }
    @Test void filterAndPaginationHaveTheSameCountAndExcludeOffShelf()throws Exception {
        var first=create("教材甲","BOOK");var second=create("教材乙","BOOK");var hidden=create("教材下架","BOOK");create("台灯","DAILY");
        jdbc.update("UPDATE products SET status='OFF_SHELF' WHERE id=?",hidden.get("id").asLong());
        var one=check(get("/api/products").param("category","BOOK").param("size","1"),200);
        assertEquals(2,one.get("total").asInt());assertEquals(1,one.get("records").size());
        assertEquals(second.get("id").asLong(),one.get("records").get(0).get("id").asLong());
        var two=check(get("/api/products").param("category","BOOK").param("page","2").param("size","1"),200);
        assertEquals(2,two.get("total").asInt());assertEquals(first.get("id").asLong(),two.get("records").get(0).get("id").asLong());
        assertEquals(0,check(get("/api/products").param("category","SPORT"),200).get("total").asInt());
        assertEquals(3,check(get("/api/products"),200).get("total").asInt());
    }
    @Test void keywordAndCategoryAreCombinedWithLiteralWildcards()throws Exception {
        create("教材100%","BOOK");create("教材100X","BOOK");create("台灯100%","DAILY");
        var p=check(get("/api/products").param("category","BOOK").param("keyword","100%"),200);
        assertEquals(1,p.get("total").asInt());assertEquals("教材100%",p.get("records").get(0).get("title").asText());
    }
    @Test void invalidFilterCodesAreRejected()throws Exception {
        for(String code:List.of("book","BOOK ","UNKNOWN","' OR 1=1 --"))
            check(get("/api/products").param("category",code),400);
    }
    @Test void invalidPublishingCategoryCreatesNoProduct()throws Exception {
        for(String code:List.of("","book","UNKNOWN"))check(post("/api/products").header("Authorization","Bearer "+token)
                .contentType("application/json").content(json.writeValueAsString(Map.of("title","非法分类","priceInCents",1200,"categoryCode",code))),400);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));
    }
    @Test void categoryOnlyChangeAdvancesVersionAndNoOpDoesNot()throws Exception {
        var item=create("分类编辑商品","BOOK");
        var changed=check(edit(item,"DAILY",0,token),200);assertEquals(1,changed.get("version").asLong());
        assertEquals("DAILY",changed.get("category").get("code").asText());
        assertEquals(1,check(edit(item,"DAILY",1,token),200).get("version").asLong());
        assertEquals("UNCLASSIFIED",check(edit(item,"UNCLASSIFIED",1,token),200).get("category").get("code").asText());
    }
    @Test void omittedUpdateCategoryPreservesExistingCategory()throws Exception {
        var item=create("兼容更新商品","SPORT");
        assertEquals("SPORT",check(edit(item,null,0,token),200).get("category").get("code").asText());
        assertEquals(0,jdbc.queryForObject("SELECT version FROM products WHERE id=?",Long.class,item.get("id").asLong()));
    }
    @Test void staleCategoryChangeReturnsConflictWithoutOverwriting()throws Exception {
        var item=create("并发分类商品","BOOK");check(edit(item,"DAILY",0,token),200);
        check(edit(item,"DIGITAL",0,token),409);
        assertEquals("DAILY",jdbc.queryForObject("SELECT category_code FROM products WHERE id=?",String.class,item.get("id").asLong()));
    }
    @Test void categoryChangesRequireOwnerAndValidCode()throws Exception {
        var item=create("权限分类商品","BOOK");
        check(edit(item,"DAILY",0,otherToken),403);check(edit(item,"bad",0,token),400);
        assertEquals("BOOK",jdbc.queryForObject("SELECT category_code FROM products WHERE id=?",String.class,item.get("id").asLong()));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM products WHERE id=?",Long.class,item.get("id").asLong()));
    }
    @Test void databaseRejectsUnknownAndLowercaseCodes()throws Exception {
        var item=create("数据库分类商品","OTHER");
        for(String code:List.of("book","INVALID"))assertThrows(org.springframework.dao.DataAccessException.class,()->
                jdbc.update("UPDATE products SET category_code=? WHERE id=?",code,item.get("id").asLong()));
    }
}
