package com.campus.market.docs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.url=${LESSON74_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}","spring.datasource.username=root","spring.datasource.password=","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.orders.timeout-sweep-enabled=false","springdoc.api-docs.enabled=true","springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LESSON74_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson74_verify\\?.*")
class ApiContractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    final JsonMapper json=JsonMapper.builder().build();JsonNode api;
    @BeforeEach void specification()throws Exception {
        var r=mvc.perform(get("/api-docs")).andReturn().getResponse();assertEquals(200,r.getStatus());
        api=json.readTree(r.getContentAsByteArray());
    }
    JsonNode op(String path,String method){return api.path("paths").path(path).path(method);}
    JsonNode schema(String suffix){var s=api.path("components").path("schemas");for(String name:s.propertyNames())if(name.endsWith(suffix))return s.get(name);fail("Schema not found: "+suffix);return null;}
    @Test void includesExactlyAllBusinessRoutesAndUniqueOperationIds(){
        Set<String> expected=new TreeSet<>(),actual=new TreeSet<>(),ids=new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping,handler)->{
            String pkg=handler.getBeanType().getPackageName();if(!pkg.startsWith("com.campus.market")||pkg.contains(".health")||pkg.contains(".monitoring"))return;
            for(String path:mapping.getPatternValues())if(path.startsWith("/api/")&&!path.startsWith("/api/ops/"))for(var method:mapping.getMethodsCondition().getMethods()){
                expected.add(method.name().toLowerCase()+" "+path);
                boolean authorization=Arrays.stream(handler.getMethodParameters()).anyMatch(p->{var h=p.getParameterAnnotation(org.springframework.web.bind.annotation.RequestHeader.class);return h!=null&&"Authorization".equals(h.name());});
                if(!authorization)assertTrue(op(path,method.name().toLowerCase()).path("security").isEmpty(),"Public route incorrectly requires auth: "+path);
            }
        });
        var paths=api.path("paths");for(String p:paths.propertyNames())for(String method:paths.path(p).propertyNames()){
            JsonNode operation=paths.path(p).path(method);actual.add(method+" "+p);assertTrue(ids.add(operation.path("operationId").asString()));assertFalse(operation.path("tags").isEmpty());assertTrue(operation.path("summary").asString().contains("："));
            for(JsonNode parameter:operation.path("parameters"))assertNotEquals("Authorization",parameter.path("name").asString());
        }
        assertFalse(expected.isEmpty());assertEquals(expected,actual);assertReferencesResolve(api);
    }
    @Test void distinguishesPublicOptionalAndRequiredSessionAuthentication(){
        assertTrue(op("/api/products","get").path("security").isEmpty());
        var optional=op("/api/products/{id}","get").path("security");assertEquals(2,optional.size());assertTrue(optional.get(0).isEmpty());assertTrue(optional.get(1).has("bearerAuth"));
        assertTrue(op("/api/orders","get").path("security").get(0).has("bearerAuth"));
        var bearer=api.at("/components/securitySchemes/bearerAuth");assertEquals("bearer",bearer.path("scheme").asString());assertEquals("opaque session token",bearer.path("bearerFormat").asString());
    }
    @Test void documentsCreationReplayEmptyAndLoginProtectionResponses(){
        var order=op("/api/products/{id}/orders","post").path("responses");assertTrue(order.has("200")&&order.has("201")&&order.has("409"));
        assertTrue(op("/api/products","post").path("responses").has("201"));
        var logout=op("/api/users/logout","post").path("responses");assertTrue(logout.has("204"));assertFalse(logout.path("204").has("content"));assertFalse(logout.has("200"));
        for(String status:List.of("429","503"))assertTrue(op("/api/users/login","post").path("responses").path(status).path("headers").has("Retry-After"));
        assertTrue(order.path("201").path("headers").has("X-Request-Id"));
    }
    @Test void preservesDifferentPageModelsAndRequiredInputConstraints(){
        var orderPage=schema("OrderModels.Page");var messagePage=schema("PurchaseIntentMessageModels.Page");
        assertNotEquals(orderPage.path("properties").path("items"),messagePage.path("properties").path("items"));
        assertTrue(orderPage.path("properties").has("summary"));assertFalse(messagePage.path("properties").has("summary"));
        var create=schema("OrderModels.Create");assertTrue(create.path("required").toString().contains("productVersion"));assertEquals("integer",create.at("/properties/productVersion/type").asString());assertEquals("uuid",create.at("/properties/requestId/format").asString());
        assertTrue(schema("LoginUserRequest").at("/properties/password/writeOnly").asBoolean());assertEquals(3,schema("RegisterUserRequest").path("required").size());
    }
    @Test void excludesOperationalEndpointsInternalRecordsAndSecrets(){
        String spec=api.toString();assertFalse(spec.contains("/api/ops/"));assertFalse(spec.contains("/api/health"));assertFalse(spec.contains("passwordHash"));assertFalse(spec.contains("MONITORING_TOKEN"));assertFalse(spec.contains("StoredOrder"));assertEquals("/",api.path("servers").get(0).path("url").asString());
        assertTrue(schema("ApiError").path("properties").has("code"));assertTrue(schema("ApiError").path("properties").has("message"));
    }
    @Test void realValidationAndAnonymousErrorsMatchDocumentedContract()throws Exception {
        var r=mvc.perform(get("/api/products").param("page","0")).andReturn().getResponse();assertEquals(400,r.getStatus());assertTrue(op("/api/products","get").path("responses").has("400"));
        var error=json.readTree(r.getContentAsByteArray());assertEquals(2,error.size());assertTrue(error.has("code")&&error.has("message"));assertTrue(r.getHeader("X-Request-Id").matches("[0-9a-f]{32}"));
        var auth=mvc.perform(get("/api/users/me")).andReturn().getResponse();assertEquals(401,auth.getStatus());assertTrue(op("/api/users/me","get").path("responses").has("401"));
    }
    @Test void servesOnlyLocalSwaggerAssetsWithoutExternalValidator()throws Exception {
        assertEquals(302,mvc.perform(get("/docs.html")).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus());
        var config=json.readTree(mvc.perform(get("/api-docs/swagger-config")).andReturn().getResponse().getContentAsByteArray());
        assertEquals("/api-docs",config.path("url").asString());assertFalse(config.path("persistAuthorization").asBoolean());assertTrue(config.path("validatorUrl").asString("").isEmpty());
        assertEquals(403,mvc.perform(get("/api-docs").with(q->{q.setRemoteAddr("192.168.2.3");return q;})).andReturn().getResponse().getStatus());
    }

    @Test void publicSortCatalogMatchesAnonymousContract()throws Exception {
        assertTrue(op("/api/product-sorts","get").path("security").isEmpty());
        assertEquals(200,mvc.perform(get("/api/product-sorts")).andReturn().getResponse().getStatus());
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void realCreateReplayAndLogoutMatchTheirDocumentedSuccessStatuses()throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,10);
        String seller=registerAndLogin("doc_s"+suffix),buyer=registerAndLogin("doc_b"+suffix);
        var productResult=mvc.perform(post("/api/products").header("Authorization",seller).contentType("application/json").content("{\"title\":\"契约验收教材\",\"description\":\"隔离事务回滚\",\"priceInCents\":3500}" )).andReturn().getResponse();
        assertEquals(201,productResult.getStatus(),productResult.getContentAsString());
        var product=json.readTree(productResult.getContentAsByteArray());long id=product.path("id").asLong();
        String body=json.writeValueAsString(Map.of("productVersion",product.path("version").asLong(),"requestId",UUID.randomUUID().toString()));
        var first=mvc.perform(post("/api/products/"+id+"/orders").header("Authorization",buyer).contentType("application/json").content(body)).andReturn().getResponse();assertEquals(201,first.getStatus(),first.getContentAsString());
        var replay=mvc.perform(post("/api/products/"+id+"/orders").header("Authorization",buyer).contentType("application/json").content(body)).andReturn().getResponse();assertEquals(200,replay.getStatus());
        var created=json.readTree(first.getContentAsByteArray());var repeated=json.readTree(replay.getContentAsByteArray());assertFalse(created.path("replayed").asBoolean());assertTrue(repeated.path("replayed").asBoolean());assertEquals(created.path("order").path("id"),repeated.path("order").path("id"));
        assertEquals(3500,created.path("order").path("priceInCentsSnapshot").asLong());
        var logout=mvc.perform(post("/api/users/logout").header("Authorization",buyer)).andReturn().getResponse();assertEquals(204,logout.getStatus());assertEquals(0,logout.getContentAsByteArray().length);
        assertTrue(op("/api/products/{id}/orders","post").path("responses").has(Integer.toString(first.getStatus())));
        assertTrue(op("/api/products/{id}/orders","post").path("responses").has(Integer.toString(replay.getStatus())));
    }
    String registerAndLogin(String name)throws Exception {
        String body=json.writeValueAsString(Map.of("username",name,"password","Campus2026!","nickname","契约验收"));
        assertEquals(201,mvc.perform(post("/api/users/register").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        var login=mvc.perform(post("/api/users/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","Campus2026!")))).andReturn().getResponse();assertEquals(200,login.getStatus());return "Bearer "+json.readTree(login.getContentAsByteArray()).path("accessToken").asString();
    }
    void assertReferencesResolve(JsonNode node){
        if(node.isObject()){
            if(node.has("$ref")){String ref=node.path("$ref").asString();assertTrue(ref.startsWith("#/"));assertFalse(api.at(ref.substring(1)).isMissingNode(),"Dangling reference: "+ref);}
            for(String property:node.propertyNames())assertReferencesResolve(node.get(property));
        } else if(node.isArray())for(JsonNode child:node)assertReferencesResolve(child);
    }
}
