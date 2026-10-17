package com.campus.market.publication;

import com.campus.market.product.*;
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

@SpringBootTest(properties={"spring.datasource.url=${LESSON28_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON28_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*")
class PublicationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationService service;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper json=JsonMapper.builder().build();
    String token,otherToken;
    long owner,other;
    JsonNode check(MockHttpServletRequestBuilder request,int status)throws Exception {
        var r=mvc.perform(request).andReturn().getResponse();
        assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));
        return json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    @BeforeEach void fixtures()throws Exception {
        assertTrue(System.getenv("LESSON28_DB_URL").contains("/campus_lesson28_verify?"));
        for(String table:List.of("product_publications","product_comments","product_favorites","products","product_images","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        for(String name:List.of("publish_01","publish_02"))check(post("/api/users/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname",name))),201);
        owner=jdbc.queryForObject("SELECT id FROM users WHERE username='publish_01'",Long.class);
        other=jdbc.queryForObject("SELECT id FROM users WHERE username='publish_02'",Long.class);
        token=login("publish_01");otherToken=login("publish_02");
    }
    @AfterEach void clearReceipts(){jdbc.update("DELETE FROM product_publications");}
    String login(String name)throws Exception{return check(post("/api/users/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","Campus2026!"))),200).get("accessToken").asText();}
    String key(){return UUID.randomUUID().toString();}
    Map<String,Object> product(){return Map.of("title","幂等发布台灯","description","正常使用","priceInCents",1250,"coverImageId","","categoryCode","DAILY");}
    MockHttpServletRequestBuilder submit(String token,String key,Object product){
        var body=new HashMap<String,Object>();body.put("requestId",key);body.put("product",product);
        return post("/api/product-publications").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body));
    }
    long products(){return jdbc.queryForObject("SELECT COUNT(*) FROM products",Long.class);}
    long receipts(){return jdbc.queryForObject("SELECT COUNT(*) FROM product_publications",Long.class);}
    @Test void first201AndReplay200ReferToSingleProduct()throws Exception {
        String key=key();var a=check(submit(token,key,product()),201);var b=check(submit(token,key,product()),200);
        assertEquals(a.get("productId"),b.get("productId"));assertEquals(key,b.get("requestId").asText());assertFalse(a.get("replayed").asBoolean());assertTrue(b.get("replayed").asBoolean());assertEquals(1,products());assertEquals(1,receipts());
    }
    @Test void changedPayloadWithSameKeyConflictsAndKeepsOriginal()throws Exception {
        String key=key();check(submit(token,key,product()),201);var changed=new HashMap<>(product());changed.put("priceInCents",1300);
        assertEquals("PUBLICATION_CONFLICT",check(submit(token,key,changed),409).get("code").asText());assertEquals(1250,jdbc.queryForObject("SELECT price_in_cents FROM products",Long.class));assertEquals(1,products());
    }
    @Test void whitespaceAndDefaultFieldsNormalizeBeforeComparison()throws Exception {
        String key=key();check(submit(token,key,Map.of("title","  幂等发布台灯  ","priceInCents",1250)),201);
        var b=check(submit(token,key,Map.of("title","幂等发布台灯","description","","priceInCents",1250,"coverImageId"," ","categoryCode","UNCLASSIFIED")),200);assertTrue(b.get("replayed").asBoolean());assertEquals(1,products());
    }
    @Test void sixConcurrentRetriesProduceOneProduct()throws Exception {
        String key=key();var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);var ready=new CountDownLatch(6);
        try {
            var futures=new ArrayList<Future<PublicationResponse>>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return service.create(new CreatePublicationRequest(key,new CreateProductRequest("并发发布台灯","",1250L,"","DAILY")),"Bearer "+token);}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();var ids=new HashSet<Long>();int created=0;
            for(var future:futures){var receipt=future.get(10,TimeUnit.SECONDS);ids.add(receipt.productId());if(!receipt.replayed())created++;}
            assertEquals(1,ids.size());assertEquals(1,created);assertEquals(1,products());assertEquals(1,receipts());
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void differentAccountsCanUseSameKey()throws Exception {
        String key=key();var a=check(submit(token,key,product()),201);var b=check(submit(otherToken,key,product()),201);
        assertNotEquals(a.get("productId"),b.get("productId"));assertEquals(2,products());assertEquals(2,receipts());
    }
    @Test void differentKeysAllowIntentionalSameContent()throws Exception {
        var a=check(submit(token,key(),product()),201);var b=check(submit(token,key(),product()),201);assertNotEquals(a.get("productId"),b.get("productId"));assertEquals(2,products());
    }
    @Test void missingAndInvalidSessionsCannotCreateOrConfirm()throws Exception {
        check(post("/api/product-publications").contentType("application/json").content(json.writeValueAsString(Map.of("requestId",key(),"product",product()))),401);
        check(submit("invalid",key(),product()),401);String key=key();check(submit(token,key,product()),201);
        jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(NOW(),INTERVAL 1 HOUR)");check(submit(token,key,product()),401);assertEquals(1,products());
    }
    @Test void malformedKeysAndNullProductDoNotWrite()throws Exception {
        for(String k:List.of("","bad","AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"))check(submit(token,k,product()),400);
        check(submit(token,null,product()),400);check(submit(token,key(),null),400);assertEquals(0,products());assertEquals(0,receipts());
    }
    @Test void invalidProductFieldsDoNotWrite()throws Exception {
        for(var entry:Map.of("title","a","description","字".repeat(501),"priceInCents",0,"categoryCode","UNKNOWN").entrySet()){
            var invalid=new HashMap<>(product());invalid.put(entry.getKey(),entry.getValue());check(submit(token,key(),invalid),400);
        }assertEquals(0,products());assertEquals(0,receipts());
    }
    @Test void callerCannotSpoofPublisher()throws Exception {
        var p=new HashMap<>(product());p.put("sellerId",other);p.put("seller",Map.of("id",other));check(submit(token,key(),p),201);
        assertEquals(owner,jdbc.queryForObject("SELECT seller_id FROM products",Long.class));assertEquals(owner,jdbc.queryForObject("SELECT seller_id FROM product_publications",Long.class));
    }
    @Test void originalReceiptSurvivesEditsAndOffShelfWithoutResurrection()throws Exception {
        String key=key();long id=check(submit(token,key,product()),201).get("productId").asLong();
        jdbc.update("UPDATE products SET title='编辑后的台灯',status='OFF_SHELF',version=version+1 WHERE id=?",id);
        assertEquals(id,check(submit(token,key,product()),200).get("productId").asLong());assertEquals("OFF_SHELF",jdbc.queryForObject("SELECT status FROM products WHERE id=?",String.class,id));assertEquals("编辑后的台灯",jdbc.queryForObject("SELECT title FROM products WHERE id=?",String.class,id));assertEquals(1,products());
    }
    @Test void receiptInsertFailureRollsBackNewProduct()throws Exception {
        // 临时数据库触发器模拟回执写入失败，验证外层事务的原子性。
        jdbc.execute("CREATE TRIGGER fail_publication BEFORE INSERT ON product_publications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture receipt failure'");
        try {check(submit(token,key(),product()),500);assertEquals(0,products());assertEquals(0,receipts());}
        finally {jdbc.execute("DROP TRIGGER fail_publication");}
    }
    @Test void transactionRollbackRemovesBothProductAndReceipt() {
        new TransactionTemplate(transactions).execute(status->{service.create(new CreatePublicationRequest(key(),new CreateProductRequest("事务回滚台灯","",1250L,"","DAILY")),"Bearer "+token);status.setRollbackOnly();return null;});assertEquals(0,products());assertEquals(0,receipts());
    }
    @Test void uniqueConstraintAndForeignKeyProtectReceipt()throws Exception {
        String key=key();long id=check(submit(token,key,product()),201).get("productId").asLong();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_publications SELECT * FROM product_publications"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM products WHERE id=?",id));assertEquals(1,receipts());
    }
    @Test void receiptHasNoPrivateDataAndIsNotCached()throws Exception {
        var response=mvc.perform(submit(token,key(),product())).andReturn().getResponse();assertEquals(201,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(3,json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).size());assertFalse(response.containsHeader("Set-Cookie"));
        assertEquals(403,mvc.perform(submit(token,key(),product()).header("Origin","https://foreign.example")).andReturn().getResponse().getStatus());
    }
    @Test void legacyEndpointStillReturnsFullProduct()throws Exception {
        var p=check(post("/api/products").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(product())),201);
        assertEquals(owner,p.get("seller").get("id").asLong());assertEquals("幂等发布台灯",p.get("title").asText());assertEquals(1,products());assertEquals(0,receipts());
    }
}
