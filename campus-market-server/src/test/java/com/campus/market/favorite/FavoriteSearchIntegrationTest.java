package com.campus.market.favorite;
import com.campus.market.auth.SessionService;
import com.campus.market.user.UserResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.notifications.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class FavoriteSearchIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson125_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson125_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired FavoriteRepository repository;@Autowired FavoriteService service;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String owner,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("product_favorites","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'favorite_owner','PRIVATE_HASH','甲'),(102,'favorite_other','PRIVATE_HASH','乙'),(103,'favorite_seller','PRIVATE_HASH','丙')");
        for(int id=201;id<=218;id++) {
            String title=switch(id) {
                case 207 -> "二手教材"; case 208 -> "台灯隐藏下架"; case 209 -> "台灯隐藏已售";
                case 210 -> "100%正版"; case 211 -> "百分测试"; case 212 -> "A_B书架"; case 213 -> "A1B书架";
                case 214 -> "惊喜!物品"; case 215 -> "C:\\path书籍"; case 216 -> "二手台灯其他";
                case 217 -> "O'Reilly指南"; case 218 -> "100%隐藏"; default -> "二手台灯"+id;
            };
            String status=id==209?"SOLD":id==208||id==218?"OFF_SHELF":"ON_SALE";
            jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status,version) VALUES(?,?,?,1000,103,?,0)",id,title,"搜索隔离描述",status);
            if(id!=216)jdbc.update("INSERT INTO product_favorites(user_id,product_id,created_at) VALUES(101,?,'2026-01-01 00:00:00')",id);
        }
        for(int id:List.of(201,216))jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(102,?)",id);
        owner=issue(101);other=issue(102);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"favorite_"+id,"隔离验收")).accessToken();}
    MockHttpServletResponse get(String token,String availability,int page,int size)throws Exception {
        var r=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/favorites").param("availability",availability).param("page",""+page).param("size",""+size);
        if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();
    }
    JsonNode node(MockHttpServletResponse r,int code)throws Exception {assertEquals(code,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode page(String filter)throws Exception{return node(get(owner,filter,1,50),200);}
    List<Long> ids(JsonNode n){var ids=new ArrayList<Long>();for(var row:n.path("records"))ids.add(row.path("productId").asLong());return ids;}
    Map<String,List<Map<String,Object>>> stable(){var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("users","login_sessions","products","product_favorites","market_orders","notification_outbox","user_notifications"))result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return result;}
    MockHttpServletResponse search(String token,String keyword,String availability,int page,int size)throws Exception {
        var r=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/favorites").param("keyword",keyword).param("availability",availability).param("page",""+page).param("size",""+size);
        if(token!=null)r.header("Authorization",token);return mvc.perform(r).andReturn().getResponse();
    }
    JsonNode search(String keyword)throws Exception{return node(search(owner,keyword,"ALL",1,50),200);}
    @Test void omittedKeywordKeepsAllFavoritesAndOriginalShape()throws Exception {var n=page("ALL");assertEquals(17,n.path("total").asLong());assertEquals(Set.of("records","total","page","size"),new HashSet<>(n.propertyNames()));}
    @Test void trimAndFilterHappenBeforeCountAndLimit()throws Exception {var a=node(search(owner,"  台灯  ","ALL",1,5),200);var b=node(search(owner,"台灯","ALL",2,5),200);assertEquals(6,a.path("total").asLong());assertEquals(6,b.path("total").asLong());assertEquals(List.of(206L,205L,204L,203L,202L),ids(a));assertEquals(List.of(201L),ids(b));}
    @Test void availabilityAndSearchAreIntersection()throws Exception {assertEquals(ids(search("台灯")),ids(node(search(owner,"台灯","AVAILABLE",1,50),200)));assertEquals(0,node(search(owner,"台灯","UNAVAILABLE",1,50),200).path("total").asLong());}
    @Test void hiddenTitlesCannotBeInferredThroughCounts()throws Exception {for(String keyword:List.of("隐藏","隐藏下架","隐藏已售","100%隐藏"))for(String filter:List.of("ALL","AVAILABLE","UNAVAILABLE")){var n=node(search(owner,keyword,filter,1,50),200);assertEquals(0,n.path("total").asLong());assertTrue(n.path("records").isEmpty());assertFalse(n.toString().contains("隐藏"));}}
    @Test void emptySearchStillShowsNullUnavailableRows()throws Exception {var n=node(search(owner,"  ","UNAVAILABLE",1,50),200);assertEquals(List.of(218L,209L,208L),ids(n));for(var row:n.path("records"))assertTrue(row.path("product").isNull());}
    @Test void literalPercentDoesNotBecomeWildcard()throws Exception {assertEquals(List.of(210L),ids(search("%")));assertEquals(List.of(210L),ids(search("100%")));}
    @Test void literalUnderscoreDoesNotMatchArbitraryCharacter()throws Exception {assertEquals(List.of(212L),ids(search("_")));assertEquals(List.of(212L),ids(search("A_B")));}
    @Test void literalEscapeAndBackslashRemainText()throws Exception {assertEquals(List.of(214L),ids(search("!")));assertEquals(List.of(215L),ids(search("\\")));}
    @Test void quoteAndInjectionAreBoundText()throws Exception {assertEquals(List.of(217L),ids(search("O'Reilly")));assertEquals(0,search("' OR 1=1 --").path("total").asLong());}
    @Test void currentAccountOwnsSearchRegardlessOfForgedUserId()throws Exception {var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/favorites").param("keyword","台灯").param("userId","101").header("Authorization",other)).andReturn().getResponse();assertEquals(List.of(216L,201L),ids(node(r,200)));}
    @Test void invalidKeywordUses400AndAuthenticatedValidation()throws Exception {for(String keyword:List.of("x".repeat(51),"😀".repeat(26),"中\n间","中\u0085间"))node(search(owner,keyword,"ALL",1,5),400);node(search(null,"x".repeat(51),"ALL",1,5),401);}
    @Test void fiftyUtf16UnitsAreAccepted()throws Exception {jdbc.update("UPDATE products SET title=? WHERE id=201","x".repeat(50));assertEquals(List.of(201L),ids(search("x".repeat(50))));}
    @Test void outOfRangePageKeepsFilteredCountAndRequestedPage()throws Exception {var n=node(search(owner,"台灯","ALL",99,5),200);assertEquals(6,n.path("total").asLong());assertEquals(99,n.path("page").asInt());assertTrue(n.path("records").isEmpty());}
    @Test void revokedAndExpiredTokensCannotSearch()throws Exception {sessions.logout(owner);node(search(owner,"台灯","ALL",1,5),401);jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE user_id=102");node(search(other,"台灯","ALL",1,5),401);}
    @Test void readDoesNotChangeBusinessRows() {var before=stable();for(String filter:List.of("ALL","AVAILABLE","UNAVAILABLE"))service.page(1,50,filter,"台灯",owner);assertEquals(before,stable());}
    @Test void legacyOverloadsAndNullKeywordRemainCompatible(){assertEquals(service.page(1,5,"ALL",owner),service.page(1,5,"ALL",null,owner));assertEquals(repository.count(101,"ALL"),repository.count(101,"ALL",""));assertEquals(repository.findPage(101,5,0,"ALL"),repository.findPage(101,5,0,"ALL",""));}
    @Test void statusChangeRemovesSearchMatchButPreservesFavorite(){assertEquals(6,service.page(1,50,"ALL","台灯",owner).total());jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=206");assertEquals(5,service.page(1,50,"ALL","台灯",owner).total());assertEquals(17,service.page(1,50,owner).total());}
    @Test void databaseFailureIsNotSuccessfulEmptySearch()throws Exception {jdbc.execute("RENAME TABLE product_favorites TO favorite_search_private_failure");try{node(search(owner,"台灯","ALL",1,5),500);}finally{jdbc.execute("RENAME TABLE favorite_search_private_failure TO product_favorites");}}
    @Test void repeatableReadUsesSameTitleSnapshot(){var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(6,service.page(1,50,"ALL","台灯",owner).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->jdbc.update("UPDATE products SET title='已改名商品',version=version+1 WHERE id=206"))).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(6,service.page(1,50,"ALL","台灯",owner).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(5,service.page(1,50,"ALL","台灯",owner).total());}
}
