package com.campus.market.migration;
import java.util.*;
import org.flywaydb.core.Flyway;
public final class DatabaseMigrate {
    public static void main(String[] args) {
        try {
            if(args.length!=1||!args[0].matches("campus_(?:development|test|production)"))throw new IllegalArgumentException();
            String schema=args[0],url=required("MIGRATION_DB_URL"),user=required("MIGRATION_DB_USERNAME"),password=required("MIGRATION_DB_PASSWORD");
            if(!url.matches("jdbc:mysql://[a-zA-Z0-9.-]+:[0-9]{1,5}/"+schema+"(?:\\?[^\\s#]*)?")||url.toLowerCase(Locale.ROOT).matches(".*[?&](?:user|password|socketfactory|autodeserialize)=.*")||!user.equals("campus_"+schema.substring(7)+"_migrator"))throw new IllegalArgumentException();
            var flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).load();
            var result=flyway.migrate();flyway.validate();
            System.out.println("{\"status\":\"passed\",\"migrationsExecuted\":"+result.migrationsExecuted+"}");
        }catch(Exception error){System.err.println("Migration refused or failed ("+error.getClass().getSimpleName()+"). Credentials are not printed; inspect target and private logs.");System.exit(2);}
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
}
