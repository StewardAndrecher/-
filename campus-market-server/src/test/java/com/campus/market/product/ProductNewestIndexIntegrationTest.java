package com.campus.market.product;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Uses repository SQL verbatim; never forces an index or connects to the business schema. */
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class ProductNewestIndexIntegrationTest {
    String schema,url; Connection control; RecordingJdbc jdbc; ProductRepository repository;
    @BeforeEach void privateLegacySchemaWithTwelveThousandProducts() throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@datadir")){assertTrue(r.next());assertTrue(r.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson87_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var s=control.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?")+"&rewriteBatchedStatements=true";
        assertEquals(25,Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("74").cleanDisabled(true).load().migrate().migrationsExecuted);
        // Keep the historical index migration as the subject, with today's response columns available.
        // This is only this test's owned private schema; V87 is still absent until upgrade().
        try(var c=DriverManager.getConnection(url,"root","")) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/migration/V128__product_details_and_profiles.sql"));
        }
        jdbc=new RecordingJdbc(url);repository=new ProductRepository(jdbc);
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname) VALUES(1,'plan_seller','unused','性能卖家'),(2,'plan_fan','unused','性能收藏者')");
        String[] categories={"UNCLASSIFIED","BOOK","DIGITAL","DAILY","SPORT","OTHER"};
        try(var c=DriverManager.getConnection(url,"root","");var p=c.prepareStatement("INSERT INTO products(id,title,price_in_cents,seller_id,status,category_code,created_at) VALUES(?,?,?,1,?,?,FROM_UNIXTIME(?))")) {
            c.setAutoCommit(false);
            for(int id=1;id<=12000;id++) {
                p.setInt(1,id);p.setString(2,id==1?"百分%_!教材":"性能教材"+id);p.setInt(3,100+(id*17)%100000);
                p.setString(4,id%5==0?"OFF_SHELF":"ON_SALE");p.setString(5,categories[id%6]);p.setLong(6,1700000000L+id%1000);p.addBatch();
                if(id%500==0)p.executeBatch();
            }
            c.commit();
        }
        jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(2,11999)");
        analyze();
    }
    @AfterEach void removeOnlyOwnedSchema() throws Exception {
        if(control!=null)try {if(schema!=null&&schema.matches("campus_lesson87_case_[0-9a-f]{12}"))try(var s=control.createStatement()){s.execute("DROP DATABASE "+schema);}}finally{control.close();}
    }
    Flyway latest(){return Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("87").baselineOnMigrate(false).cleanDisabled(true).load();}
    void upgrade(){assertEquals(1,latest().migrate().migrationsExecuted);analyze();}
    void analyze(){jdbc.queryForList("ANALYZE TABLE products");}
    List<Long> ids(List<ProductResponse> rows){return rows.stream().map(ProductResponse::id).toList();}
    String plan(Query query){assertFalse(query.sql().contains("FORCE INDEX"));return jdbc.queryForObject("EXPLAIN FORMAT=TREE "+query.sql(),String.class,query.arguments());}
    String indexColumns(String index){return jdbc.queryForObject("SELECT GROUP_CONCAT(CONCAT(column_name,':',collation) ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=? AND table_name='products' AND index_name=?",String.class,schema,index);}

    @Test void migrationAddsBothIndexesWithMatchingDescendingOrder() {
        upgrade();assertEquals("status:A,created_at:D,id:D",indexColumns("idx_products_status_created_id"));
        assertEquals("category_code:A,status:A,created_at:D,id:D",indexColumns("idx_products_category_status_created_id"));
    }
    @Test void upgradePreservesAllProductRowsAndAutoIncrement() {
        var before=jdbc.queryForList("SELECT * FROM products ORDER BY id");Long next=jdbc.queryForObject("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=? AND table_name='products'",Long.class,schema);
        upgrade();assertEquals(before,jdbc.queryForList("SELECT * FROM products ORDER BY id"));assertEquals(next,jdbc.queryForObject("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=? AND table_name='products'",Long.class,schema));
    }
    @Test void repeatMigrationDoesNotDuplicateIndexesOrHistory() {
        upgrade();assertEquals(0,latest().migrate().migrationsExecuted);assertEquals(26,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='87' AND success=1",Integer.class));
    }
    @Test void homepageRepositoryQueryUsesNewIndexWithoutHints() {
        var before=repository.findPage("","",ProductSort.NEWEST,20,0);String oldPlan=plan(jdbc.last);
        upgrade();var after=repository.findPage("","",ProductSort.NEWEST,20,0);String newPlan=plan(jdbc.last);
        assertEquals(before,after);assertFalse(oldPlan.contains("idx_products_status_created_id"));assertTrue(newPlan.contains("idx_products_status_created_id"),newPlan);assertTrue(newPlan.contains("Materialize"),newPlan);
    }
    @Test void categoryRepositoryQueryUsesItsCompositeIndex() {
        var before=repository.findPage("","BOOK",ProductSort.NEWEST,20,0);upgrade();var after=repository.findPage("","BOOK",ProductSort.NEWEST,20,0);String plan=plan(jdbc.last);
        assertEquals(before,after);assertTrue(plan.contains("idx_products_category_status_created_id"),plan);
    }
    @Test void paginationHasStableTiesAndNoMissingOrDuplicatedProducts() {
        upgrade();var first=repository.findPage("","",ProductSort.NEWEST,20,0);var second=repository.findPage("","",ProductSort.NEWEST,20,20);
        var actual=new ArrayList<Long>(ids(first));actual.addAll(ids(second));assertEquals(40,new HashSet<>(actual).size());
        assertEquals(jdbc.queryForList("SELECT id FROM products WHERE status='ON_SALE' ORDER BY created_at DESC,id DESC LIMIT 40",Long.class),actual);
        assertTrue(first.stream().allMatch(p->p.status()==ProductStatus.ON_SALE));assertEquals(1,first.get(0).favoriteCount());
    }
    @Test void keywordWildcardCharactersKeepTheirLiteralMeaning() {
        upgrade();var rows=repository.findPage("%_!","BOOK",ProductSort.NEWEST,20,0);assertEquals(List.of(1L),ids(rows));assertEquals(1,repository.countByKeyword("%_!","BOOK"));
    }
    @Test void allPublicSortsReturnTheSamePageBeforeAndAfterIndexes() {
        Map<ProductSort,List<ProductResponse>> before=new EnumMap<>(ProductSort.class);for(var sort:ProductSort.values())before.put(sort,repository.findPage("","BOOK",sort,20,0));
        upgrade();for(var sort:ProductSort.values())assertEquals(before.get(sort),repository.findPage("","BOOK",sort,20,0),sort.name());
    }
    @Test void leftJoinKeepsLegacyProductsWithoutSeller() {
        jdbc.update("UPDATE products SET seller_id=NULL WHERE id=11999");upgrade();var first=repository.findPage("","",ProductSort.NEWEST,20,0).get(0);assertEquals(11999,first.id());assertNull(first.seller());
    }
    @Test void indexDdlRetainsExistingPriceAndForeignKeyConstraints() {
        upgrade();var price=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO products(title,price_in_cents) VALUES('invalid',0)"));assertNotNull(price.getCause());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO product_favorites(user_id,product_id) VALUES(999999,999999)"));
    }
    record Query(String sql,Object[] arguments) {}
    static class RecordingJdbc extends JdbcTemplate {
        Query last;
        RecordingJdbc(String url){super(new DriverManagerDataSource(url,"root",""));}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...arguments){last=new Query(sql,arguments.clone());return super.query(sql,mapper,arguments);}
    }
}
