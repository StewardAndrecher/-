package com.campus.market.product;
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
class ProductLookupIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson127_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson127_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired ProductRepository repository;@Autowired ProductLookupService service;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();String owner,other;
    @BeforeEach void fixtures() {
        for(String table:List.of("product_favorites","products","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'lookup_owner','PRIVATE_HASH','甲'),(102,'lookup_other','PRIVATE_HASH','乙'),(103,'lookup_empty','PRIVATE_HASH','丙')");
        for(int id=201;id<=225;id++) {
            String status=id==203?"OFF_SHELF":id==204?"SOLD":"ON_SALE";
            jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id,status,category_code,version) VALUES(?,?,?,1000,?,?, 'BOOK',0)",id,id==203||id==204?"隐藏PRIVATE"+id:"公开商品"+id,"私有验收描述",id==205?null:id==202?102L:101L,status);
        }
        jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(102,201),(103,201)");
        owner="Bearer "+sessions.issueToken(new UserResponse(101,"lookup_owner","甲")).accessToken();
    }
    MockHttpServletResponse lookup(String body)throws Exception {return mvc.perform(post("/api/products/lookup").contentType("application/json").content(body)).andReturn().getResponse();}
    MockHttpServletResponse lookup(List<Long> ids)throws Exception {return lookup(json.writeValueAsString(Map.of("productIds",ids)));}
    JsonNode node(MockHttpServletResponse r,int code)throws Exception {assertEquals(code,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    List<Long> ids(JsonNode n){var result=new ArrayList<Long>();for(var row:n.path("records"))result.add(row.path("productId").asLong());return result;}
    Map<String,List<Map<String,Object>>> stable(){var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:List.of("users","login_sessions","products","product_favorites","market_orders","notification_outbox","user_notifications"))result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return result;}
    @Test void orderedPublicBatchUsesOriginalOrderAndPublicShape()throws Exception {var n=node(lookup(List.of(202L,201L)),200);assertEquals(Set.of("records"),new HashSet<>(n.propertyNames()));assertEquals(List.of(202L,201L),ids(n));assertEquals(202,n.at("/records/0/product/id").asLong());assertEquals(2,n.at("/records/1/product/favoriteCount").asLong());}
    @Test void hiddenSoldAndUnknownAreIndistinguishableNullEntries()throws Exception {var n=node(lookup(List.of(203L,999L,204L)),200);assertEquals(List.of(203L,999L,204L),ids(n));for(var row:n.path("records")){assertEquals(Set.of("productId","product"),new HashSet<>(row.propertyNames()));assertTrue(row.path("product").isNull());}assertFalse(n.toString().contains("PRIVATE"));assertFalse(n.toString().contains("OFF_SHELF"));assertFalse(n.toString().contains("SOLD"));}
    @Test void mixedAvailabilityPreservesEveryRequestedPosition()throws Exception {var n=node(lookup(List.of(203L,201L,204L,202L)),200);assertEquals(4,n.path("records").size());assertTrue(n.at("/records/0/product").isNull());assertEquals(201,n.at("/records/1/product/id").asLong());assertTrue(n.at("/records/2/product").isNull());assertEquals(202,n.at("/records/3/product/id").asLong());}
    @Test void legacyProductWithoutSellerRemainsVisible()throws Exception {var n=node(lookup(List.of(205L)),200);assertTrue(n.at("/records/0/product/seller").isNull());assertEquals("ON_SALE",n.at("/records/0/product/status").asString());}
    @Test void twentyRequestsAcceptedAndTwentyOneRejected()throws Exception {var ids=new ArrayList<Long>();for(long id=206;id<226;id++)ids.add(id);assertEquals(20,node(lookup(ids),200).path("records").size());ids.add(201L);node(lookup(ids),400);}
    @Test void emptyMissingAndNullArrayAreRejected()throws Exception {for(String body:List.of("{}","{\"productIds\":[]}","{\"productIds\":null}"))node(lookup(body),400);}
    @Test void coercedStringsFloatsNullBooleanAndNestedIdsAreRejected()throws Exception {for(String value:List.of("\"201\"","201.0","2.01e2","null","true","{}","[]"))node(lookup("{\"productIds\":["+value+"]}"),400);}
    @Test void idsArePositiveSafeJsonIntegers()throws Exception {for(String value:List.of("0","-1","9007199254740992","9223372036854775808"))node(lookup("{\"productIds\":["+value+"]}"),400);assertTrue(node(lookup(List.of(9007199254740991L)),200).at("/records/0/product").isNull());}
    @Test void duplicatesAreRejectedInsteadOfChangingResultCardinality()throws Exception {node(lookup(List.of(201L,201L)),400);}
    @Test void unknownFieldsAndDuplicateKeysCannotUploadHistoryMetadata()throws Exception {for(String body:List.of("{\"productIds\":[201],\"userId\":101}","{\"productIds\":[201],\"viewedAt\":123}","{\"productIds\":[201],\"productIds\":[202]}","{\"productIds\":[201],\"other\":null}"))node(lookup(body),400);}
    @Test void malformedJsonAndTopLevelArraysAre400()throws Exception {for(String body:List.of("null","[]","1","{","{\"productIds\":[201]","{\"productIds\":[201,]}"))node(lookup(body),400);}
    @Test void suppliedTokenNeverGrantsPrivateOwnerVisibility()throws Exception {for(String token:List.of(owner,"Bearer invalid")){var response=mvc.perform(post("/api/products/lookup").header("Authorization",token).contentType("application/json").content("{\"productIds\":[203,201]}" )).andReturn().getResponse();var n=node(response,200);assertTrue(n.at("/records/0/product").isNull());assertEquals(201,n.at("/records/1/product/id").asLong());assertFalse(response.containsHeader("Set-Cookie"));}}
    @Test void publicProductProjectionContainsNoAccountSecrets()throws Exception {var n=node(lookup(List.of(201L)),200);assertEquals(Set.of("id","nickname","avatarImageId","bio","campusCode"),new HashSet<>(n.at("/records/0/product/seller").propertyNames()));for(String field:List.of("username","passwordHash","accessToken","roles","viewedAt"))assertFalse(n.toString().contains("\""+field+"\""));}
    @Test void batchMatchesAnonymousDetailForOnSaleProduct()throws Exception {var detail=json.readTree(mvc.perform(get("/api/products/201")).andReturn().getResponse().getContentAsByteArray());assertEquals(detail,node(lookup(List.of(201L)),200).at("/records/0/product"));}
    @Test void statusChangeIsReadFreshWithoutDetailCache()throws Exception {assertFalse(node(lookup(List.of(201L)),200).at("/records/0/product").isNull());jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=201");assertTrue(node(lookup(List.of(201L)),200).at("/records/0/product").isNull());}
    @Test void batchReadDoesNotWriteAnyCoreBusinessRows(){var before=stable();service.lookup(new ProductLookupModels.Request(List.of(201L,203L,999L)));assertEquals(before,stable());}
    @Test void repositoryUsesOneParameterizedQueryForBatch(){var spy=org.mockito.Mockito.spy(jdbc);var local=new ProductRepository(spy);assertEquals(2,local.findPublicByIds(List.of(202L,201L)).size());org.mockito.Mockito.verify(spy,org.mockito.Mockito.times(1)).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));}
    @Test void directInvalidRequestsFailBeforeReadingRepository(){var repository=org.mockito.Mockito.mock(ProductRepository.class);var local=new ProductLookupService(repository);for(var request:Arrays.asList(null,new ProductLookupModels.Request(null),new ProductLookupModels.Request(List.of()),new ProductLookupModels.Request(Arrays.asList(201L,null)),new ProductLookupModels.Request(List.of(0L)),new ProductLookupModels.Request(List.of(201L,201L))))assertThrows(InvalidProductException.class,()->local.lookup(request));org.mockito.Mockito.verifyNoInteractions(repository);}
    @Test void databaseFailureIsWhole500NotUnavailability()throws Exception {jdbc.execute("RENAME TABLE products TO product_lookup_private_failure");try{var n=node(lookup(List.of(201L,999L)),500);assertEquals("SERVER_ERROR",n.path("code").asString());assertFalse(n.has("records"));}finally{jdbc.execute("RENAME TABLE product_lookup_private_failure TO products");}}
    @Test void corsAllowsLocalPostAndRejectsForeignOrigin()throws Exception {var local=mvc.perform(options("/api/products/lookup").header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type")).andReturn().getResponse();assertEquals(200,local.getStatus());assertEquals("http://localhost:5173",local.getHeader("Access-Control-Allow-Origin"));assertEquals(403,mvc.perform(post("/api/products/lookup").header("Origin","https://foreign.example").contentType("application/json").content("{\"productIds\":[201]}" )).andReturn().getResponse().getStatus());}
}
