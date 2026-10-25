package com.campus.market.seller;
import com.campus.market.product.*;
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
class SellerCategoryIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson126_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson126_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired ProductRepository repository;@Autowired SellerStoreService service;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String owner,other;
    @BeforeEach void fixtures(){
        for(String table:List.of("product_favorites","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'favorite_owner','PRIVATE_HASH','甲'),(102,'favorite_other','PRIVATE_HASH','乙'),(103,'favorite_seller','PRIVATE_HASH','丙')");
        long[] prices={1000,500,1000,4000,2500,3000};
        for(int id=201;id<=214;id++) {
            String category=id<=206||id==208||id==209||id==210||id==211?"BOOK":id==207?"DIGITAL":id==212?"UNCLASSIFIED":id==213?"DAILY":"OTHER";
            String status=id==208?"OFF_SHELF":id==209?"SOLD":"ON_SALE";
            Long seller=id==211?null:id==210?102L:101L;
            jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status,category_code,version,created_at) VALUES(?,?,?,?,?,?,?,0,'2026-01-01 00:00:00')",id,"卖家分类商品"+id,"仅私有测试",id<=206?prices[id-201]:100,seller,status,category);
        }
        jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(102,201),(103,201),(102,205)");
        owner=issue(101);other=issue(102);
    }
    String issue(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"favorite_"+id,"隔离验收")).accessToken();}
    MockHttpServletResponse query(long seller,String category,String sort,int page,int size)throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sellers/"+seller+"/products").param("category",category).param("sort",sort).param("page",""+page).param("size",""+size)).andReturn().getResponse();
    }
    JsonNode node(MockHttpServletResponse r,int code)throws Exception {assertEquals(code,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode page(String category)throws Exception{return node(query(101,category,"NEWEST",1,50),200);}
    List<Long> ids(JsonNode n){var ids=new ArrayList<Long>();for(var row:n.path("products").path("records"))ids.add(row.path("id").asLong());return ids;}
    Map<String,List<Map<String,Object>>> stable(){var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("users","login_sessions","products","product_favorites","market_orders","notification_outbox","user_notifications"))result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return result;}
    @Test void omittedCategoryPreservesPublicShapeAndAllOnSaleProducts()throws Exception {var n=node(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sellers/101/products")).andReturn().getResponse(),200);assertEquals(Set.of("seller","products"),new HashSet<>(n.propertyNames()));assertEquals(10,n.path("products").path("total").asLong());assertEquals(5,ids(n).size());}
    @Test void bookFilterRunsBeforeCountAndPagination()throws Exception {var a=node(query(101,"BOOK","NEWEST",1,5),200);var b=node(query(101,"BOOK","NEWEST",2,5),200);assertEquals(6,a.at("/products/total").asLong());assertEquals(6,b.at("/products/total").asLong());assertEquals(List.of(206L,205L,204L,203L,202L),ids(a));assertEquals(List.of(201L),ids(b));}
    @Test void everyCategoryHasIndependentCountsAndExactRecordCodes()throws Exception {var expected=Map.of("BOOK",6L,"DIGITAL",1L,"DAILY",1L,"SPORT",0L,"OTHER",1L,"UNCLASSIFIED",1L);for(var entry:expected.entrySet()){var n=page(entry.getKey());assertEquals(entry.getValue(),n.at("/products/total").asLong());for(var row:n.at("/products/records"))assertEquals(entry.getKey(),row.at("/category/code").asString());}}
    @Test void emptyCategoryIsAllWhileUnclassifiedIsOneSpecificCategory()throws Exception {assertEquals(10,page("").at("/products/total").asLong());assertEquals(List.of(212L),ids(page("UNCLASSIFIED")));}
    @Test void soldHiddenForeignAndUnownedProductsNeverEnterBookResults()throws Exception {var n=page("BOOK");for(var row:n.at("/products/records")){assertEquals(101,row.at("/seller/id").asLong());assertEquals("ON_SALE",row.path("status").asString());assertFalse(Set.of(208L,209L,210L,211L).contains(row.path("id").asLong()));}}
    @Test void publicSellerProjectionHasNoAccountFields()throws Exception {var n=page("BOOK");assertEquals(Set.of("id","nickname","avatarImageId","bio","campusCode"),new HashSet<>(n.path("seller").propertyNames()));for(String field:List.of("username","password","passwordHash","accessToken","roles"))assertFalse(n.path("seller").has(field));}
    @Test void fourSortsRemainScopedToBookFilter()throws Exception {assertEquals(List.of(206L,205L,204L,203L,202L,201L),ids(node(query(101,"BOOK","NEWEST",1,50),200)));assertEquals(List.of(202L,203L,201L,205L,206L,204L),ids(node(query(101,"BOOK","PRICE_ASC",1,50),200)));assertEquals(List.of(204L,206L,205L,203L,201L,202L),ids(node(query(101,"BOOK","PRICE_DESC",1,50),200)));assertEquals(List.of(201L,205L,206L,204L,203L,202L),ids(node(query(101,"BOOK","MOST_FAVORITED",1,50),200)));}
    @Test void nonexistentCategoryCodeWhitespaceAndSqlAre400()throws Exception {for(String code:List.of("book"," BOOK","BOOK ","UNKNOWN","BOOK' OR 1=1","BOOK,DAILY"))node(query(101,code,"NEWEST",1,5),400);}
    @Test void repeatedCategoryIsRejected()throws Exception {var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sellers/101/products").param("category","BOOK","DAILY")).andReturn().getResponse();node(r,400);}
    @Test void categoryAndSortBothValidated()throws Exception {node(query(101,"BOOK","price_asc",1,5),400);node(query(101,"BOOK","p.id DESC",1,5),400);node(query(101,"BOOK","NEWEST",0,5),400);node(query(101,"BOOK","NEWEST",1,51),400);}
    @Test void noMatchingAndEmptySellerAreSuccessfulEmptyPages()throws Exception {for(long id:List.of(101L,103L)){var n=node(query(id,"SPORT","NEWEST",1,5),200);assertEquals(id,n.at("/seller/id").asLong());assertEquals(0,n.at("/products/total").asLong());assertTrue(ids(n).isEmpty());}}
    @Test void missingSellerStill404()throws Exception {node(query(999999,"BOOK","NEWEST",1,5),404);}
    @Test void outOfRangeKeepsRequestedPageAndCategoryTotal()throws Exception {var n=node(query(101,"BOOK","NEWEST",99,5),200);assertEquals(99,n.at("/products/page").asInt());assertEquals(6,n.at("/products/total").asLong());assertTrue(ids(n).isEmpty());}
    @Test void tokenAndSpoofedOwnerOrStatusCannotExpandPublicScope()throws Exception {for(String token:List.of("Bearer invalid",owner,other)){var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sellers/101/products").param("category","BOOK").param("sellerId","102").param("status","OFF_SHELF").header("Authorization",token)).andReturn().getResponse();assertEquals(6,node(r,200).at("/products/total").asLong());assertFalse(r.containsHeader("Set-Cookie"));}}
    @Test void publicReadDoesNotWriteBusinessRows() {var before=stable();for(String category:List.of("","BOOK","DIGITAL","SPORT"))service.getStore(101,1,50,"NEWEST",category);assertEquals(before,stable());}
    @Test void oldOverloadsRemainAllAndNullServiceCategoryIsInvalid(){assertEquals(service.getStore(101,1,5,"NEWEST"),service.getStore(101,1,5,"NEWEST",""));assertEquals(repository.countOnSaleBySeller(101),repository.countOnSaleBySeller(101,null));assertEquals(repository.findPublicPageBySeller(101,ProductSort.NEWEST,5,0),repository.findPublicPageBySeller(101,ProductSort.NEWEST,5,0,null));assertThrows(InvalidProductException.class,()->service.getStore(101,1,5,"NEWEST",null));}
    @Test void categoryAndStatusChangeAreVisibleOnNextQuery(){assertEquals(6,service.getStore(101,1,50,"NEWEST","BOOK").products().total());jdbc.update("UPDATE products SET category_code='DAILY',version=version+1 WHERE id=206");assertEquals(5,service.getStore(101,1,50,"NEWEST","BOOK").products().total());jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=205");assertEquals(4,service.getStore(101,1,50,"NEWEST","BOOK").products().total());}
    @Test void databaseFailureIs500InsteadOfSuccessfulEmpty()throws Exception {jdbc.execute("RENAME TABLE products TO seller_category_private_failure");try{node(query(101,"BOOK","NEWEST",1,5),500);}finally{jdbc.execute("RENAME TABLE seller_category_private_failure TO products");}}
    @Test void repeatableReadRetainsCategorySnapshot(){var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.executeWithoutResult(status->{assertEquals(6,service.getStore(101,1,50,"NEWEST","BOOK").products().total());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->jdbc.update("UPDATE products SET category_code='DAILY',version=version+1 WHERE id=206"))).get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(6,service.getStore(101,1,50,"NEWEST","BOOK").products().total());}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}});assertEquals(5,service.getStore(101,1,50,"NEWEST","BOOK").products().total());}
}
