package com.campus.market.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=${LESSON28_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root","spring.datasource.password="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON28_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson28_verify\\?.*")
class HealthEndpointIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    final JsonMapper json = JsonMapper.builder().build();

    void check(String path,int status,String state)throws Exception {
        var response=mvc.perform(get(path)).andReturn().getResponse();
        assertEquals(status,response.getStatus());
        assertEquals("no-store",response.getHeader("Cache-Control"));
        var body=json.readTree(response.getContentAsString());
        assertEquals(state,body.get("status").asText());
        assertFalse(body.has("details"));
        assertFalse(body.has("components"));
        if (path.equals("/api/health")) {
            assertEquals(2,body.size());
            assertEquals(2,body.get("groups").size());
            assertTrue(body.get("groups").toString().contains("liveness"));
            assertTrue(body.get("groups").toString().contains("readiness"));
        } else assertEquals(1,body.size(),"Probe must not expose database details");
        assertNull(response.getHeader("Set-Cookie"));
    }
    @Test void healthyDatabaseAndApplicationReturnAnonymousUp()throws Exception {
        check("/api/health",200,"UP");
        check("/api/health/liveness",200,"UP");
        check("/api/health/readiness",200,"UP");
    }
    @Test void localBrowserOriginsAreAllowed()throws Exception {
        for(String origin:new String[]{"http://localhost:5173","http://127.0.0.1:5234"}) {
            var response=mvc.perform(get("/api/health/liveness").header("Origin",origin)).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals(origin,response.getHeader("Access-Control-Allow-Origin"));
        }
    }
    @Test void foreignBrowserOriginIsRejected()throws Exception {
        var response=mvc.perform(options("/api/health/readiness").header("Origin","https://foreign.example")
                .header("Access-Control-Request-Method","GET")).andReturn().getResponse();
        assertEquals(403,response.getStatus());assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }
    @Test void acceptsJsonRequestsWithoutSessionToken()throws Exception {
        var response=mvc.perform(get("/api/health/readiness").header("Accept","application/json")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("UP",json.readTree(response.getContentAsString()).get("status").asText());
    }
    @Test void healthEndpointIsReadOnly()throws Exception {
        assertEquals(405,mvc.perform(post("/api/health/liveness")).andReturn().getResponse().getStatus());
    }
    @Test void otherManagementEndpointsAndDiscoveryAreUnavailable()throws Exception {
        for(String path:new String[]{"/api/env","/api/beans","/api/metrics","/api/configprops","/api"})
            assertEquals(404,mvc.perform(get(path)).andReturn().getResponse().getStatus(),path);
    }
    @Test void readinessStateCanFailWhileApplicationIsLive()throws Exception {
        AvailabilityChangeEvent.publish(context,ReadinessState.REFUSING_TRAFFIC);
        try {check("/api/health/liveness",200,"UP");check("/api/health/readiness",503,"OUT_OF_SERVICE");}
        finally {AvailabilityChangeEvent.publish(context,ReadinessState.ACCEPTING_TRAFFIC);}
    }
    @Test void brokenLivenessProducesServiceUnavailable()throws Exception {
        AvailabilityChangeEvent.publish(context,LivenessState.BROKEN);
        try {check("/api/health/liveness",503,"DOWN");}
        finally {AvailabilityChangeEvent.publish(context,LivenessState.CORRECT);}
    }
}
