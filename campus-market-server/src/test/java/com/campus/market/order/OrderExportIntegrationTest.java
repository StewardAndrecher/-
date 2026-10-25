package com.campus.market.order;
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
class OrderExportIntegrationTest {
    static Connection control;static String schema;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson112_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
    }
    @AfterAll static void removeSchema()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson112_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;
    final JsonMapper json=JsonMapper.builder().build();String admin,user,seller;long serial;
    @BeforeEach void fixtures(){
        for(String table:List.of("order_reviews","order_events","order_timeout_outbox","market_orders","products","user_role_audit","user_role_grants","login_sessions","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(101,'trade_admin','private','管理员'),(102,'trade_seller','private','卖家'),(103,'trade_buyer','private','买家')");
        serial=1000;
        admin="Bearer "+sessions.issueToken(new UserResponse(101,"trade_admin","管理员")).accessToken();
        user="Bearer "+sessions.issueToken(new UserResponse(103,"trade_buyer","买家")).accessToken();
        seller="Bearer "+sessions.issueToken(new UserResponse(102,"trade_seller","卖家")).accessToken();
    }
    long order(String status,long price,int age){
        long id=++serial;String created="CURRENT_TIMESTAMP(6)-INTERVAL "+age+" SECOND";
        jdbc.update("INSERT INTO products(id,title,description,price_in_cents,seller_id) VALUES(?,'PRIVATE_TITLE','PRIVATE_BODY',201,102)",id);
        boolean paid=Set.of("PAID","DELIVERED","COMPLETED").contains(status),delivered=Set.of("DELIVERED","COMPLETED").contains(status);
        String sql="INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,cancelled_at,paid_at,closed_at,payment_kind,delivered_at,completed_at) VALUES(?,?,103,102,?,'PRIVATE_TITLE',?,0,'PRIVATE_BUYER','PRIVATE_SELLER',?,"+created+","+(status.equals("CANCELLED")?"CURRENT_TIMESTAMP(6)":"NULL")+","+(paid?"("+created+")+INTERVAL 1 SECOND":"NULL")+","+(status.equals("CLOSED_TIMEOUT")?"("+created+")+INTERVAL 900 SECOND":"NULL")+","+(paid?"'SIMULATED'":"NULL")+","+(delivered?"("+created+")+INTERVAL 2 SECOND":"NULL")+","+(status.equals("COMPLETED")?"("+created+")+INTERVAL 3 SECOND":"NULL")+")";
        jdbc.update(sql,id,id,UUID.randomUUID().toString(),price,status);return id;
    }

    MockHttpServletResponse export(String auth,String role,String status,String id)throws Exception{
        var r=get("/api/orders/export").param("role",role).param("status",status).param("orderId",id);if(auth!=null)r.header("Authorization",auth);
        return mvc.perform(r).andReturn().getResponse();
    }
    JsonNode node(MockHttpServletResponse r)throws Exception {assertEquals(200,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return json.readTree(r.getContentAsByteArray());}
    JsonNode export()throws Exception{return node(export(user,"BUYER","ALL",""));}
    @Test void anonymousCannotExportOrProbeParameters()throws Exception{assertEquals(401,export(null,"ADMIN","BAD","01").getStatus());}
    @Test void revokedSessionCannotExport()throws Exception{mvc.perform(post("/api/users/logout").header("Authorization",user));assertEquals(401,export(user,"BUYER","ALL","").getStatus());}
    @Test void defaultsGiveEmptyBuyerSnapshot()throws Exception{var n=node(mvc.perform(get("/api/orders/export").header("Authorization",user)).andReturn().getResponse());assertEquals("BUYER",n.path("role").asString());assertEquals("ALL",n.path("status").asString());assertTrue(n.path("orderId").isNull());assertEquals(200,n.path("limit").asInt());assertEquals(0,n.path("total").asInt());}
    @Test void buyerGetsAllOwnRowsBeyondOnePage()throws Exception{for(int i=0;i<13;i++)order("PAID",1200,60);var n=export();assertEquals(13,n.path("total").asInt());assertEquals(13,n.path("records").size());}
    @Test void sellerGetsOnlyCurrentSellerRole()throws Exception{order("PAID",1200,60);assertEquals(1,node(export(seller,"SELLER","ALL","")).path("total").asInt());assertEquals(0,node(export(seller,"BUYER","ALL","")).path("total").asInt());}
    @Test void thirdPartyCannotExportExistingOrder()throws Exception{long id=order("PAID",1200,60);for(String role:List.of("BUYER","SELLER"))assertEquals(0,node(export(admin,role,"ALL",""+id)).path("total").asInt());}
    @Test void nonexistentAndForeignIdsBothReturnEmpty()throws Exception{long id=order("PAID",1200,60);var a=node(export(admin,"BUYER","ALL",""+id));var b=node(export(admin,"BUYER","ALL","999999"));assertEquals(a.path("records"),b.path("records"));assertEquals(a.path("total"),b.path("total"));}
    @Test void clientParametersCannotExpandIdentityOrLimit()throws Exception{order("PAID",1200,60);var n=node(mvc.perform(get("/api/orders/export").param("userId","103").param("limit","9999").param("page","2").header("Authorization",admin)).andReturn().getResponse());assertEquals(0,n.path("total").asInt());assertEquals(200,n.path("limit").asInt());}
    @Test void exactOrderAndStatusFormIntersection()throws Exception{long id=order("COMPLETED",1200,60);order("PAID",1200,60);assertEquals(0,node(export(user,"BUYER","PAID",""+id)).path("total").asInt());var n=node(export(user,"BUYER","COMPLETED",""+id));assertEquals(1,n.path("total").asInt());assertEquals(id,n.path("orderId").asLong());assertEquals(id,n.at("/records/0/id").asLong());}
    @Test void allSixStatesAreExportable()throws Exception{for(String status:List.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT")){order(status,1200,1200);var n=node(export(user,"BUYER",status,""));assertEquals(1,n.path("total").asInt());assertEquals(status,n.at("/records/0/status").asString());}assertEquals(6,export().path("total").asInt());}
    @Test void canonicalIdRulesMatchList()throws Exception{for(String id:List.of("0","01","-1","+1","1e0","1.0"," 1","1 ","１","9007199254740992","12345678901234567"))assertEquals(400,export(user,"BUYER","ALL",id).getStatus());}
    @Test void invalidRoleStatusRejected()throws Exception{assertEquals(400,export(user,"ADMIN","ALL","").getStatus());assertEquals(400,export(user,"BUYER","WRONG","").getStatus());}
    @Test void injectionTextIsRejectedAndDataRetained()throws Exception{order("PAID",1200,60);for(String id:List.of("1 OR 1=1","1;DROP TABLE users","1' UNION SELECT 1"))assertEquals(400,export(user,"BUYER","ALL",id).getStatus());assertEquals(1,export().path("total").asInt());}
    @Test void maximumSafeOrderIdRetainsPrecision()throws Exception{long id=order("PAID",99999999,60);jdbc.update("UPDATE market_orders SET id=9007199254740991 WHERE id=?",id);var n=node(export(user,"BUYER","ALL","9007199254740991"));assertEquals(9007199254740991L,n.at("/records/0/id").asLong());assertEquals(99999999,n.at("/records/0/priceInCentsSnapshot").asLong());}
    @Test void responseContainsOnlyMinimalFields()throws Exception{order("PAID",1200,60);var n=export();assertEquals(Set.of("role","status","orderId","total","limit","records"),new HashSet<>(n.propertyNames()));assertEquals(Set.of("id","productTitleSnapshot","priceInCentsSnapshot","status","createdAt"),new HashSet<>(n.at("/records/0").propertyNames()));for(String secret:List.of("PRIVATE_BODY","PRIVATE_BUYER","PRIVATE_SELLER","buyerId","sellerId","requestId","password","accessToken"))assertFalse(n.toString().contains(secret),secret);}
    @Test void productEditDoesNotChangeOrderSnapshot()throws Exception{long id=order("PAID",1200,60);jdbc.update("UPDATE products SET title='CHANGED_PRODUCT',price_in_cents=9900 WHERE id=?",id);var n=export();assertEquals("PRIVATE_TITLE",n.at("/records/0/productTitleSnapshot").asString());assertEquals(1200,n.at("/records/0/priceInCentsSnapshot").asLong());}
    @Test void specialTitleIsOriginalInJson()throws Exception{long id=order("PAID",1200,60);String title="=1+2,中文台灯";jdbc.update("UPDATE market_orders SET product_title_snapshot=? WHERE id=?",title,id);assertEquals(title,export().at("/records/0/productTitleSnapshot").asString());}
    @Test void createdTimeAndIdDescendingAreDeterministic()throws Exception{long a=order("PENDING_PAYMENT",1200,60),b=order("PENDING_PAYMENT",1200,120);jdbc.update("UPDATE market_orders SET created_at='2026-10-01 12:00:00' WHERE id IN(?,?)",a,b);var n=export();assertEquals(b,n.at("/records/0/id").asLong());assertEquals(a,n.at("/records/1/id").asLong());}
    @Test void exactly200RowsSucceeds()throws Exception{for(int i=0;i<200;i++)order("PAID",1200,60);var n=export();assertEquals(200,n.path("total").asInt());assertEquals(200,n.path("records").size());}
    @Test void row201Returns413NotPartialSnapshot()throws Exception{for(int i=0;i<201;i++)order("PAID",1200,60);var r=export(user,"BUYER","ALL","");assertEquals(413,r.getStatus());var n=json.readTree(r.getContentAsByteArray());assertEquals("ORDER_EXPORT_LIMIT",n.path("code").asString());assertFalse(n.has("records"));assertEquals(201,jdbc.queryForObject("SELECT COUNT(*) FROM market_orders",Long.class));}
    @Test void narrowingOverLimitAccountCanSucceed()throws Exception{for(int i=0;i<201;i++)order("PAID",1200,60);long id=order("COMPLETED",1200,60);assertEquals(1,node(export(user,"BUYER","COMPLETED","")).path("total").asInt());assertEquals(1,node(export(user,"BUYER","ALL",""+id)).path("total").asInt());}
    @Test void readsDoNotCloseExpiredOrdersOrWriteEvents()throws Exception{order("PENDING_PAYMENT",1200,1200);var a=jdbc.queryForList("SELECT * FROM market_orders");var b=jdbc.queryForList("SELECT * FROM products");export();assertEquals(a,jdbc.queryForList("SELECT * FROM market_orders"));assertEquals(b,jdbc.queryForList("SELECT * FROM products"));for(String t:List.of("order_events","notification_outbox","user_notifications","order_timeout_outbox"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));}
    @Test void laterExportReflectsStatusChange()throws Exception{long id=order("PAID",1200,60);assertEquals(1,node(export(user,"BUYER","PAID","")).path("total").asInt());jdbc.update("UPDATE market_orders SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);assertEquals(0,node(export(user,"BUYER","PAID","")).path("total").asInt());assertEquals("DELIVERED",export().at("/records/0/status").asString());}
    @Test void databaseFailureIsNotEmptySuccess()throws Exception{jdbc.execute("RENAME TABLE market_orders TO private_unavailable_orders");try{var r=export(user,"BUYER","ALL","");assertEquals(500,r.getStatus());assertFalse(r.getContentAsString().contains("SELECT"));}finally{jdbc.execute("RENAME TABLE private_unavailable_orders TO market_orders");}}
}
