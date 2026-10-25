package com.campus.market.migration;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyDatabaseBaselineTest {
    Map<String,String> environment() {return new HashMap<>(Map.of("DB_URL","jdbc:mysql://127.0.0.1:3306/campus_market", "DB_USERNAME","root", "DB_PASSWORD",""));}
    @Test void requiresAnExplicitCommandAndSchema() {assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[0], environment()));}
    @Test void rejectsUnknownCommandsBeforeConnecting() {assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"clean","campus_market"}, environment()));}
    @Test void rejectsExtraCheckArguments() {assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"check","campus_market","x"}, environment()));}
    @Test void baselineRequiresThePreviousFingerprint() {assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"baseline","campus_market"}, environment()));}
    @Test void rejectsUnsafeSchemaIdentifiers() {assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"check","campus_market;DROP DATABASE x"}, environment()));}
    @Test void rejectsRemoteConnections() {var env=environment();env.put("DB_URL","jdbc:mysql://example.com:3306/campus_market");assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"check","campus_market"}, env));}
    @Test void rejectsTheWrongSchema() {assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"check","another_schema"}, environment()));}
    @Test void requiresPasswordEnvironmentEvenWhenPasswordIsEmpty() {var env=environment();env.remove("DB_PASSWORD");assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"check","campus_market"}, env));}
    @Test void refusesCredentialsInJdbcUrl() {var env=environment();env.put("DB_URL",env.get("DB_URL")+"?password=secret");assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.execute(new String[]{"check","campus_market"}, env));}
    @Test void removesOnlyTheTableAutoIncrementCounter() {
        String ddl="CREATE TABLE `x` (\n `note` varchar(90) DEFAULT 'AUTO_INCREMENT=123 ',\n `id` bigint AUTO_INCREMENT\n) ENGINE=InnoDB AUTO_INCREMENT=89 DEFAULT CHARSET=utf8mb4";
        assertEquals(ddl.replace("ENGINE=InnoDB AUTO_INCREMENT=89 ","ENGINE=InnoDB "),LegacyDatabaseBaseline.normalize(ddl));
    }
    @Test void verifiesAllTwentyFiveFrozenSqlResources() throws Exception {LegacyDatabaseBaseline.verifyMigrations(LegacyDatabaseBaseline.reference());}
    @Test void changedMigrationManifestIsRefused() throws Exception {var reference=LegacyDatabaseBaseline.reference();reference.setProperty("migration.V1__products.sql","0".repeat(64));assertThrows(LegacyDatabaseBaseline.Refused.class, () -> LegacyDatabaseBaseline.verifyMigrations(reference));}
}
