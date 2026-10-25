package com.campus.market.migration;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class LegacyDatabaseBaselineIntegrationTest {
    @TempDir Path temporary;
    String schema,url; Connection control;
    @BeforeEach void createOnlyPrivateLegacySchema() throws Exception {
        String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
        try(var statement=control.createStatement();var row=statement.executeQuery("SELECT @@datadir")) {assertTrue(row.next());assertTrue(row.getString(1).contains("campus-lesson24-"));}
        schema="campus_lesson86_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var statement=control.createStatement()){statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        url=base.replace("/campus_lesson85_verify?","/"+schema+"?");
        assertEquals(25,flyway().migrate().migrationsExecuted);
        execute("DROP TABLE flyway_schema_history");
        execute("INSERT INTO products(id,title,description,price_in_cents) VALUES(77,'已有教材','保留描述',3500)");
    }
    @AfterEach void removeOnlyOwnedSchema() throws Exception {
        if(control!=null)try {if(schema!=null&&schema.matches("campus_lesson86_case_[0-9a-f]{12}"))try(var statement=control.createStatement()){statement.execute("DROP DATABASE "+schema);}}finally{control.close();}
    }
    Flyway flyway(){return Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("74").baselineOnMigrate(false).cleanDisabled(true).load();}
    Map<String,String> environment(){return Map.of("DB_URL",url,"DB_USERNAME","root","DB_PASSWORD","");}
    LegacyDatabaseBaseline.Report check() throws Exception {return LegacyDatabaseBaseline.execute(new String[]{"check",schema},environment());}
    LegacyDatabaseBaseline.Report baseline(String fingerprint) throws Exception {return LegacyDatabaseBaseline.execute(new String[]{"baseline",schema,fingerprint},environment());}
    void execute(String sql) throws Exception {try(var c=DriverManager.getConnection(url,"root","");var s=c.createStatement()){s.execute(sql);}}
    long count(String sql) throws Exception {try(var c=DriverManager.getConnection(url,"root","");var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    void noHistory() throws Exception {assertEquals(0,count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='flyway_schema_history'"));}
    @Test void checkIsReadOnlyAndIgnoresLiveAutoIncrementCounters() throws Exception {
        var first=check();assertEquals("CHECKED",first.status());assertEquals(14,first.tables());noHistory();
        // DML advances the counter without rewriting CHECK expressions as ALTER TABLE can do in MySQL.
        execute("INSERT INTO products(id,title,price_in_cents) VALUES(999,'counter fixture',100)");
        assertEquals(first.fingerprint(),check().fingerprint());noHistory();
    }
    @Test void explicitBaselineWritesOneHistoryRowAndPreservesExistingProduct() throws Exception {
        var result=baseline(check().fingerprint());assertEquals("BASELINED",result.status());assertEquals(1,result.historyRows());
        assertEquals(1,count("SELECT COUNT(*) FROM flyway_schema_history WHERE type='BASELINE' AND version='74' AND success=1"));
        flyway().validate();assertEquals(0,flyway().migrate().migrationsExecuted);
        assertEquals(1,count("SELECT COUNT(*) FROM products WHERE id=77 AND title='已有教材' AND description='保留描述' AND price_in_cents=3500"));
        assertEquals(1,count("SELECT COUNT(*) FROM flyway_schema_history"));
    }
    @Test void incorrectFingerprintDoesNotCreateHistory() throws Exception {assertThrows(LegacyDatabaseBaseline.Refused.class,()->baseline("0".repeat(64)));noHistory();}
    @Test void alreadyManagedDatabaseCannotBeBaselinedAgain() throws Exception {String token=check().fingerprint();baseline(token);assertThrows(LegacyDatabaseBaseline.Refused.class,()->baseline(token));assertEquals(1,count("SELECT COUNT(*) FROM flyway_schema_history"));}
    @Test void missingColumnIsRefused() throws Exception {execute("ALTER TABLE products DROP COLUMN description");assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();}
    @Test void missingCheckConstraintIsRefused() throws Exception {execute("ALTER TABLE products DROP CHECK chk_products_price");assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();}
    @Test void unexpectedTableIsRefused() throws Exception {execute("CREATE TABLE surprise(id INT)");assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();}
    @Test void unexpectedViewIsRefused() throws Exception {execute("CREATE VIEW surprise_view AS SELECT id FROM products");assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();}
    @Test void unexpectedTriggerIsRefused() throws Exception {execute("CREATE TRIGGER surprise_trigger BEFORE INSERT ON products FOR EACH ROW SET NEW.description='unexpected'");assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();}
    @Test void concurrentAdoptionCheckIsRefused() throws Exception {
        String lock="campus-baseline-"+LegacyDatabaseBaseline.sha256(schema).substring(0,40);
        try(var s=control.createStatement()){s.execute("SELECT GET_LOCK('"+lock+"',0)");}
        try {assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();}
        finally {try(var s=control.createStatement()){s.execute("SELECT RELEASE_LOCK('"+lock+"')");}}
    }
    @Test void fingerprintIsBoundToSchemaIdentityEvenForIdenticalStructures() throws Exception {
        String original=check().fingerprint();String other="campus_lesson86_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var statement=control.createStatement()){statement.execute("CREATE DATABASE "+other+" CHARACTER SET utf8mb4");}
        String alternateUrl=url.replace("/"+schema+"?","/"+other+"?");
        try {
            Flyway.configure().dataSource(alternateUrl,"root","").locations("classpath:db/migration").target("74").cleanDisabled(true).load().migrate();
            try(var c=DriverManager.getConnection(alternateUrl,"root","");var statement=c.createStatement()){statement.execute("DROP TABLE flyway_schema_history");}
            var env=Map.of("DB_URL",alternateUrl,"DB_USERNAME","root","DB_PASSWORD","");
            var checked=LegacyDatabaseBaseline.execute(new String[]{"check",other},env);assertNotEquals(original,checked.fingerprint());
            assertThrows(LegacyDatabaseBaseline.Refused.class,()->LegacyDatabaseBaseline.execute(new String[]{"baseline",other,original},env));
            noHistory();
        } finally {try(var statement=control.createStatement()){statement.execute("DROP DATABASE "+other);}}
    }
    @Test void schemaChangeAfterCheckInvalidatesAdoption() throws Exception {
        String token=check().fingerprint();execute("ALTER TABLE products MODIFY description VARCHAR(499) NOT NULL DEFAULT ''");
        assertThrows(LegacyDatabaseBaseline.Refused.class,()->baseline(token));noHistory();
    }
    @Test void missingForeignKeyIsRefused() throws Exception {
        execute("ALTER TABLE product_favorites DROP FOREIGN KEY fk_favorites_user");
        assertThrows(LegacyDatabaseBaseline.Refused.class,this::check);noHistory();
    }
    @Test void futureMigrationAfterBaselinePreservesExistingRows() throws Exception {
        baseline(check().fingerprint());
        assertEquals(17,Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").cleanDisabled(true).load().migrate().migrationsExecuted);
        Path migrations=temporary.resolve("migrations");Files.createDirectories(migrations);
        for(var info:flyway().info().all()) {String name=info.getScript();if(!name.endsWith(".sql"))continue;try(var input=new ClassPathResource("db/migration/"+name).getInputStream()){Files.copy(input,migrations.resolve(name));}}
        // Include all frozen resources: info() may also contain the baseline marker, which is not SQL.
        for(String key:LegacyDatabaseBaseline.reference().stringPropertyNames())if(key.startsWith("migration.")) {
            String name=key.substring(10);if(!Files.exists(migrations.resolve(name)))try(var input=new ClassPathResource("db/migration/"+name).getInputStream()){Files.copy(input,migrations.resolve(name));}
        }
        Files.writeString(migrations.resolve("V999__private_adoption_test.sql"),"ALTER TABLE products ADD COLUMN adoption_note VARCHAR(30) NOT NULL DEFAULT '';\n");
        var future=Flyway.configure().dataSource(url,"root","").locations("filesystem:"+migrations).cleanDisabled(true).load();
        assertEquals(1,future.migrate().migrationsExecuted);assertEquals(0,future.migrate().migrationsExecuted);
        assertEquals(1,count("SELECT COUNT(*) FROM products WHERE id=77 AND price_in_cents=3500 AND adoption_note=''"));
        assertEquals(19,count("SELECT COUNT(*) FROM flyway_schema_history"));assertEquals("999",future.info().current().getVersion().toString());
    }
}
