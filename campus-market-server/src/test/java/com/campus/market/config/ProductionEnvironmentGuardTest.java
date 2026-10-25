package com.campus.market.config;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import static org.junit.jupiter.api.Assertions.*;

class ProductionEnvironmentGuardTest {
    MockEnvironment valid() {
        var e = new MockEnvironment();e.setActiveProfiles("production");
        Map.ofEntries(
            Map.entry("market.deployment.public-origin","https://market.campus.edu"),
            Map.entry("server.address","127.0.0.1"),
            Map.entry("server.forward-headers-strategy","native"),
            Map.entry("server.tomcat.remoteip.internal-proxies",ProductionEnvironmentGuard.TRUSTED_LOOPBACK),
            Map.entry("server.tomcat.remoteip.remote-ip-header","x-forwarded-for"),
            Map.entry("server.tomcat.remoteip.protocol-header","x-forwarded-proto"),
            Map.entry("market.orders.simulated-payment-enabled","false"),
            Map.entry("market.login-protection.enabled","true"),
            Map.entry("market.registration-protection.enabled","true"),
            Map.entry("springdoc.api-docs.enabled","false"),
            Map.entry("springdoc.swagger-ui.enabled","false"),
            Map.entry("spring.flyway.clean-disabled","true"),
            Map.entry("spring.flyway.enabled","false"),
            Map.entry("spring.flyway.baseline-on-migrate","false"),
            Map.entry("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/campus_production"),
            Map.entry("spring.datasource.username","campus_production_runtime"),
            Map.entry("spring.datasource.password","fixture-secret-never-print"),
            Map.entry("market.upload-dir","/srv/campus-market/uploads"),
            Map.entry("logging.file.name","/var/log/campus-market/app.log")
        ).forEach(e::setProperty);return e;
    }
    void check(MockEnvironment e){new ProductionEnvironmentGuard().postProcessEnvironment(e,null);}
    @Test void validProductionConfigurationPasses(){assertDoesNotThrow(()->check(valid()));}
    @Test void jdbcOptionsCannotOverrideRuntimeIdentity(){var e=valid();e.setProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/campus_production?user=root&password=hidden");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void remoteDatabaseRequiresIdentityVerifiedTls(){var e=valid();e.setProperty("spring.datasource.url","jdbc:mysql://db.campus.edu:3306/campus_production");assertThrows(IllegalStateException.class,()->check(e));e.setProperty("spring.datasource.url","jdbc:mysql://db.campus.edu:3306/campus_production?sslMode=VERIFY_IDENTITY");assertDoesNotThrow(()->check(e));}
    @Test void runtimeCannotMigrate(){var e=valid();e.setProperty("spring.flyway.enabled","true");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void runtimeCannotUseDevelopmentSchema(){var e=valid();e.setProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/campus_development");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void migratorCannotBeRuntime(){var e=valid();e.setProperty("spring.datasource.username","campus_production_migrator");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void developmentProfileRemainsUnchanged(){assertDoesNotThrow(()->check(new MockEnvironment()));}
    @Test void productionDefaultProfileCannotSkipValidation(){var e=new MockEnvironment();e.setDefaultProfiles("production");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void mixedProfileStillRequiresProductionSafety(){var e=valid();e.setActiveProfiles("development","production");e.setProperty("market.orders.simulated-payment-enabled","true");assertThrows(IllegalStateException.class,()->check(e));}
    @ParameterizedTest @ValueSource(strings={"http://market.campus.edu","https://localhost","https://127.0.0.1","https://10.0.0.1","https://market.invalid","https://market.campus.edu/path","https://market.campus.edu/","https://user:secret@market.campus.edu","https://market.campus.edu?secret=x","https://market.campus.edu#x","https://market.campus.edu:8443","https://a..campus.edu"})
    void malformedOrDevelopmentOriginsRejected(String origin){var e=valid();e.setProperty("market.deployment.public-origin",origin);assertThrows(IllegalStateException.class,()->check(e));}
    @ParameterizedTest @ValueSource(strings={"market.orders.simulated-payment-enabled","market.login-protection.enabled","market.registration-protection.enabled","springdoc.api-docs.enabled","springdoc.swagger-ui.enabled","spring.flyway.clean-disabled","spring.flyway.baseline-on-migrate"})
    void unsafeBooleanOverrideRejected(String key){var e=valid();e.setProperty(key,"true".equals(e.getProperty(key))?"false":"true");assertThrows(IllegalStateException.class,()->check(e));}
    @ParameterizedTest @ValueSource(strings={"server.address","server.forward-headers-strategy","server.tomcat.remoteip.internal-proxies","server.tomcat.remoteip.remote-ip-header","server.tomcat.remoteip.protocol-header","spring.datasource.username","spring.datasource.password","market.upload-dir","logging.file.name"})
    void missingRequiredValueRejected(String key){var e=valid();e.setProperty(key,"");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void blankProxyPatternCannotTrustEveryAddress(){var e=valid();e.setProperty("server.tomcat.remoteip.internal-proxies",".*");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void externalJavaBindingRejected(){var e=valid();e.setProperty("server.address","0.0.0.0");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void rootDatabaseAccountRejected(){var e=valid();e.setProperty("spring.datasource.username","root");assertThrows(IllegalStateException.class,()->check(e));}
    @Test void relativeDataLocationsRejected(){for(String key:List.of("market.upload-dir","logging.file.name")){var e=valid();e.setProperty(key,"./data");assertThrows(IllegalStateException.class,()->check(e));}}
    @Test void failureListsKeysWithoutLeakingValues(){var e=valid();e.setProperty("market.deployment.public-origin","https://user:super-secret@market.campus.edu");var error=assertThrows(IllegalStateException.class,()->check(e));assertTrue(error.getMessage().contains("market.deployment.public-origin"));assertFalse(error.getMessage().contains("super-secret"));assertFalse(error.getMessage().contains("fixture-secret"));}
    @Test void guardIsRegisteredAndRunsAfterConfigFiles(){assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class,getClass().getClassLoader()).contains(ProductionEnvironmentGuard.class.getName()));assertTrue(new ProductionEnvironmentGuard().getOrder()>ConfigDataEnvironmentPostProcessor.ORDER);}
}
