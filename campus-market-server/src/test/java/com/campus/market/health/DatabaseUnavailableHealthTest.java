package com.campus.market.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={"spring.datasource.url=jdbc:mysql://127.0.0.1:1/lesson30_unavailable",
        "spring.datasource.username=unused","spring.datasource.password="})
@AutoConfigureMockMvc
class DatabaseUnavailableHealthTest {
    @Autowired MockMvc mvc;
    @Test void unavailableDatabaseDoesNotBreakLivenessAndReturns503WithoutDetails()throws Exception {
        var live=mvc.perform(get("/api/health/liveness")).andReturn().getResponse();
        assertEquals(200,live.getStatus());
        long start=System.nanoTime();
        var ready=mvc.perform(get("/api/health/readiness")).andReturn().getResponse();
        assertEquals(503,ready.getStatus());assertEquals("no-store",ready.getHeader("Cache-Control"));
        var body=JsonMapper.builder().build().readTree(ready.getContentAsString());
        assertEquals("DOWN",body.get("status").asText());assertEquals(1,body.size());
        assertTrue((System.nanoTime()-start)/1_000_000<6500,"Database acquisition must be bounded");
    }
}
