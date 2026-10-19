package com.campus.market.monitoring;
import io.micrometer.prometheusmetrics.*;
import com.campus.market.order.mq.OrderMqMetrics;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class MetricsControllerTest {
 final String token="a".repeat(64);final PrometheusMeterRegistry registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);final OrderMqMetrics metrics=new OrderMqMetrics(registry);final MockMvc mvc=MockMvcBuilders.standaloneSetup(new MetricsController(registry,token)).build();
 @AfterEach void close(){registry.close();}
 @Test void anonymousCannotScrape()throws Exception{mvc.perform(get("/api/ops/metrics")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));}
 @Test void wrongTokenCannotScrape()throws Exception{mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+"b".repeat(64))).andExpect(status().isUnauthorized());}
 @Test void tokenInQueryIsNotAuthentication()throws Exception{mvc.perform(get("/api/ops/metrics").queryParam("token",token)).andExpect(status().isUnauthorized());}
 @Test void malformedHeaderCannotScrape()throws Exception{mvc.perform(get("/api/ops/metrics").header("Authorization",token)).andExpect(status().isUnauthorized());}
 @Test void duplicateAuthorizationIsRejected()throws Exception{mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+token,"Bearer "+token)).andExpect(status().isUnauthorized());}
 @Test void authorizedScrapeIsPrometheusText()throws Exception{metrics.outbox(2,1,0,5);mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().contentType("text/plain;version=0.0.4;charset=utf-8")).andExpect(content().string(org.hamcrest.Matchers.containsString("campus_order_outbox_pending 2.0")));}
 @Test void everyResponseIsNotCacheable()throws Exception{mvc.perform(get("/api/ops/metrics")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Vary","Authorization"));}
 @Test void browserOriginIsRejected()throws Exception{mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+token).header("Origin","http://localhost:5173")).andExpect(status().isForbidden());}
 @Test void authenticatedQueryParametersAreRejected()throws Exception{mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer "+token).queryParam("token",token)).andExpect(status().isBadRequest());}
 @Test void postCannotScrape()throws Exception{mvc.perform(post("/api/ops/metrics").header("Authorization","Bearer "+token)).andExpect(status().isMethodNotAllowed());}
 @Test void enablingWithoutIndependentTokenFailsClosed(){assertThrows(IllegalArgumentException.class,()->new MetricsController(registry,""));assertThrows(IllegalArgumentException.class,()->new MetricsController(registry,"Campus2026!"));}
}
