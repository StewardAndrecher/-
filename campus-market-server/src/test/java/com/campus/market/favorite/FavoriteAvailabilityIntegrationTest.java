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
class FavoriteAvailabilityIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson122_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson122_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired FavoriteRepository repository;@Autowired FavoriteService service;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String owner,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("product_favorites","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'favorite_owner','PRIVATE_HASH','甲'),(102,'favorite_other','PRIVATE_HASH','乙'),(103,'favorite_seller','PRIVATE_HASH','丙')");
        for(int i=201;i<=213;i++) {
            String status=i<=207||i==213?"ON_SALE":i<=210?"OFF_SHELF":"SOLD";
            jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status,version) VALUES(?,?,?,1000,103,?,0)",i,status.equals("ON_SALE")?"公开商品"+i:"不可见标题"+i,"隔离描述"+i,status);
            if(i<=212)jdbc.update("INSERT INTO product_favorites(user_id,product_id,created_at) VALUES(101,?,'2026-01-01 00:00:00')",i);
        }
        for(int id:List.of(201,208,213))jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(102,?)",id);
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
    @Test void omittedFilterKeepsLegacyShapeAndSize()throws Exception {var n=node(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/favorites").header("Authorization",owner)).andReturn().getResponse(),200);assertEquals(12,n.path("total").asLong());assertEquals(5,n.path("records").size());assertEquals(Set.of("records","total","page","size"),new HashSet<>(n.propertyNames()));assertEquals(List.of(212L,211L,210L,209L,208L),ids(n));}
    @Test void allStillIncludesUnavailableAsNull()throws Exception {var n=page("ALL");assertEquals(12,n.path("total").asLong());assertEquals(12,n.path("records").size());assertTrue(n.at("/records/0/product").isNull());}
    @Test void availableIncludesOnlyVisibleOnSaleProducts()throws Exception {var n=page("AVAILABLE");assertEquals(7,n.path("total").asLong());for(var row:n.path("records")){assertEquals("ON_SALE",row.path("product").path("status").asString());assertEquals(row.path("productId").asLong(),row.path("product").path("id").asLong());}}
    @Test void unavailableIncludesOffShelfAndSoldWithoutDetails()throws Exception {var n=page("UNAVAILABLE");assertEquals(5,n.path("total").asLong());for(var row:n.path("records")){assertEquals(Set.of("productId","product"),new HashSet<>(row.propertyNames()));assertTrue(row.path("product").isNull());}assertFalse(n.toString().contains("不可见标题"));assertFalse(n.toString().contains("隔离描述"));assertFalse(n.toString().contains("seller"));}
    @Test void countAndRowsFilterBeforePagination()throws Exception {var first=node(get(owner,"AVAILABLE",1,4),200);var second=node(get(owner,"AVAILABLE",2,4),200);assertEquals(7,first.path("total").asLong());assertEquals(List.of(207L,206L,205L,204L),ids(first));assertEquals(List.of(203L,202L,201L),ids(second));}
    @Test void unavailablePagingUsesSameCountAndStableTieBreak()throws Exception {var n=node(get(owner,"UNAVAILABLE",2,3),200);assertEquals(5,n.path("total").asLong());assertEquals(List.of(209L,208L),ids(n));}
    @Test void outOfRangeKeepsRequestedPageAndFilteredTotal()throws Exception {var n=node(get(owner,"UNAVAILABLE",99,5),200);assertEquals(5,n.path("total").asLong());assertEquals(99,n.path("page").asInt());assertEquals(0,n.path("records").size());}
    @Test void otherAccountSeesOnlyItsOwnFavorites()throws Exception {var a=node(get(other,"AVAILABLE",1,50),200);assertEquals(List.of(213L,201L),ids(a));assertEquals(2,a.path("total").asLong());assertEquals(List.of(208L),ids(node(get(other,"UNAVAILABLE",1,50),200)));}
    @Test void forgedOwnerQueryCannotChangeIdentity()throws Exception {var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/favorites").param("availability","UNAVAILABLE").param("userId","101").param("sellerId","101").header("Authorization",other)).andReturn().getResponse();assertEquals(1,node(r,200).path("total").asLong());}
    @Test void invalidCaseWhitespaceAndInjectionAreRejected()throws Exception {for(String v:List.of("available","AVAILABLE "," ALL","UNKNOWN","ALL' OR 1=1","constructor"))node(get(owner,v,1,5),400);}
    @Test void duplicateFiltersAreRejected()throws Exception {for(String[] v:new String[][]{{"ALL","ALL"},{"AVAILABLE","UNAVAILABLE"}}){var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/favorites").param("availability",v).header("Authorization",owner)).andReturn().getResponse();node(r,400);}}
    @Test void emptyParameterUsesDefaultAll()throws Exception {assertEquals(12,node(get(owner,"",1,50),200).path("total").asLong());}
    @Test void invalidPaginationStill400()throws Exception {for(int p:new int[]{0,-1})node(get(owner,"AVAILABLE",p,5),400);for(int size:new int[]{0,51})node(get(owner,"UNAVAILABLE",1,size),400);}
    @Test void anonymousAndForgedTokensRemain401()throws Exception {node(get(null,"INVALID",1,5),401);node(get("Bearer "+"A".repeat(43),"UNAVAILABLE",1,5),401);}
    @Test void revokedAndExpiredSessionsCannotFilter()throws Exception {sessions.logout(owner);node(get(owner,"AVAILABLE",1,5),401);jdbc.update("UPDATE login_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE user_id=102");node(get(other,"UNAVAILABLE",1,5),401);}
    @Test void emptyAccountReturnsRealZeroForEachFilter() {var token=issue(103);for(String v:List.of("ALL","AVAILABLE","UNAVAILABLE")){var n=service.page(1,5,v,token);assertEquals(0,n.total());assertTrue(n.records().isEmpty());}}
    @Test void readsDoNotModifyFavoritesProductsOrMessaging(){var before=stable();for(String filter:List.of("ALL","AVAILABLE","UNAVAILABLE"))service.page(1,50,filter,owner);assertEquals(before,stable());}
    @Test void allIsDisjointUnionOfAvailableAndUnavailable()throws Exception {var a=new HashSet<>(ids(page("AVAILABLE")));var u=new HashSet<>(ids(page("UNAVAILABLE")));assertTrue(Collections.disjoint(a,u));a.addAll(u);assertEquals(new HashSet<>(ids(page("ALL"))),a);}
    @Test void statusChangesMoveMembershipWithoutDroppingFavorite(){jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=207");assertEquals(6,service.page(1,50,"AVAILABLE",owner).total());assertEquals(6,service.page(1,50,"UNAVAILABLE",owner).total());assertEquals(12,service.page(1,50,owner).total());jdbc.update("UPDATE products SET status='ON_SALE',version=version+1 WHERE id=207");assertEquals(7,service.page(1,50,"AVAILABLE",owner).total());}
    @Test void oldServiceAndRepositoryOverloadsRetainAll(){assertEquals(service.page(1,5,"ALL",owner),service.page(1,5,owner));assertEquals(repository.count(101,"ALL"),repository.count(101));assertEquals(repository.findPage(101,5,0,"ALL"),repository.findPage(101,5,0));}
    @Test void explicitCancellationFromFilteredPageOnlyRemovesOwnRelation(){service.remove(208,owner);assertEquals(4,service.page(1,50,"UNAVAILABLE",owner).total());assertEquals(1,service.page(1,50,"UNAVAILABLE",other).total());assertEquals(13,jdbc.queryForObject("SELECT COUNT(*) FROM products",Integer.class));}
    @Test void databaseFailureIs500NotSuccessfulEmptyPage()throws Exception {jdbc.execute("RENAME TABLE product_favorites TO product_favorites_private_unavailable");try{node(get(owner,"UNAVAILABLE",1,5),500);}finally{jdbc.execute("RENAME TABLE product_favorites_private_unavailable TO product_favorites");}}
    @Test void directNullFilterIsValidationFailure(){assertThrows(com.campus.market.product.InvalidProductException.class,()->service.page(1,5,null,owner));}
    @Test void repeatableReadRetainsFilterSnapshotAcrossStatusChange(){var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(7,service.page(1,50,"AVAILABLE",owner).total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=207"))).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(7,service.page(1,50,"AVAILABLE",owner).total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(6,service.page(1,50,"AVAILABLE",owner).total());}
}
