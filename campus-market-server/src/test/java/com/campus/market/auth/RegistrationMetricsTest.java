package com.campus.market.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RegistrationMetrics metrics = new RegistrationMetrics(registry, true, 3, 60);
    final LoginRateLimiter store = mock(LoginRateLimiter.class);
    @BeforeEach void enabled() { when(store.protectionEnabled()).thenReturn(true); }
    @AfterEach void close() { registry.close(); }
    RegistrationRateLimiter gate() { return new RegistrationRateLimiter(store, true, 3, 60, metrics); }
    double count(String outcome) { return registry.get("campus.registration.decisions").tag("outcome", outcome).counter().count(); }
    @Test void allFourOutcomesExistBeforeTraffic() { for (String o : List.of("allowed","limited","unavailable","disabled")) assertEquals(0, count(o)); }
    @Test void policyGaugesExposeEffectiveValues() { assertEquals(1,registry.get("campus.registration.protection.enabled").gauge().value()); assertEquals(3,registry.get("campus.registration.protection.limit").gauge().value()); assertEquals(60,registry.get("campus.registration.protection.window.seconds").gauge().value()); }
    @Test void admittedAttemptIncrementsOnlyAllowed() { gate().check("127.0.0.1"); assertEquals(1,count("allowed")); assertEquals(0,count("limited")); assertEquals(0,count("unavailable")); verify(store).beginRegistration("127.0.0.1",3,60); }
    @Test void limitedDecisionPreservesExceptionAndRetryAfter() { var error=new RegistrationRateLimitedException(12); doThrow(error).when(store).beginRegistration(anyString(),anyInt(),anyInt()); assertSame(error,assertThrows(RegistrationRateLimitedException.class,()->gate().check("127.0.0.1"))); assertEquals(1,count("limited")); assertEquals(0,count("allowed")); }
    @Test void unavailableDecisionPreservesException() { var error=new RegistrationProtectionUnavailableException(); doThrow(error).when(store).beginRegistration(anyString(),anyInt(),anyInt()); assertSame(error,assertThrows(RegistrationProtectionUnavailableException.class,()->gate().check("127.0.0.1"))); assertEquals(1,count("unavailable")); assertEquals(0,count("allowed")); }
    @Test void disabledDecisionDoesNotCallRedisStore() { new RegistrationRateLimiter(store,false,3,60,metrics).check("private-address"); verify(store,never()).beginRegistration(anyString(),anyInt(),anyInt()); assertEquals(1,count("disabled")); assertEquals(0,count("allowed")); }
    @Test void compatibilityConstructorWorksWithoutMetrics() { new RegistrationRateLimiter(store,true,3,60).check("127.0.0.1"); assertEquals(0,count("allowed")); }
    @Test void optionalSpringProviderSupportsMonitoringDisabled() { var beans=new StaticListableBeanFactory(); new RegistrationRateLimiter(store,true,3,60,beans.getBeanProvider(RegistrationMetrics.class)).check("127.0.0.1"); verify(store).beginRegistration("127.0.0.1",3,60); }
    @Test void optionalSpringProviderRecordsWhenMonitoringEnabled() { var beans=new StaticListableBeanFactory(); beans.addBean("registrationMetrics",metrics); new RegistrationRateLimiter(store,true,3,60,beans.getBeanProvider(RegistrationMetrics.class)).check("127.0.0.1"); assertEquals(1,count("allowed")); }
    @Test void labelSetIsFixedAndContainsNoRequestData() { for(int i=1;i<=30;i++)gate().check("198.51.100."+i); assertEquals(7,registry.getMeters().size()); for(var meter:registry.getMeters()) { var id=meter.getId(); if(id.getName().equals("campus.registration.decisions")) { assertEquals(1,id.getTags().size()); assertEquals("outcome",id.getTags().get(0).getKey()); } else assertTrue(id.getTags().isEmpty()); assertFalse(id.toString().contains("198.51.100")); } }
    @Test void concurrentDecisionsAreNotLost() throws Exception { var executor=Executors.newFixedThreadPool(8); try { var jobs=new ArrayList<Callable<Void>>(); for(int i=0;i<800;i++)jobs.add(()->{metrics.record(RegistrationMetrics.Outcome.ALLOWED);return null;}); for(var f:executor.invokeAll(jobs))f.get(); assertEquals(800,count("allowed")); } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); } }
    @Test void newProcessRegistryStartsCountersAtZero() { metrics.record(RegistrationMetrics.Outcome.ALLOWED); var fresh=new SimpleMeterRegistry();try{new RegistrationMetrics(fresh,false,60,60);assertEquals(0,fresh.get("campus.registration.decisions").tag("outcome","allowed").counter().count());assertEquals(0,fresh.get("campus.registration.protection.enabled").gauge().value());}finally{fresh.close();} }
    @Test void invalidPolicyRejectedBeforeRegisteringPartialMeters() { for(int[] p:List.of(new int[]{0,60},new int[]{10001,60},new int[]{60,0},new int[]{60,86401})){var fresh=new SimpleMeterRegistry();try{assertThrows(IllegalArgumentException.class,()->new RegistrationMetrics(fresh,true,p[0],p[1]));assertTrue(fresh.getMeters().isEmpty());}finally{fresh.close();}} }
}
