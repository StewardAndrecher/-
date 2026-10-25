package com.campus.market.product;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class ProductFeedIntegrationTest {
    String schema,url;Connection control;RecordingJdbc jdbc;ProductRepository repository;ProductService service;MockMvc mvc;
    com.campus.market.auth.SessionService sessions; final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void privateSchema() throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson88_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");
        assertEquals(26,Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("87").cleanDisabled(true).load().migrate().migrationsExecuted);
        // Keep the historical index migration as the subject, with today's response columns available.
        // This is only this test's owned private schema; V87 is still absent until upgrade().
        try(var c=DriverManager.getConnection(url,"root","")) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/migration/V128__product_details_and_profiles.sql"));
        }
        jdbc=new RecordingJdbc(url);repository=new ProductRepository(jdbc);sessions=mock(com.campus.market.auth.SessionService.class);
        service=new ProductService(repository,sessions,mock(com.campus.market.media.ImageService.class),mock(ProductDetailCache.class),mock(com.campus.market.order.OrderRepository.class));
        mvc=MockMvcBuilders.standaloneSetup(new ProductController(service)).setControllerAdvice(new com.campus.market.common.GlobalExceptionHandler()).build();
        for(int id=1;id<=45;id++)jdbc.update("INSERT INTO products(id,title,description,price_in_cents,category_code,created_at) VALUES(?,?,'游标夹具',3500,?,FROM_UNIXTIME(?))",id,id==1?"百分%_!教材":"游标教材"+id,id%2==0?"BOOK":"DAILY",1700000000L+id%3);
        jdbc.update("INSERT INTO products(id,title,price_in_cents,status,created_at) VALUES(46,'下架',100,'OFF_SHELF',FROM_UNIXTIME(1700000100)),(47,'售出',100,'SOLD',FROM_UNIXTIME(1700000100))");
    }
    @AfterEach void cleanup() throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson88_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}}
    JsonNode feed(int size,String cursor,String keyword,String category)throws Exception {
        var r=mvc.perform(get("/api/products/feed").param("size",""+size).param("cursor",cursor).param("keyword",keyword).param("category",category)).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());return json.readTree(r.getContentAsByteArray());
    }
    List<Long> ids(JsonNode data){var result=new ArrayList<Long>();data.path("records").forEach(row->result.add(row.path("id").asLong()));return result;}
    String next(JsonNode data){return data.path("nextCursor").isNull()?"":data.path("nextCursor").asString();}
    List<Long> expected(){return jdbc.queryForList("SELECT id FROM products WHERE status='ON_SALE' ORDER BY created_at DESC,id DESC",Long.class);}
    @Test void firstPageIsPublicBoundedAndNotCached()throws Exception {
        var r=mvc.perform(get("/api/products/feed").param("size","20")).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));
        var data=json.readTree(r.getContentAsByteArray());assertEquals(20,data.path("records").size());assertTrue(data.path("hasMore").asBoolean());assertFalse(next(data).isEmpty());assertFalse(data.has("total"));assertFalse(data.has("totalPages"));
        verifyNoInteractions(sessions);assertFalse(jdbc.lastSql.contains("OFFSET"));assertFalse(jdbc.lastSql.contains("COUNT(*) FROM products"));
    }
    @Test void traversesTiedTimesWithoutDuplicatesOrSkippedLookahead()throws Exception {
        var all=new ArrayList<Long>();String cursor="";for(int count=0;count<10;count++){var data=feed(7,cursor,"","");all.addAll(ids(data));cursor=next(data);if(!data.path("hasMore").asBoolean())break;}
        assertEquals(expected(),all);assertEquals(45,new HashSet<>(all).size());
    }
    @Test void exactLastPageReturnsNullCursor()throws Exception {var a=feed(15,"","","");var b=feed(15,next(a),"","");var c=feed(15,next(b),"","");assertEquals(15,c.path("records").size());assertFalse(c.path("hasMore").asBoolean());assertTrue(c.path("nextCursor").isNull());}
    @Test void emptyFilterResultHasNoNextCursor()throws Exception {var data=feed(10,"","不存在的物品","");assertTrue(data.path("records").isEmpty());assertFalse(data.path("hasMore").asBoolean());assertTrue(data.path("nextCursor").isNull());}
    @Test void cursorCannotBeReusedForDifferentFilters()throws Exception {String token=next(feed(5,"","游标","BOOK"));for(var q:List.of(get("/api/products/feed").param("cursor",token).param("keyword","游标").param("category","DAILY"),get("/api/products/feed").param("cursor",token).param("keyword","教材").param("category","BOOK")))assertEquals(400,mvc.perform(q).andReturn().getResponse().getStatus());}
    @Test void malformedCursorReturnsBusiness400InsteadOf500()throws Exception {var r=mvc.perform(get("/api/products/feed").param("cursor","invalid!")).andReturn().getResponse();assertEquals(400,r.getStatus());assertEquals("INVALID_PRODUCT",json.readTree(r.getContentAsByteArray()).path("code").asString());}
    @Test void rejectsInvalidSizeFilterSortAndForeignOrigin()throws Exception {
        for(var q:List.of(get("/api/products/feed").param("size","0"),get("/api/products/feed").param("size","21"),get("/api/products/feed").param("size","abc"),get("/api/products/feed").param("keyword","中".repeat(51)),get("/api/products/feed").param("category","UNKNOWN"),get("/api/products/feed").param("sort","PRICE_ASC")))assertEquals(400,mvc.perform(q).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/products/feed").header("Origin","https://foreign.example")).andReturn().getResponse().getStatus());
    }
    @Test void preservesLiteralKeywordAndNormalizesWhitespace()throws Exception {assertEquals(List.of(1L),ids(feed(10,""," %_! ","")));assertEquals(22,service.listProducts(1,20,"","BOOK","NEWEST").total());}
    @Test void insertAboveAnchorDoesNotRepeatPreviouslySeenProducts()throws Exception {
        var first=feed(5,"","","");var initial=expected();jdbc.update("INSERT INTO products(id,title,price_in_cents,created_at) VALUES(100,'后来发布',100,FROM_UNIXTIME(1700001000))");
        var second=feed(5,next(first),"","");assertEquals(initial.subList(5,10),ids(second));assertTrue(Collections.disjoint(ids(first),ids(second)));assertEquals(100L,ids(feed(5,"","","")).get(0));
        assertTrue(ids(first).contains(service.listProducts(2,5,"","","NEWEST").records().get(0).id()),"offset shifts after insertion, while cursor does not");
    }
    @Test void deletingAnchorDoesNotRequireItsContinuedExistence()throws Exception {var first=feed(5,"","","");var initial=expected();jdbc.update("DELETE FROM products WHERE id=?",ids(first).get(4));assertEquals(initial.subList(5,10),ids(feed(5,next(first),"","")));}
    @Test void changingVisibilityDoesNotExposeOffShelfOrSoldProducts()throws Exception {var first=feed(5,"","","");var anchor=ProductFeedCursor.decode(next(first),"","");var candidate=repository.findFeed("","",anchor,1).get(0).product().id();jdbc.update("UPDATE products SET status='OFF_SHELF' WHERE id=?",candidate);assertFalse(ids(feed(5,next(first),"","")).contains(candidate));}
    @Test void legacyProductsWithoutSellerStillAppear()throws Exception {var data=feed(10,"","","");assertEquals(10,data.path("records").size());for(var row:data.path("records")){assertTrue(row.path("seller").isNull());assertEquals("ON_SALE",row.path("status").asString());}}
    @Test void pageSizeCanChangeWithoutLosingBoundaryRows()throws Exception {var a=feed(3,"","","");var b=feed(11,next(a),"","");assertEquals(expected().subList(3,14),ids(b));}
    @Test void epochCursorSurvivesDifferentConnectionTimezones() {
        var utc=new ProductRepository(new JdbcTemplate(new DriverManagerDataSource(url+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root","")));
        var first=utc.findFeed("","",null,5);var last=first.get(4);var continued=repository.findFeed("","",new ProductFeedCursor(last.epochSecond(),last.product().id()),5);
        assertEquals(expected().subList(5,10),continued.stream().map(row->row.product().id()).toList());
    }
    @Test void deepCursorUsesRangeIndexWithoutOffsetOrForcedIndex() {
        for(int start=100;start<12100;start+=1000){var rows=new ArrayList<String>();for(int id=start;id<start+1000;id++)rows.add("("+id+",'深页教材',100,FROM_UNIXTIME("+(1700000100+id%1000)+"))");jdbc.update("INSERT INTO products(id,title,price_in_cents,created_at) VALUES"+String.join(",",rows));}
        jdbc.queryForList("ANALYZE TABLE products");var anchor=jdbc.queryForMap("SELECT id,UNIX_TIMESTAMP(created_at) AS epoch FROM products WHERE status='ON_SALE' ORDER BY created_at DESC,id DESC LIMIT 1 OFFSET 9999");
        var rows=repository.findFeed("","",new ProductFeedCursor(((Number)anchor.get("epoch")).longValue(),((Number)anchor.get("id")).longValue()),21);String sql=jdbc.lastSql;Object[] args=jdbc.lastArguments;
        assertEquals(21,rows.size());assertFalse(sql.contains("OFFSET"));assertFalse(sql.contains("FORCE INDEX"));String plan=jdbc.queryForObject("EXPLAIN FORMAT=TREE "+sql,String.class,args);assertTrue(plan.contains("idx_products_status_created_id"),plan);assertTrue(plan.toLowerCase(Locale.ROOT).contains("range scan"),plan);
        String seek=jdbc.queryForObject("EXPLAIN ANALYZE "+sql,String.class,args);
        var offsetRows=repository.findPage("","",ProductSort.NEWEST,21,10000);String offsetSql=jdbc.lastSql;Object[] offsetArgs=jdbc.lastArguments;
        assertEquals(offsetRows.stream().map(ProductResponse::id).toList(),rows.stream().map(row->row.product().id()).toList());
        String offset=jdbc.queryForObject("EXPLAIN ANALYZE "+offsetSql,String.class,offsetArgs);
        System.out.println("LESSON88_SEEK_ANALYZE_BEGIN\n"+seek+"\nLESSON88_SEEK_ANALYZE_END");
        System.out.println("LESSON88_OFFSET_ANALYZE_BEGIN\n"+offset+"\nLESSON88_OFFSET_ANALYZE_END");
    }
    static class RecordingJdbc extends JdbcTemplate {String lastSql;Object[] lastArguments;RecordingJdbc(String url){super(new DriverManagerDataSource(url,"root",""));}@Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...arguments){lastSql=sql;lastArguments=arguments.clone();return super.query(sql,mapper,arguments);}}
}
