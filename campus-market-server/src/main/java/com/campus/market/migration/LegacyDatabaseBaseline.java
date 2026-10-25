package com.campus.market.migration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Explicit local-only adoption of the frozen V74 schema. Never called during application startup. */
public final class LegacyDatabaseBaseline {
    static final String VERSION = "74";
    private LegacyDatabaseBaseline() {}

    public static void main(String[] arguments) {
        try {
            Report report = execute(arguments, System.getenv());
            System.out.println(report.json());
        } catch (Exception error) {
            // Driver/Flyway exception messages can contain connection details; do not echo them.
            System.err.println(error instanceof Refused ? error.getMessage()
                    : "Baseline failed (" + error.getClass().getSimpleName()
                    + "). Inspect database permissions and local configuration; credentials are not printed.");
            System.exit(2);
        }
    }

    static Report execute(String[] arguments, Map<String, String> environment) throws Exception {
        require(arguments.length >= 2, "Usage: check <schema> | baseline <schema> <checkFingerprint>");
        boolean writing = arguments[0].equals("baseline");
        require((writing && arguments.length == 3) || (arguments[0].equals("check") && arguments.length == 2),
                "Usage: check <schema> | baseline <schema> <checkFingerprint>");
        String schema = arguments[1];
        require(schema.matches("[a-z][a-z0-9_]{0,63}"), "Schema name must be a lowercase SQL identifier.");
        String url = value(environment, "DB_URL");
        // Deliberately support one local MySQL server and one explicitly named schema only.
        require(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"
                + schema + "(?:\\?[^\\s#]*)?"), "DB_URL must name the requested schema on local MySQL with an explicit port.");
        require(!url.toLowerCase(Locale.ROOT).matches(".*[?&](?:user|password|socketfactory|autodeserialize)=.*"),
                "Put credentials in DB_USERNAME/DB_PASSWORD; unsafe JDBC options are refused.");
        String user = value(environment, "DB_USERNAME");
        require(environment.containsKey("DB_PASSWORD"), "Missing DB_PASSWORD environment variable.");
        String password = environment.get("DB_PASSWORD");
        Properties reference = reference();
        verifyMigrations(reference);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            require(schema.equals(connection.getCatalog()), "Connected schema does not match the requested schema.");
            String lock = "campus-baseline-" + sha256(schema).substring(0, 40);
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, lock);
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next() && rows.getInt(1) == 1, "Another adoption check holds this database lock.");
                }
            }
            try {
                Report checked = inspect(connection, schema, reference);
                if (!writing) return checked;
                require(arguments[2].matches("[0-9a-f]{64}") && arguments[2].equals(checked.fingerprint()),
                        "Check fingerprint changed or is invalid. Run check again and review the target.");
                // Re-read immediately before the only write. GET_LOCK serializes this tool, not outside DDL.
                require(checked.equals(inspect(connection, schema, reference)), "Schema changed during adoption check.");
                Flyway flyway = Flyway.configure().dataSource(url, user, password)
                        .locations("classpath:db/migration").target(VERSION).baselineVersion(VERSION)
                        .baselineDescription("Verified legacy V74 schema")
                        .baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).load();
                flyway.baseline();
                flyway.validate();
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT COUNT(*), SUM(type='BASELINE' AND version='74' AND success=1) FROM flyway_schema_history")) {
                    require(rows.next() && rows.getInt(1) == 1 && rows.getInt(2) == 1,
                            "Baseline history needs manual inspection; do not retry or repair automatically.");
                }
                return new Report(schema, VERSION, checked.tables(), checked.fingerprint(), "BASELINED", 1);
            } finally {
                try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, lock); statement.execute();
                }
            }
        }
    }

    static Properties reference() throws IOException {
        Properties properties = new Properties();
        try (InputStream source = LegacyDatabaseBaseline.class.getResourceAsStream("/db/baseline/legacy-v74.properties")) {
            if (source == null) throw new IOException("Missing frozen reference");
            properties.load(new InputStreamReader(source, StandardCharsets.UTF_8));
        }
        return properties;
    }

    static void verifyMigrations(Properties reference) throws Exception {
        require(VERSION.equals(reference.getProperty("baselineVersion")), "Frozen baseline version is invalid.");
        SortedMap<String, String> actual = new TreeMap<>();
        Set<String> names = new HashSet<>();
        Set<MigrationVersion> versions = new HashSet<>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            String name = resource.getFilename();
            require(name != null && name.matches("V[0-9]+__.+\\.sql") && names.add(name), "Invalid or duplicate migration resource.");
            MigrationVersion version = MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
            require(versions.add(version), "Duplicate migration version.");
            // Future migrations remain pending; only the frozen prefix is eligible for V74 baseline.
            if (version.compareTo(MigrationVersion.fromVersion(VERSION)) > 0) continue;
            try (InputStream source = resource.getInputStream()) {actual.put(name, hex(MessageDigest.getInstance("SHA-256").digest(source.readAllBytes())));}
        }
        SortedMap<String, String> expected = new TreeMap<>();
        for (String key : reference.stringPropertyNames()) if (key.startsWith("migration.")) expected.put(key.substring(10), reference.getProperty(key));
        require(expected.size() == 25 && actual.equals(expected),
                "Historical migration files differ from the frozen 25-version reference. Do not raise the baseline version to skip changes.");
    }

    static Report inspect(Connection connection, String schema, Properties reference) throws Exception {
        SortedMap<String, String> expected = new TreeMap<>(), actual = new TreeMap<>();
        for (String key : reference.stringPropertyNames()) if (key.startsWith("table.")) expected.put(key.substring(6), reference.getProperty(key));
        require(expected.size() == 14, "Frozen reference must contain all fourteen business tables.");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    require(!name.equals("flyway_schema_history"), "Database already has Flyway history; use normal validate/migrate, not baseline.");
                    require(expected.containsKey(name) && rows.getString(2).equals("BASE TABLE"), "Unexpected table or view; baseline refused.");
                    try (Statement ddl = connection.createStatement(); ResultSet create = ddl.executeQuery("SHOW CREATE TABLE `" + name + "`")) {
                        require(create.next(), "Cannot read table structure."); actual.put(name, normalize(create.getString(2)));
                    }
                }
            }
        }
        require(actual.keySet().equals(expected.keySet()), "Business table set does not match V74.");
        for (String table : expected.keySet()) require(expected.get(table).equals(actual.get(table)),
                "Table structure differs from V74: " + table + ". No automatic schema modification is performed.");
        for (String query : List.of(
                "SELECT COUNT(*) FROM information_schema.triggers WHERE TRIGGER_SCHEMA=?",
                "SELECT COUNT(*) FROM information_schema.routines WHERE ROUTINE_SCHEMA=?",
                "SELECT COUNT(*) FROM information_schema.events WHERE EVENT_SCHEMA=?")) {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, schema);
                try (ResultSet rows = statement.executeQuery()) {require(rows.next() && rows.getInt(1) == 0, "Unexpected trigger, routine or event; baseline refused.");}
            }
        }
        String identity;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT @@server_uuid")) {
            require(rows.next(), "Cannot identify database server."); identity = rows.getString(1);
        }
        String material = VERSION + "\n" + identity + "\n" + schema + "\n" + actual;
        return new Report(schema, VERSION, actual.size(), sha256(material), "CHECKED", 0);
    }

    static String normalize(String ddl) {return ddl.replaceAll("(?m)(^\\) ENGINE=\\S+ )AUTO_INCREMENT=[0-9]+ ", "$1").strip();}
    static String value(Map<String, String> environment, String key) throws Refused {
        String value = environment.get(key); require(value != null && !value.isBlank(), "Missing " + key + " environment variable."); return value;
    }
    static String sha256(String text) throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    static String hex(byte[] bytes) {return HexFormat.of().formatHex(bytes);}
    static void require(boolean condition, String message) throws Refused {if (!condition) throw new Refused(message);}
    static final class Refused extends Exception {Refused(String message) {super(message);}}
    record Report(String schema, String baselineVersion, int tables, String fingerprint, String status, int historyRows) {
        String json() {return "{\"status\":\"" + status + "\",\"schema\":\"" + schema + "\",\"baselineVersion\":\"" + baselineVersion
                + "\",\"businessTables\":" + tables + ",\"fingerprint\":\"" + fingerprint + "\",\"historyRows\":" + historyRows + "}";}
    }
}
