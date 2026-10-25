package com.campus.market.migration;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Every test creates only a guarded schema inside the disposable test MySQL instance. */
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class FlywayMigrationIntegrationTest {
    @TempDir Path temporary;
    String schema,url;
    Connection control;

    @BeforeEach void privateSchema() throws Exception {
        String base=System.getenv("LESSON85_DB_URL");
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*"));
        control=DriverManager.getConnection(base,"root","");
        try(var statement=control.createStatement();var row=statement.executeQuery("SELECT @@datadir")) {
            assertTrue(row.next());assertTrue(row.getString(1).contains("campus-lesson24-"),"Refuse a non-disposable MySQL instance");
        }
        schema="campus_lesson85_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var statement=control.createStatement()){statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");
    }
    @AfterEach void removeOnlyOwnedSchema() throws Exception {
        if(control!=null)try {
            if(schema!=null&&schema.matches("campus_lesson85_case_[0-9a-f]{12}"))try(var statement=control.createStatement()){statement.execute("DROP DATABASE "+schema);}
        } finally {control.close();}
    }
    Flyway flyway(){return configured("classpath:db/migration");}
    Flyway configured(String location){return Flyway.configure().dataSource(url,"root","").locations(location).baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).load();}
    void execute(String sql) throws Exception {try(var c=DriverManager.getConnection(url,"root","");var s=c.createStatement()){s.execute(sql);}}
    long number(String sql) throws Exception {try(var c=DriverManager.getConnection(url,"root","");var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    long tableCount() throws Exception {return number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+schema+"'");}
    Path copyMigrations() throws Exception {
        Path directory=temporary.resolve("migrations");Files.createDirectories(directory);
        for(var migration:flyway().info().all()) {
            String name=migration.getScript();
            try(var input=new ClassPathResource("db/migration/"+name).getInputStream()){Files.copy(input,directory.resolve(name));}
        }
        return directory;
    }

    @Test void freshSchemaRunsAllVersionsAndRecordsChecksums() throws Exception {
        assertEquals(42,flyway().migrate().migrationsExecuted);
        assertEquals(45,tableCount());assertEquals(42,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND checksum IS NOT NULL"));
        assertEquals("136",flyway().info().current().getVersion().toString());
        assertEquals(0,number("SELECT COUNT(*) FROM users"));assertEquals(0,number("SELECT COUNT(*) FROM products"));
    }
    @Test void secondMigrationDoesNotRepeatDdlOrHistory() throws Exception {
        flyway().migrate();assertEquals(0,flyway().migrate().migrationsExecuted);assertEquals(42,number("SELECT COUNT(*) FROM flyway_schema_history"));assertEquals(45,tableCount());
    }
    @Test void laterVersionsPreserveAnExistingProduct() throws Exception {
        var first=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("31").cleanDisabled(true).load();
        assertEquals(11,first.migrate().migrationsExecuted);
        execute("INSERT INTO products(title,description,price_in_cents) VALUES('迁移保留教材','原始描述',3500)");
        assertEquals(31,flyway().migrate().migrationsExecuted);
        assertEquals(1,number("SELECT COUNT(*) FROM products WHERE title='迁移保留教材' AND description='原始描述' AND price_in_cents=3500 AND version=0"));
    }
    @Test void changedAppliedSqlFailsChecksumValidationWithoutDeletingData() throws Exception {
        flyway().migrate();execute("INSERT INTO products(title,description,price_in_cents) VALUES('checksum fixture','keep',100)");
        Path directory=copyMigrations();Files.writeString(directory.resolve("V1__products.sql"),"\nSELECT 1;\n",StandardOpenOption.APPEND);
        assertThrows(FlywayException.class,()->configured("filesystem:"+directory).validate());
        assertEquals(1,number("SELECT COUNT(*) FROM products WHERE title='checksum fixture'"));assertEquals(42,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
    }
    @Test void nonemptyUnmanagedSchemaIsRefusedAndNeverAutoBaselined() throws Exception {
        execute("CREATE TABLE legacy_guard(id INT PRIMARY KEY,note VARCHAR(30))");execute("INSERT INTO legacy_guard VALUES(1,'keep')");
        assertThrows(FlywayException.class,()->flyway().migrate());
        assertEquals(1,tableCount());assertEquals(1,number("SELECT COUNT(*) FROM legacy_guard WHERE note='keep'"));
    }
    @Test void cleanIsDisabledAndExistingRecordsSurvive() throws Exception {
        flyway().migrate();execute("INSERT INTO products(title,price_in_cents) VALUES('clean guard',100)");
        assertThrows(FlywayException.class,()->flyway().clean());assertEquals(1,number("SELECT COUNT(*) FROM products WHERE title='clean guard'"));assertEquals(45,tableCount());
    }
    @Test void mysqlDdlFailureLeavesEvidenceAndBlocksAutomaticRetry() throws Exception {
        flyway().migrate();Path directory=copyMigrations();
        Files.writeString(directory.resolve("V999__intentional_failure.sql"),"CREATE TABLE ddl_failure_guard(id INT PRIMARY KEY);\nINSERT INTO table_that_does_not_exist(id) VALUES(1);\n");
        Flyway failing=configured("filesystem:"+directory);
        assertThrows(FlywayException.class,failing::migrate);
        assertEquals(0,number("SELECT COUNT(*) FROM ddl_failure_guard"));assertEquals(1,number("SELECT COUNT(*) FROM flyway_schema_history WHERE version='999' AND success=0"));
        assertThrows(FlywayException.class,failing::migrate);
    }
    @Test void duplicateVersionsAreRejectedBeforeBusinessTablesAreCreated() throws Exception {
        Path directory=copyMigrations();Files.writeString(directory.resolve("V1__duplicate.sql"),"SELECT 1;\n");
        assertThrows(FlywayException.class,()->configured("filesystem:"+directory).migrate());assertEquals(0,tableCount());
    }
    @Test void competingMigrationsRecordEachVersionOnlyOnce() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first=pool.submit(()->flyway().migrate().migrationsExecuted);Future<Integer> second=pool.submit(()->flyway().migrate().migrationsExecuted);
            assertEquals(42,first.get(30,TimeUnit.SECONDS)+second.get(30,TimeUnit.SECONDS));assertEquals(42,number("SELECT COUNT(*) FROM flyway_schema_history"));assertEquals(45,tableCount());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void migratedSchemaRetainsPriceAndForeignKeyConstraints() throws Exception {
        flyway().migrate();var price=assertThrows(SQLException.class,()->execute("INSERT INTO products(title,price_in_cents) VALUES('bad',0)"));assertEquals(3819,price.getErrorCode());
        var foreign=assertThrows(SQLException.class,()->execute("INSERT INTO product_favorites(user_id,product_id) VALUES(999,999)"));assertEquals(1452,foreign.getErrorCode());
    }

    @Test void reviewMigrationAddsOnlyAnEmptyTableToVersion92()throws Exception {
        var previous=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("92").cleanDisabled(true).load();
        assertEquals(30,previous.migrate().migrationsExecuted);
        execute("INSERT INTO products(title,description,price_in_cents) VALUES('before review migration','keep',100)");
        assertEquals(1,Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("101").cleanDisabled(true).load().migrate().migrationsExecuted);assertEquals(23,tableCount());assertEquals(0,number("SELECT COUNT(*) FROM order_reviews"));
        assertEquals(1,number("SELECT COUNT(*) FROM products WHERE title='before review migration' AND description='keep'"));Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("101").cleanDisabled(true).load().validate();
    }
    @Test void issueMigrationAddsOnlyAnEmptyTableToVersion101()throws Exception {
        var previous=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("101").cleanDisabled(true).load();
        assertEquals(31,previous.migrate().migrationsExecuted);
        execute("INSERT INTO products(title,description,price_in_cents) VALUES('before issue migration','keep',100)");
        assertEquals(1,Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("104").cleanDisabled(true).load().migrate().migrationsExecuted);assertEquals(24,tableCount());assertEquals(0,number("SELECT COUNT(*) FROM order_issues"));
        assertEquals(1,number("SELECT COUNT(*) FROM products WHERE title='before issue migration' AND description='keep'"));Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("104").cleanDisabled(true).load().validate();
    }

    @Test void resolutionMigrationPreservesOriginalIssueRecords()throws Exception {
        var previous=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("104").cleanDisabled(true).load();
        assertEquals(32,previous.migrate().migrationsExecuted);
        execute("INSERT INTO users(id,username,password_hash,nickname) VALUES(1,'migration_buyer','private','甲'),(2,'migration_seller','private','乙')");
        execute("INSERT INTO products(id,title,description,price_in_cents,seller_id,status) VALUES(1,'before resolution migration','keep',100,2,'SOLD')");
        execute("INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,paid_at,payment_kind,delivered_at,completed_at) VALUES(1,1,1,2,'11111111-1111-4111-8111-111111111111','migration fixture',100,0,'甲','乙','COMPLETED',CURRENT_TIMESTAMP-INTERVAL 20 MINUTE,CURRENT_TIMESTAMP-INTERVAL 19 MINUTE,'SIMULATED',CURRENT_TIMESTAMP-INTERVAL 18 MINUTE,CURRENT_TIMESTAMP-INTERVAL 17 MINUTE)");
        execute("INSERT INTO order_issues(order_id,applicant_id,reason,description) VALUES(1,1,'OTHER','immutable original issue')");
        assertEquals(1,Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("105").cleanDisabled(true).load().migrate().migrationsExecuted);assertEquals(25,tableCount());assertEquals(0,number("SELECT COUNT(*) FROM order_issue_resolutions"));assertEquals(1,number("SELECT COUNT(*) FROM order_issues WHERE description='immutable original issue'"));
        assertEquals(1,number("SELECT COUNT(*) FROM products WHERE title='before resolution migration' AND description='keep'"));Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("105").cleanDisabled(true).load().validate();
    }

 @Test void productAndProfileUpgradePreservesCoverAndBusinessValues()throws Exception {
  Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("105").cleanDisabled(true).load().migrate();
  execute("INSERT INTO users(id,username,password_hash,nickname) VALUES(8,'upgrade_owner','private','保持昵称')");
  execute("INSERT INTO product_images(id,owner_id,width,height) VALUES('11111111-1111-4111-8111-111111111111',8,40,30)");
  execute("INSERT INTO products(id,title,description,price_in_cents,seller_id,cover_image_id,version) VALUES(8,'已有封面商品','保留描述',1234,8,'11111111-1111-4111-8111-111111111111',4)");
  assertEquals(9,flyway().migrate().migrationsExecuted);
  assertEquals(1,number("SELECT COUNT(*) FROM products WHERE id=8 AND title='已有封面商品' AND price_in_cents=1234 AND version=4 AND campus_code='UNSPECIFIED' AND condition_code='UNSPECIFIED'"));
  assertEquals(1,number("SELECT COUNT(*) FROM product_gallery WHERE product_id=8 AND position=0 AND image_id='11111111-1111-4111-8111-111111111111'"));
  assertEquals(1,number("SELECT COUNT(*) FROM users WHERE id=8 AND nickname='保持昵称' AND avatar_image_id IS NULL AND bio='' AND profile_version=0"));
  assertEquals(0,number("SELECT COUNT(*) FROM order_refunds"));assertEquals(0,flyway().migrate().migrationsExecuted);
 }
}
