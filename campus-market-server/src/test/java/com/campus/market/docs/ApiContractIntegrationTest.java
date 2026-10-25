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
    @Test void notificationFiltersDocumentPrivateCategoryAndReadIntersection()throws Exception {
        var operation=op("/api/notifications","get");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        var enums=new HashMap<String,Set<String>>();for(var parameter:operation.path("parameters")){var values=new HashSet<String>();for(var value:parameter.path("schema").path("enum"))values.add(value.asString());enums.put(parameter.path("name").asString(),values);}
        assertEquals(Set.of("ALL","ORDER","REPORT","MODERATION"),enums.get("category"));assertEquals(Set.of("ALL","UNREAD","READ"),enums.get("status"));assertTrue(operation.path("description").asString().contains("REPEATABLE_READ"));assertTrue(operation.path("description").asString().contains("独立查询"));
        assertEquals(401,mvc.perform(get("/api/notifications").param("category","REPORT").param("status","READ")).andReturn().getResponse().getStatus());
    }
    @Test void lookupDocumentsBoundedPublicReadOnlyPost()throws Exception {
        var operation=op("/api/products/lookup","post");assertTrue(operation.path("security").isEmpty());assertTrue(operation.path("responses").has("200"));assertFalse(operation.path("responses").has("201"));
        var body=operation.at("/requestBody/content/application~1json/schema");var ids=body.at("/properties/productIds");assertEquals(1,ids.path("minItems").asInt());assertEquals(20,ids.path("maxItems").asInt());assertTrue(ids.path("uniqueItems").asBoolean());assertFalse(body.path("additionalProperties").asBoolean());
        assertTrue(operation.path("description").asString().contains("浏览时间"));assertEquals(200,mvc.perform(post("/api/products/lookup").contentType("application/json").content("{\"productIds\":[9007199254740991]}" )).andReturn().getResponse().getStatus());
    }
    @Test void sellerCategoryDocumentsPublicIntersectionAndEmptyDefault()throws Exception {
        var operation=op("/api/sellers/{id}/products","get");assertTrue(operation.path("security").isMissingNode()||operation.path("security").isEmpty());JsonNode category=null;
        for(var p:operation.path("parameters"))if(p.path("name").asString().equals("category"))category=p;
        assertNotNull(category);assertFalse(category.path("required").asBoolean());assertEquals("",category.path("schema").path("default").asString());
        var enums=new HashSet<String>();for(var value:category.path("schema").path("enum"))enums.add(value.asString());assertEquals(Set.of("","UNCLASSIFIED","BOOK","DIGITAL","DAILY","SPORT","OTHER"),enums);
        assertTrue(operation.path("description").asString().contains("REPEATABLE_READ"));assertTrue(operation.path("description").asString().contains("ON_SALE"));
    }
    @Test void favoriteSearchDocumentsBoundVisibleTitleQuery()throws Exception {
        var operation=op("/api/favorites","get");JsonNode keyword=null;
        for(var p:operation.path("parameters"))if(p.path("name").asString().equals("keyword"))keyword=p;
        assertNotNull(keyword);assertEquals(50,keyword.path("schema").path("maxLength").asInt());
        assertEquals("",keyword.path("schema").path("default").asString());assertFalse(keyword.path("required").asBoolean());
        assertTrue(operation.path("description").asString().contains("已失效商品不参与标题搜索"));
        assertEquals(401,mvc.perform(get("/api/favorites").param("keyword","台灯")).andReturn().getResponse().getStatus());
    }
    @Test void favoriteAvailabilityDocumentsPrivateNullProjection()throws Exception {
        var operation=op("/api/favorites","get");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        JsonNode parameter=null;for(var p:operation.path("parameters"))if(p.path("name").asString().equals("availability"))parameter=p;
        assertNotNull(parameter);var values=new HashSet<String>();for(var value:parameter.path("schema").path("enum"))values.add(value.asString());assertEquals(Set.of("ALL","AVAILABLE","UNAVAILABLE"),values);assertEquals("ALL",parameter.path("schema").path("default").asString());
        assertTrue(operation.path("description").asString().contains("REPEATABLE_READ"));assertTrue(operation.path("description").asString().contains("product:null"));
        assertEquals(401,mvc.perform(get("/api/favorites").param("availability","UNAVAILABLE")).andReturn().getResponse().getStatus());
    }
    @Test void favoriteBatchDocumentsExplicitBoundedPrivateMutation()throws Exception {
        var operation=op("/api/favorites/remove-batch","post");assertTrue(operation.path("security").get(0).has("bearerAuth"));assertTrue(operation.path("responses").has("200"));assertFalse(operation.path("responses").has("201"));assertTrue(operation.path("description").asString().contains("READ_COMMITTED"));
        var ids=operation.at("/requestBody/content/application~1json/schema/properties/productIds");assertEquals(1,ids.path("minItems").asInt());assertEquals(20,ids.path("maxItems").asInt());assertTrue(ids.path("uniqueItems").asBoolean());
        assertEquals(Set.of("userId","productIds","removedCount"),new HashSet<>(schema("FavoriteBatchModels.Receipt").path("properties").propertyNames()));
        assertEquals(401,mvc.perform(post("/api/favorites/remove-batch").contentType("application/json").content("{\"productIds\":[1]}" )).andReturn().getResponse().getStatus());
    }
    @Test void receivedReviewsDocumentPrivatePeerScopeAndStrictFilters()throws Exception {
        var operation=op("/api/orders/received-reviews","get");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        assertTrue(operation.path("responses").has("503"));assertTrue(operation.path("description").asString().contains("REPEATABLE_READ"));
        var names=new HashSet<String>();for(var parameter:operation.path("parameters"))names.add(parameter.path("name").asString());assertEquals(Set.of("role","rating","page","size"),names);
        assertEquals(Set.of("orderId","productTitleSnapshot","rating","content","completedAt","reviewedAt"),new HashSet<>(schema("ReceivedReviewService.Entry").path("properties").propertyNames()));
        assertEquals(401,mvc.perform(get("/api/orders/received-reviews")).andReturn().getResponse().getStatus());
    }
    @Test void sessionListDocumentsPrivateMinimalPagination()throws Exception {
        var operation=op("/api/users/me/sessions/active","get");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));
        assertTrue(operation.path("responses").has("400")&&operation.path("responses").has("401"));
        assertTrue(operation.path("description").asString().contains("REPEATABLE_READ"));
        var names=new HashSet<String>();for(var parameter:operation.path("parameters"))names.add(parameter.path("name").asString());assertEquals(Set.of("page","size"),names);
        assertEquals(401,mvc.perform(get("/api/users/me/sessions/active")).andReturn().getResponse().getStatus());
    }
    @Test void includesExactlyAllBusinessRoutesAndUniqueOperationIds(){
        Set<String> expected=new TreeSet<>(),actual=new TreeSet<>(),ids=new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping,handler)->{
            String pkg=handler.getBeanType().getPackageName();if(!pkg.startsWith("com.campus.market")||pkg.contains(".health")||pkg.contains(".monitoring"))return;
            for(String path:mapping.getPatternValues())if(path.startsWith("/api/")&&!path.startsWith("/api/ops/"))for(var method:mapping.getMethodsCondition().getMethods()){
                expected.add(method.name().toLowerCase()+" "+path);
                boolean authorization=Arrays.stream(handler.getMethodParameters()).anyMatch(p->{var h=p.getParameterAnnotation(org.springframework.web.bind.annotation.RequestHeader.class);return h!=null&&"Authorization".equals(h.name());});
                if(!authorization && !path.startsWith("/api/admin/"))assertTrue(op(path,method.name().toLowerCase()).path("security").isEmpty(),"Public route incorrectly requires auth: "+path);
            }
        });
        var paths=api.path("paths");for(String p:paths.propertyNames())for(String method:paths.path(p).propertyNames()){
            JsonNode operation=paths.path(p).path(method);actual.add(method+" "+p);assertTrue(ids.add(operation.path("operationId").asString()));assertFalse(operation.path("tags").isEmpty());assertTrue(operation.path("summary").asString().contains("："));
            for(JsonNode parameter:operation.path("parameters"))assertNotEquals("Authorization",parameter.path("name").asString());
        }
        assertFalse(expected.isEmpty());assertEquals(expected,actual);assertReferencesResolve(api);
    }
    @Test void moderationEndpointsArePrivateAndDocumentConflictAndIdempotency()throws Exception {
        for(String path:List.of("/api/admin/products","/api/admin/products/{id}/moderation","/api/admin/products/{id}/moderation/actions","/api/products/{id}/moderation","/api/products/{id}/moderation/appeals")) {
            String method=path.endsWith("actions")||path.endsWith("appeals")?"post":"get";
            assertTrue(op(path,method).path("security").get(0).has("bearerAuth"));assertTrue(op(path,method).path("responses").has("401"));
        }
        assertTrue(op("/api/admin/products/{id}/moderation/actions","post").path("responses").has("409"));
        assertTrue(op("/api/admin/products/{id}/moderation/actions","post").path("description").asString().contains("requestId"));
        assertEquals(401,mvc.perform(get("/api/admin/products")).andReturn().getResponse().getStatus());
    }
    @Test void administratorEndpointsRequireBearerAndDocumentForbidden()throws Exception {
        var admin=op("/api/admin/me","get");assertTrue(admin.path("security").get(0).has("bearerAuth"));assertTrue(admin.path("responses").has("401")&&admin.path("responses").has("403"));
        assertTrue(op("/api/users/me/permissions","get").path("security").get(0).has("bearerAuth"));
        assertEquals(401,mvc.perform(get("/api/admin/me")).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/users/me/permissions")).andReturn().getResponse().getStatus());
    }
    @Test void feedIsPublicAndDocumentsCursorInsteadOfTotalCount()throws Exception {
        assertTrue(op("/api/products/feed","get").path("security").isEmpty());
        assertTrue(op("/api/products/feed","get").path("description").asString().contains("nextCursor"));
        var shape=schema("ProductFeedResponse").path("properties");assertTrue(shape.has("records")&&shape.has("hasMore")&&shape.has("nextCursor"));assertFalse(shape.has("total"));
        assertEquals(200,mvc.perform(get("/api/products/feed")).andReturn().getResponse().getStatus());
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
    @Test void reportEndpointsHaveBearerReplayAndConflictContracts()throws Exception {
        for(String path:List.of("/api/products/{id}/reports","/api/reports","/api/reports/{id}","/api/admin/reports","/api/admin/reports/{id}","/api/admin/reports/{id}/resolve")) {
            String verb=path.endsWith("/resolve")||path.equals("/api/products/{id}/reports")?"post":"get";
            var operation=op(path,verb);assertFalse(operation.path("security").isEmpty());assertTrue(operation.path("responses").has("401"));
            if(verb.equals("post"))assertTrue(operation.path("responses").has("409"));
        }
        assertTrue(op("/api/products/{id}/reports","post").path("responses").has("201"));assertTrue(op("/api/products/{id}/reports","post").path("responses").has("200"));
    }
    @Test void notificationEndpointsArePrivateAndHaveOwnershipErrors()throws Exception {
        for(String path:List.of("/api/notifications","/api/notifications/unread-count","/api/notifications/{id}/read")){
            String method=path.endsWith("/read")?"post":"get";var operation=op(path,method);assertFalse(operation.path("security").isEmpty());assertTrue(operation.path("responses").has("401"));assertTrue(operation.path("responses").has("200"));
        }
        assertTrue(op("/api/notifications/{id}/read","post").path("responses").has("404"));
    }
    @Test void notificationOverviewRequiresAdminAndDocumentsSampling()throws Exception {
        var operation=op("/api/admin/notifications/overview","get");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));
        assertTrue(operation.path("responses").has("401")&&operation.path("responses").has("403"));
        assertTrue(operation.path("description").asString().contains("聚合采样"));
        assertEquals(401,mvc.perform(get("/api/admin/notifications/overview")).andReturn().getResponse().getStatus());
    }
    @Test void notificationReadBatchDocumentsBoundedAtomicOwnership()throws Exception {
        var operation=op("/api/notifications/read-batch","post");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));assertTrue(operation.path("responses").has("404"));
        assertTrue(operation.path("description").asString().contains("整批404"));
        var ids=schema("NotificationModels.ReadBatch").path("properties").path("ids");assertEquals(1,ids.path("minItems").asInt());assertEquals(20,ids.path("maxItems").asInt());assertTrue(ids.path("uniqueItems").asBoolean());
        assertEquals(401,mvc.perform(post("/api/notifications/read-batch").contentType("application/json").content(json.writeValueAsString(Map.of("ids",List.of(1))))).andReturn().getResponse().getStatus());
    }
    @Test void orderOverviewDocumentsPrivateSimulationAndReadOnlyAggregation()throws Exception {
        var operation=op("/api/admin/orders/overview","get");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));
        for(String status:List.of("200","401","403","500"))assertTrue(operation.path("responses").has(status));
        assertEquals("交易运营：交易运营概览",operation.path("summary").asString());
        for(String text:List.of("聚合采样","模拟交易金额","查询不关闭订单","不是真实收款"))assertTrue(operation.path("description").asString().contains(text));
        assertEquals(401,mvc.perform(get("/api/admin/orders/overview")).andReturn().getResponse().getStatus());
    }
    @Test void adminOrderQueryDocumentsFiltersPrivacyAndSnapshot()throws Exception {
        var operation=op("/api/admin/orders","get");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));
        for(String code:List.of("200","401","403","500"))assertTrue(operation.path("responses").has(code));
        for(var parameter:operation.path("parameters")) {
            if(parameter.path("name").asString().equals("status"))assertEquals(8,parameter.path("schema").path("enum").size());
            if(parameter.path("name").asString().equals("size"))assertEquals(20,parameter.path("schema").path("maximum").asInt());
        }
        for(String text:List.of("OVERDUE_PENDING","精确匹配","REPEATABLE_READ","不关闭订单","不包含参与者"))assertTrue(operation.path("description").asString().contains(text));
        assertEquals(401,mvc.perform(get("/api/admin/orders")).andReturn().getResponse().getStatus());
    }
    @Test void adminOrderDetailDocumentsRolesHistoryAndUnavailableData()throws Exception {
        var operation=op("/api/admin/orders/{id}","get");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));
        for(String code:List.of("200","401","403","404","503"))assertTrue(operation.path("responses").has(code));
        for(String text:List.of("REPEATABLE_READ","HISTORY_SNAPSHOT","不返回参与者","不伪造事件"))assertTrue(operation.path("description").asString().contains(text));
        assertEquals(401,mvc.perform(get("/api/admin/orders/1")).andReturn().getResponse().getStatus());
    }
    @Test void sessionManagementDocumentsOwnScopeAndNoCredentialExposure()throws Exception {
        for(String path:List.of("/api/users/me/sessions","/api/users/me/sessions/revoke-others")) {
            String method=path.endsWith("revoke-others")?"post":"get";var operation=op(path,method);
            assertTrue(operation.path("security").get(0).has("bearerAuth"));assertTrue(operation.path("responses").has("401"));
            for(String text:List.of("无客户端userId","保留当前会话","不返回令牌","账号锁"))assertTrue(operation.path("description").asString().contains(text));
        }
        assertEquals(401,mvc.perform(get("/api/users/me/sessions")).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(post("/api/users/me/sessions/revoke-others")).andReturn().getResponse().getStatus());
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

    @Test void passwordChangeDocumentsSessionRevocationAndWriteOnlyInput()throws Exception {
        var operation=op("/api/users/me/password","post");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        for(String status:List.of("200","400","401","403","429","503"))assertTrue(operation.path("responses").has(status));
        assertFalse(operation.path("responses").has("409"));assertTrue(operation.path("description").asString().contains("所有有效会话"));
        var input=schema("PasswordChangeRequest");assertTrue(input.at("/properties/currentPassword/writeOnly").asBoolean());assertTrue(input.at("/properties/newPassword/writeOnly").asBoolean());
        assertEquals(401,mvc.perform(post("/api/users/me/password").contentType("application/json").content("{\"currentPassword\":\"Old2026!\",\"newPassword\":\"New2027!\"}")).andReturn().getResponse().getStatus());
    }

    @Test void orderReviewsDocumentParticipantPrivacyAndIdempotentCreation()throws Exception {
        for(String method:List.of("get","post")){
            var operation=op("/api/orders/{id}/reviews",method);assertTrue(operation.path("security").get(0).has("bearerAuth"));
            for(String status:List.of("200","400","401","404","503"))assertTrue(operation.path("responses").has(status));
            assertTrue(operation.path("description").asString().contains("仅订单双方可见"));
        }
        assertTrue(op("/api/orders/{id}/reviews","post").path("responses").has("201"));assertTrue(op("/api/orders/{id}/reviews","post").path("responses").has("409"));
        assertEquals(401,mvc.perform(get("/api/orders/1/reviews")).andReturn().getResponse().getStatus());
    }
    @Test void reviewNotificationContractDescribesQueuedPrivateDelivery(){
        String text=op("/api/orders/{id}/reviews","post").path("description").asString();
        for(String part:List.of("Outbox同一事务","重放不重复提醒","不回填历史评价","不含评分/文字/对方身份","ORDER分类"))assertTrue(text.contains(part),part);
    }

    @Test void reviewTaskContractDocumentsOwnScopeSnapshotAndReadOnly()throws Exception {
        var operation=op("/api/orders/review-tasks","get");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        for(String status:List.of("200","400","401","500","503"))assertTrue(operation.path("responses").has(status));
        for(String part:List.of("本人记录","SQL筛选和分页","REPEATABLE_READ","不返回对方身份","no-store"))assertTrue(operation.path("description").asString().contains(part));
        assertEquals(401,mvc.perform(get("/api/orders/review-tasks")).andReturn().getResponse().getStatus());
    }

    @Test void orderIssueContractDocumentsOwnScopeReplayAndNoRefund()throws Exception {
        for(String method:List.of("get","post")){
            var operation=op("/api/orders/{id}/issue",method);assertTrue(operation.path("security").get(0).has("bearerAuth"));
            for(String status:List.of("200","400","401","404","503"))assertTrue(operation.path("responses").has(status));
            for(String part:List.of("本人反馈","首次201","同内容重放200","不触发退款","管理员身份和处理幂等键不返回"))assertTrue(operation.path("description").asString().contains(part));
        }
        assertTrue(op("/api/orders/{id}/issue","post").path("responses").has("201"));assertTrue(op("/api/orders/{id}/issue","post").path("responses").has("409"));
        assertEquals(401,mvc.perform(get("/api/orders/1/issue")).andReturn().getResponse().getStatus());
    }

    @Test void issueResolutionContractDocumentsAdminPermissionReplayAndAtomicNotice()throws Exception {
        for(String path:List.of("/api/admin/order-issues","/api/admin/order-issues/{orderId}/{applicantId}","/api/admin/order-issues/{orderId}/{applicantId}/resolve")){
            String method=path.endsWith("resolve")?"post":"get";var operation=op(path,method);
            assertTrue(operation.path("security").get(0).has("bearerAuth"));
            for(String status:List.of("200","400","401","403","503"))assertTrue(operation.path("responses").has(status));
            for(String text:List.of("禁止处理本人参与","Outbox同一事务","仅提醒提交人","不执行退款"))assertTrue(operation.path("description").asString().contains(text));
        }
        assertTrue(op("/api/admin/order-issues/{orderId}/{applicantId}/resolve","post").path("responses").has("409"));
        assertEquals(401,mvc.perform(get("/api/admin/order-issues")).andReturn().getResponse().getStatus());
    }

    @Test void ownIssueListContractIsSessionScopedReadOnlyAndMinimal()throws Exception {
        var operation=op("/api/orders/issues","get");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        for(String code:List.of("200","400","401","503"))assertTrue(operation.path("responses").has(code));
        for(String part:List.of("本人已提交","ALL/OPEN/RESOLVED","REPEATABLE_READ","不返回反馈正文","no-store"))assertTrue(operation.path("description").asString().contains(part));
        assertEquals(401,mvc.perform(get("/api/orders/issues")).andReturn().getResponse().getStatus());
    }
    @Test void issueOverviewAndAgingContractDocumentPrivateClockAndSubset()throws Exception {
        assertEquals(Set.of("sampledAt","total","open","resolved","answered","dismissed","openOver24h","openOver72h","oldestOpenAt"),new HashSet<>(schema("IssueOverview").path("properties").propertyNames()));
        var overview=op("/api/admin/order-issues/overview","get");assertTrue(overview.path("security").get(0).has("bearerAuth"));
        for(String code:List.of("200","401","403","503"))assertTrue(overview.path("responses").has(code));
        for(String text:List.of("单条SQL","72小时是24小时子集","不含正文","no-store"))assertTrue(overview.path("description").asString().contains(text));
        var list=op("/api/admin/order-issues","get");for(String text:List.of("OVER_24H","仅OPEN","sampledAt","最早提交"))assertTrue(list.path("description").asString().contains(text));
        assertEquals(401,mvc.perform(get("/api/admin/order-issues/overview")).andReturn().getResponse().getStatus());
    }
    @Test void adminIssueSearchContractDocumentsExactSafeIdAndIntersection()throws Exception {
        var operation=op("/api/admin/order-issues","get");
        for(String text:List.of("9007199254740991","精确匹配","取交集","最多双方两条","不是身份或权限凭证"))assertTrue(operation.path("description").asString().contains(text));
        var parameter=java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(),false).filter(p->p.path("name").asString().equals("orderId")).findFirst().orElseThrow();
        assertEquals("string",parameter.at("/schema/type").asString());assertEquals(16,parameter.at("/schema/maxLength").asInt());
    }

    @Test void tradeTasksArePrivateAndExposeOnlyOwnAggregates()throws Exception {
        var operation=op("/api/orders/tasks/overview","get");
        assertTrue(operation.path("security").get(0).has("bearerAuth"));assertTrue(operation.path("responses").has("401"));assertTrue(operation.path("responses").has("503"));
        assertTrue(operation.path("description").asString().contains("本人"));assertTrue(operation.path("description").asString().contains("读取不关闭订单"));
        assertEquals(Set.of("userId","sampledAt","buyer","seller"),new HashSet<>(schema("OwnTradeTaskOverview").path("properties").propertyNames()));
        assertEquals(Set.of("pendingPayment","payable","overduePendingPayment","toConfirm","toReview"),new HashSet<>(schema("BuyerTradeTasks").path("properties").propertyNames()));
        assertEquals(Set.of("toDeliver","toReview"),new HashSet<>(schema("SellerTradeTasks").path("properties").propertyNames()));
        assertEquals(401,mvc.perform(get("/api/orders/tasks/overview")).andReturn().getResponse().getStatus());
    }

    @Test void notificationTargetsAreBoundedKindsWithoutSourceOrExternalUrl() {
        var properties=schema("NotificationModels.Notice").path("properties");
        assertTrue(properties.has("target"));assertFalse(properties.has("sourceKey"));assertFalse(properties.has("recipientId"));assertFalse(properties.has("url"));
        Set<String> values=new HashSet<>();properties.path("target").path("enum").forEach(v->values.add(v.asString()));
        assertEquals(Set.of("ORDER_DETAIL","ORDER_REVIEWS","OWN_ORDER_ISSUE","REPORT_DETAIL","PRODUCT_MODERATION"),values);
        assertTrue(op("/api/notifications","get").path("description").asString().contains("target"));
        assertTrue(op("/api/notifications/{id}/read","post").path("description").asString().contains("target"));
        assertTrue(op("/api/notifications/read-batch","post").path("description").asString().contains("target"));
    }

    @Test void ownOrderSearchDocumentsPrivateFilterAndWholeRoleSummary(){
        var operation=op("/api/orders","get");assertTrue(operation.path("security").get(0).has("bearerAuth"));
        JsonNode parameter=null;for(var item:operation.path("parameters"))if(item.path("name").asString().equals("orderId"))parameter=item;
        assertNotNull(parameter);assertEquals("string",parameter.path("schema").path("type").asString());assertEquals(16,parameter.path("schema").path("maxLength").asInt());
        assertTrue(operation.path("description").asString().contains("他人订单均200空列表"));assertTrue(operation.path("description").asString().contains("summary始终"));
        assertTrue(schema("OrderModels.Page").path("properties").has("orderId"));
    }
    @Test void ownOrderExportDocumentsPrivateBoundedSnapshot()throws Exception {
        var e=op("/api/orders/export","get");assertTrue(e.path("security").get(0).has("bearerAuth"));assertTrue(e.path("responses").has("401"));assertTrue(e.path("responses").has("413"));assertTrue(e.path("description").asString().contains("200"));
        assertEquals(Set.of("role","status","orderId","total","limit","records"),new HashSet<>(schema("OrderExportModels.Snapshot").path("properties").propertyNames()));
        assertEquals(Set.of("id","productTitleSnapshot","priceInCentsSnapshot","status","createdAt"),new HashSet<>(schema("OrderExportModels.Row").path("properties").propertyNames()));
        assertEquals(401,mvc.perform(get("/api/orders/export")).andReturn().getResponse().getStatus());
    }
    @Test void registrationDocumentsRateLimitAndUnavailableWithoutRequiringLogin(){
        var registration=op("/api/users/register","post");assertTrue(registration.path("security").isEmpty());
        for(String code:List.of("429","503"))assertTrue(registration.path("responses").path(code).path("headers").has("Retry-After"));
        assertTrue(registration.path("description").asString().contains("Lua"));assertTrue(registration.path("description").asString().contains("本次未创建账号")||registration.path("responses").path("503").path("description").asString().contains("本次未创建账号"));
    }

    @Test void tradeCapabilitiesArePublicAndDoNotPromiseRealPayment()throws Exception {
        assertTrue(op("/api/trade-capabilities","get").path("security").isEmpty());
        var response=mvc.perform(get("/api/trade-capabilities")).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));
        assertEquals("SIMULATED",json.readTree(response.getContentAsByteArray()).get("paymentMode").asString());
    }
}
