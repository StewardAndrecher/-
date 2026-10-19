package com.campus.market.docs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
@SpringBootTest(properties={"spring.datasource.url=jdbc:mysql://127.0.0.1:1/disabled","market.login-protection.enabled=false","market.product-cache.enabled=false","market.orders.mq.enabled=false","market.monitoring.enabled=false","market.orders.timeout-sweep-enabled=false"})
@AutoConfigureMockMvc
class ApiDocsDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void defaultConfigurationClosesDocsAndLeavesPublicBusinessCatalogAvailable()throws Exception {
        for(String path:new String[]{"/api-docs","/docs.html","/swagger-ui/index.html","/webjars/swagger-ui/swagger-ui.js"})assertEquals(404,mvc.perform(get(path)).andReturn().getResponse().getStatus(),path);
        assertEquals(200,mvc.perform(get("/api/product-sorts")).andReturn().getResponse().getStatus());
    }
}
