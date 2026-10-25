package com.campus.market.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/** Validate production configuration before any application beans or migrations run. */
public final class ProductionEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
    public static final String TRUSTED_LOOPBACK = "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1";
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 100; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!env.acceptsProfiles(Profiles.of("production"))) return;
        List<String> invalid = new ArrayList<>();
        check(invalid, "market.deployment.public-origin", publicOrigin(value(env, "market.deployment.public-origin")));
        check(invalid, "server.address", "127.0.0.1".equals(value(env, "server.address")));
        check(invalid, "server.forward-headers-strategy", "native".equalsIgnoreCase(value(env, "server.forward-headers-strategy")));
        check(invalid, "server.tomcat.remoteip.internal-proxies", TRUSTED_LOOPBACK.equals(value(env, "server.tomcat.remoteip.internal-proxies")));
        check(invalid, "server.tomcat.remoteip.remote-ip-header", "x-forwarded-for".equalsIgnoreCase(value(env, "server.tomcat.remoteip.remote-ip-header")));
        check(invalid, "server.tomcat.remoteip.protocol-header", "x-forwarded-proto".equalsIgnoreCase(value(env, "server.tomcat.remoteip.protocol-header")));
        check(invalid, "market.orders.simulated-payment-enabled", bool(env, "market.orders.simulated-payment-enabled", false));
        check(invalid, "market.login-protection.enabled", bool(env, "market.login-protection.enabled", true));
        check(invalid, "market.registration-protection.enabled", bool(env, "market.registration-protection.enabled", true));
        check(invalid, "springdoc.api-docs.enabled", bool(env, "springdoc.api-docs.enabled", false));
        check(invalid, "springdoc.swagger-ui.enabled", bool(env, "springdoc.swagger-ui.enabled", false));
        check(invalid, "spring.flyway.clean-disabled", bool(env, "spring.flyway.clean-disabled", true));
        check(invalid, "spring.flyway.baseline-on-migrate", bool(env, "spring.flyway.baseline-on-migrate", false));
        check(invalid, "spring.datasource.url", value(env, "spring.datasource.url").startsWith("jdbc:mysql:"));
        check(invalid,"spring.flyway.enabled",bool(env,"spring.flyway.enabled",false));
        check(invalid,"spring.datasource.url.schema",value(env,"spring.datasource.url").matches("jdbc:mysql://[a-zA-Z0-9.-]+:[0-9]{1,5}/campus_production(?:\\?[^\\s#]*)?"));
        String jdbcUrl=value(env,"spring.datasource.url");
        check(invalid,"spring.datasource.url.options",!jdbcUrl.toLowerCase(Locale.ROOT).matches(".*[?&](?:user|password|socketfactory|autodeserialize)=.*"));
        boolean localDatabase=jdbcUrl.startsWith("jdbc:mysql://127.0.0.1:")||jdbcUrl.startsWith("jdbc:mysql://localhost:");
        check(invalid,"spring.datasource.url.tls",localDatabase||jdbcUrl.matches("(?i).*[?&]sslMode=VERIFY_IDENTITY(?:&.*)?"));
        String username = value(env, "spring.datasource.username");
        check(invalid,"spring.datasource.username.runtime","campus_production_runtime".equals(username));
        check(invalid, "spring.datasource.username", !username.isBlank() && !"root".equalsIgnoreCase(username));
        check(invalid, "spring.datasource.password", !value(env, "spring.datasource.password").isBlank());
        check(invalid, "market.upload-dir", absolute(value(env, "market.upload-dir")));
        check(invalid, "logging.file.name", absolute(value(env, "logging.file.name")));
        if (Boolean.parseBoolean(value(env,"market.email.enabled"))) {
            check(invalid,"market.email.allow-loopback-plaintext",!Boolean.parseBoolean(value(env,"market.email.allow-loopback-plaintext")));
            check(invalid,"market.email.otp-key",value(env,"market.email.otp-key").length()>=32);
        }
        if (!invalid.isEmpty()) throw new IllegalStateException("Production configuration rejected; invalid properties: " + String.join(", ", invalid));
    }
    private static String value(ConfigurableEnvironment env, String key) {
        try { return env.getProperty(key, "").trim(); }
        catch (IllegalArgumentException error) { return ""; }
    }
    private static boolean bool(ConfigurableEnvironment env, String key, boolean expected) {
        return Boolean.toString(expected).equalsIgnoreCase(value(env,key));
    }
    private static void check(List<String> invalid, String key, boolean valid) { if (!valid) invalid.add(key); }
    private static boolean absolute(String value) {
        try { return !value.isBlank() && Path.of(value).isAbsolute() && !value.contains("\n") && !value.contains("\r"); }
        catch (RuntimeException error) { return false; }
    }
    static boolean publicOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && uri.getRawUserInfo() == null &&
                (uri.getPort() == -1 || uri.getPort() == 443) &&
                (uri.getRawPath() == null || uri.getRawPath().isEmpty()) &&
                uri.getRawQuery() == null && uri.getRawFragment() == null &&
                host.toLowerCase(Locale.ROOT).matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?") &&
                !host.toLowerCase(Locale.ROOT).matches(".*\\.(?:localhost|local|invalid|example|test)$");
        } catch (IllegalArgumentException error) { return false; }
    }
}
