package com.campus.market.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;
import java.util.Map;

/** Local operator tool, never exposed as an HTTP endpoint or application startup hook. */
public final class AdminRoleTool {
    private AdminRoleTool() {}
    static final class Refused extends RuntimeException {Refused(String message) {super(message);}}
    public record Result(long userId, String username, boolean admin, boolean changed, String fingerprint) {
        public String json() {return "{\"userId\":"+userId+",\"username\":\""+username+"\",\"admin\":"+admin+",\"changed\":"+changed+",\"fingerprint\":\""+fingerprint+"\"}";}
    }
    public static void main(String[] args) {
        try {System.out.println(execute(args, System.getenv()).json());}
        catch (Exception error) {
            System.err.println(error instanceof Refused ? error.getMessage() : "Role tool failed ("+error.getClass().getSimpleName()+"). Inspect local configuration; credentials are not printed.");
            System.exit(2);
        }
    }
    static Result execute(String[] args, Map<String,String> env) throws Exception {
        require(args.length==3 || args.length==5, "Usage: check <schema> <username> | grant/revoke <schema> <username> <fingerprint> <reason>");
        boolean check=args[0].equals("check"), grant=args[0].equals("grant");
        require((check && args.length==3) || ((grant || args[0].equals("revoke")) && args.length==5), "Invalid role operation");
        String schema=args[1], username=args[2];
        require(schema.matches("[a-z][a-z0-9_]{0,63}") && username.matches("[a-z0-9_]{4,32}"), "Invalid schema or username");
        String url=env.getOrDefault("DB_URL", "");
        require(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"+schema+"[?][^#]*"), "DB_URL must target the named schema on explicit IPv4 loopback");
        String reason=check ? "" : args[4].strip();
        require(check || (reason.length()>=2 && reason.length()<=200 && reason.codePoints().noneMatch(Character::isISOControl)), "Reason must contain 2-200 printable characters");
        require(check || args[3].matches("[0-9a-f]{64}"), "Invalid check fingerprint");
        try (Connection connection=DriverManager.getConnection(url,env.getOrDefault("DB_USERNAME", ""),env.getOrDefault("DB_PASSWORD", ""))) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try {
                require(schema.equals(connection.getCatalog()), "Database catalog mismatch");
                // Lock the same user row for grant/revoke so concurrent local tools serialize absent grants as well.
                long id;
                try (PreparedStatement statement=connection.prepareStatement("SELECT id FROM users WHERE username=? FOR UPDATE")) {
                    statement.setString(1,username);try(ResultSet rows=statement.executeQuery()){require(rows.next(), "Registered user not found; register the account first");id=rows.getLong(1);}
                }
                String current=null;
                try (PreparedStatement statement=connection.prepareStatement("SELECT CAST(granted_at AS CHAR) FROM user_role_grants WHERE user_id=? AND role_code='ADMIN' FOR UPDATE")) {
                    statement.setLong(1,id);try(ResultSet rows=statement.executeQuery()){if(rows.next())current=rows.getString(1);}
                }
                boolean before=current!=null;String fingerprint=fingerprint(schema,id,username,current);
                if(check){connection.rollback();return new Result(id,username,before,false,fingerprint);}
                require(MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),args[3].getBytes(StandardCharsets.US_ASCII)), "Account or role changed after check; inspect it again");
                if(before==grant){connection.rollback();return new Result(id,username,before,false,fingerprint);}
                String mutation=grant ? "INSERT INTO user_role_grants(user_id,role_code) VALUES(?,'ADMIN')" : "DELETE FROM user_role_grants WHERE user_id=? AND role_code='ADMIN'";
                try(PreparedStatement statement=connection.prepareStatement(mutation)){statement.setLong(1,id);require(statement.executeUpdate()==1,"Role state changed concurrently");}
                try(PreparedStatement statement=connection.prepareStatement("INSERT INTO user_role_audit(target_user_id,target_username,action,operator_source,reason) VALUES(?,?,?,'LOCAL_OPERATOR',?)")) {
                    statement.setLong(1,id);statement.setString(2,username);statement.setString(3,grant?"GRANT":"REVOKE");statement.setString(4,reason);require(statement.executeUpdate()==1,"Cannot write role audit");
                }
                connection.commit();return new Result(id,username,grant,true,"");
            } catch(Exception error){connection.rollback();throw error;}
        }
    }
    private static String fingerprint(String schema,long id,String username,String grant) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("role-v1|"+schema+"|"+id+"|"+username+"|"+grant).getBytes(StandardCharsets.UTF_8)));
    }
    private static void require(boolean accepted,String message) {if(!accepted)throw new Refused(message);}
}
