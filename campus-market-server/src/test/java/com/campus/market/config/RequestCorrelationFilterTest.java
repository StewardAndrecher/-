package com.campus.market.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.campus.market.common.GlobalExceptionHandler;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RequestCorrelationFilterTest {
    final RequestCorrelationFilter filter = new RequestCorrelationFilter();
    final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    final Logger access = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);
    final Logger errors = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    Level previousLevel;
    AppenderBase<ILoggingEvent> appender;
    MockMvc mvc;
    @BeforeEach void setup() {
        MDC.clear(); previousLevel = access.getLevel(); access.setLevel(Level.INFO);
        appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); events.add(event);
            }
        };
        appender.start(); access.addAppender(appender); errors.addAppender(appender);
        mvc = MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter).build();
    }
    @AfterEach void cleanup() {
        access.detachAppender(appender); errors.detachAppender(appender); appender.stop();
        access.setLevel(previousLevel); MDC.clear();
    }
    @RestController @RequestMapping("/api/trace-fixture")
    @CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"}, exposedHeaders="Retry-After")
    static class Fixture {
        @GetMapping("/ok") Map<String,String> ok() { return Map.of("id", MDC.get("requestId")); }
        @GetMapping("/failure") Map<String,String> failure() { throw new IllegalStateException("isolated synthetic failure"); }
        @GetMapping("/limited") ResponseEntity<?> limited() { return ResponseEntity.status(503).header("Retry-After","5").body(Map.of("message","retry later")); }
    }
    String id(MockHttpServletResponse response) {
        String id=response.getHeader(RequestCorrelationFilter.HEADER);
        assertNotNull(id); assertTrue(id.matches("[0-9a-f]{32}")); return id;
    }
    @Test void successfulRequestSharesIdAcrossResponseControllerAndSummary() throws Exception {
        var response=mvc.perform(get("/api/trace-fixture/ok")).andReturn().getResponse();
        String id=id(response); assertEquals(200,response.getStatus()); assertTrue(response.getContentAsString().contains(id));
        assertTrue(events.stream().anyMatch(e->id.equals(e.getMDCPropertyMap().get("requestId")) && e.getFormattedMessage().contains("status=200")));
        assertNull(MDC.get("requestId"));
    }
    @Test void inboundIdIsIgnoredAndIndependentRequestsGetDifferentIds() throws Exception {
        String forged="a".repeat(32);
        var first=mvc.perform(get("/api/trace-fixture/ok").header("X-Request-Id",forged)).andReturn().getResponse();
        var second=mvc.perform(get("/api/trace-fixture/ok")).andReturn().getResponse();
        assertNotEquals(forged,id(first)); assertNotEquals(id(first),id(second)); assertNull(MDC.get("requestId"));
    }
    @Test void realExceptionHandlerAndCompletionSummaryHaveTheResponseId() throws Exception {
        var response=mvc.perform(get("/api/trace-fixture/failure")).andReturn().getResponse();
        String id=id(response); assertEquals(500,response.getStatus());
        assertTrue(response.getContentAsString().contains("SERVER_ERROR")); assertFalse(response.getContentAsString().contains("synthetic failure"));
        assertTrue(events.stream().anyMatch(e->e.getLevel()==Level.ERROR && id.equals(e.getMDCPropertyMap().get("requestId"))));
        assertTrue(events.stream().anyMatch(e->e.getFormattedMessage().contains("status=500") && id.equals(e.getMDCPropertyMap().get("requestId"))));
    }
    @Test void summaryUsesRouteTemplateAndDoesNotIncludePrivateTransportData() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/products/private-path-value");
        request.setQueryString("password=query-secret"); request.addHeader("Authorization","Bearer auth-secret");
        request.setContent("body-secret".getBytes()); request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/products/{id}");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->{});
        String line=events.get(0).getFormattedMessage(); assertTrue(line.contains("route=/api/products/{id}"));
        for(String secret:List.of("private-path-value","query-secret","auth-secret","body-secret")) assertFalse(line.contains(secret));
    }
    @Test void unhandledExceptionIsLoggedAs500AndMdcIsRestored() {
        MDC.put("requestId","outer-context");
        var response=new MockHttpServletResponse();
        assertThrows(ServletException.class,()->filter.doFilter(new MockHttpServletRequest("GET","/broken"),response,(r,s)->{throw new ServletException("fixture");}));
        assertEquals("outer-context",MDC.get("requestId")); assertTrue(events.get(0).getFormattedMessage().contains("status=500"));
        assertEquals(id(response),events.get(0).getMDCPropertyMap().get("requestId"));
    }
    @Test void malformedRouteAndMethodCannotInjectLogLines() throws Exception {
        var request=new MockHttpServletRequest("PRIVATE\nMETHOD","/raw-secret");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/bad\nroute");
        filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{});
        assertEquals(1,events.size()); String line=events.get(0).getFormattedMessage();
        assertTrue(line.contains("method=OTHER route=unmatched")); assertFalse(line.contains("\n")); assertFalse(line.contains("secret"));
    }
    @Test void probesAndScrapesStillHaveIdsButDoNotFloodAccessSummary() throws Exception {
        for(String path:List.of("/api/ops/metrics","/api/health","/api/health/readiness","/api/health/liveness")) {
            var response=new MockHttpServletResponse(); filter.doFilter(new MockHttpServletRequest("GET",path),response,(r,s)->{}); id(response);
        }
        assertTrue(events.isEmpty()); assertNull(MDC.get("requestId"));
    }
    @Test void allowedCorsResponseExposesIdAndPreservesRetryAfter() throws Exception {
        var response=mvc.perform(get("/api/trace-fixture/limited").header("Origin","http://localhost:5173")).andReturn().getResponse();
        assertEquals(503,response.getStatus()); id(response);
        assertEquals("http://localhost:5173",response.getHeader("Access-Control-Allow-Origin"));
        String exposed=response.getHeader("Access-Control-Expose-Headers");
        assertTrue(exposed.contains("X-Request-Id")); assertTrue(exposed.contains("Retry-After")); assertEquals("5",response.getHeader("Retry-After"));
    }
    @Test void corsOriginRulesAreNotExpanded() throws Exception {
        var response=mvc.perform(options("/api/trace-fixture/ok").header("Origin","https://foreign.example").header("Access-Control-Request-Method","GET")).andReturn().getResponse();
        assertEquals(403,response.getStatus()); assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }
    @Test void exposedHeaderSetAndAddKeepOneCaseInsensitiveIdAndOtherNames() throws Exception {
        var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/cors"),response,(r,s)->{
            var res=(jakarta.servlet.http.HttpServletResponse)s;
            res.setHeader("Access-Control-Expose-Headers","Retry-After");
            res.addHeader("access-control-expose-headers","x-request-id, ETag");
        });
        var names=Arrays.stream(response.getHeader("Access-Control-Expose-Headers").split(",")).map(String::trim).toList();
        assertEquals(1,names.stream().filter(n->n.equalsIgnoreCase("X-Request-Id")).count());
        assertTrue(names.contains("ETag")); assertTrue(names.contains("Retry-After"));
    }
    @Test void simultaneousRequestsKeepDistinctThreadContexts() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<16;i++) futures.add(pool.submit(()->{
                var response=new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET","/parallel"),response,(r,s)->assertEquals(response.getHeader("X-Request-Id"),MDC.get("requestId")));
                assertNull(MDC.get("requestId")); return id(response);
            }));
            Set<String> ids=new HashSet<>(); for(var future:futures) ids.add(future.get(10,TimeUnit.SECONDS)); assertEquals(16,ids.size());
        } finally {pool.shutdownNow();}
    }
}
