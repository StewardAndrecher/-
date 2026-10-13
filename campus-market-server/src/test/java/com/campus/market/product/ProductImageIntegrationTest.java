package com.campus.market.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

// 只允许在临时测试库执行，绝不清理开发项目中的数据。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${LESSON24_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
        "spring.datasource.username=root", "spring.datasource.password=",
        "market.upload-dir=${LESSON24_UPLOAD_DIR}"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LESSON24_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson24_verify\\?.*")
class ProductImageIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductRepository repository;
    @Autowired private ProductService service;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private long sellerId;
    private long otherId;
    private long legacyId;
    private String token;
    private long productId;
    @Autowired private com.campus.market.media.ImageService images;
    @Autowired private org.springframework.core.env.Environment environment;
    private java.nio.file.Path directory() { return java.nio.file.Path.of(System.getenv("LESSON24_UPLOAD_DIR")); }
    private byte[] picture(String format, int width, int height) throws Exception {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.GREEN); graphics.fillRect(0, 0, width, height); graphics.dispose();
        var output = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, format, output)); return output.toByteArray();
    }
    private org.springframework.mock.web.MockMultipartFile file(byte[] bytes) {
        return new org.springframework.mock.web.MockMultipartFile("file", "../../unsafe.jsp", "text/plain", bytes);
    }
    private String upload(byte[] bytes) throws Exception {
        return check(multipart("/api/images").file(file(bytes)).header("Authorization", "Bearer " + token),201).get("id").asText();
    }
    private long imageCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM product_images",Long.class); }
    private long fileCount() throws Exception { try(var files=java.nio.file.Files.list(directory())){return files.count();} }
    private String body(String id) { return mapper.writeValueAsString(Map.of("title","封面测试台灯","description","测试","priceInCents",1250,"coverImageId",id)); }


    private JsonNode check(org.springframework.test.web.servlet.RequestBuilder request, int status) throws Exception {
        var response = mvc.perform(ProductTestRequests.withCurrentVersion(request, jdbc)).andReturn().getResponse();
        assertEquals(status, response.getStatus(), "接口状态码不符合预期");
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(text.contains("password"));
        assertFalse(text.contains("token_hash"));
        return text.isBlank() ? null : mapper.readTree(text);
    }

    private String login() throws Exception {
        return check(post("/api/users/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of(
                        "username", "owner_01", "password", "Campus2026!"))), 200)
                .get("accessToken").asText();
    }

    private MockHttpServletRequestBuilder publish(String title) {
        return post("/api/products").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of(
                        "title", title, "description", "测试商品", "priceInCents", 3500)));
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
    }

    @BeforeEach
    void prepareIsolatedFixtures() throws Exception {
        String url = System.getenv("LESSON24_DB_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson24_verify\\?.*"));
        assertTrue(directory().getParent().getFileName().toString().startsWith("campus-lesson24-"));
        java.nio.file.Files.createDirectories(directory());
        try (var files = java.nio.file.Files.list(directory())) {
            for (var file : files.toList()) java.nio.file.Files.delete(file);
        }
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM login_sessions");
        jdbc.update("DELETE FROM users");
        var a = check(post("/api/users/register").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", "owner_01",
                        "password", "Campus2026!", "nickname", "测试卖家甲"))), 201);
        var b = check(post("/api/users/register").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", "owner_02",
                        "password", "Campus2026!", "nickname", "测试卖家乙"))), 201);
        sellerId = a.get("id").asLong(); otherId = b.get("id").asLong();
        jdbc.update("INSERT INTO products(title,description,price_in_cents) VALUES(?,?,?)",
                "历史台灯", "迁移前商品", 900);
        legacyId = jdbc.queryForObject("SELECT MAX(id) FROM products", Long.class);
        token = login();
        productId = check(publish("待编辑台灯").header("Authorization", "Bearer " + token), 201).get("id").asLong();
    }

    @Test
    void requiresLoginAndRejectsInvalidFiles() throws Exception {
        check(multipart("/api/images").file(file(picture("png",40,30))),401);
        for(byte[] bad:new byte[][]{new byte[0],"<svg></svg>".getBytes(),picture("gif",10,10),picture("png",2001,1),new byte[5*1024*1024+1]}) {
            check(multipart("/api/images").file(file(bad)).header("Authorization","Bearer "+token),400);
        }
        assertEquals(0,imageCount());assertEquals(0,fileCount());
    }
    @Test
    void verifiesActualPngContentAndIgnoresOriginalFilename() throws Exception {
        String id=upload(picture("png",40,30));
        assertTrue(id.matches("[0-9a-f-]{36}"));assertEquals(1,imageCount());assertEquals(1,fileCount());
        assertTrue(java.nio.file.Files.exists(directory().resolve(id+".png")));
        var response=mvc.perform(get("/api/images/"+id)).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("image/png",response.getContentType());
        assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.getContentAsByteArray()));
        assertEquals(40,decoded.getWidth());assertEquals(30,decoded.getHeight());
        var rebuilt=new com.campus.market.media.ImageService(new com.campus.market.media.ImageRepository(jdbc),
                new com.campus.market.auth.SessionService(new com.campus.market.auth.SessionRepository(jdbc)),directory().toString());
        assertTrue(rebuilt.read(id).exists());
    }
    @Test
    void convertsJpegToPngAndSupportsImageCors() throws Exception {
        String id=upload(picture("jpg",20,15));
        var response=mvc.perform(get("/api/images/"+id)).andReturn().getResponse();
        byte[] bytes=response.getContentAsByteArray();assertEquals((byte)0x89,bytes[0]);assertEquals((byte)'P',bytes[1]);
        var cors=mvc.perform(options("/api/images").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization")).andReturn().getResponse();
        assertEquals(200,cors.getStatus());
    }
    @Test
    void attachesOwnImageAndCannotUseAnotherSellersImage() throws Exception {
        String id=upload(picture("png",20,15));
        var created=check(post("/api/products").header("Authorization","Bearer "+token).contentType("application/json").content(body(id)),201);
        assertEquals(id,created.get("coverImageId").asText());assertEquals("/api/images/"+id,created.get("coverImageUrl").asText());
        String other=check(post("/api/users/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username","owner_02","password","Campus2026!"))),200).get("accessToken").asText();
        check(post("/api/products").header("Authorization","Bearer "+other).contentType("application/json").content(body(id)),403);
        check(post("/api/products").header("Authorization","Bearer "+token).contentType("application/json").content(body(java.util.UUID.randomUUID().toString())),400);
        check(post("/api/products").header("Authorization","Bearer "+token).contentType("application/json").content(body("../file")),400);
        assertEquals(3,count());
    }
    @Test
    void updatesPreservesAndRemovesCoverWithoutChangingStatus() throws Exception {
        String id=upload(picture("png",20,15));
        var updated=check(put("/api/products/"+productId).header("Authorization","Bearer "+token).contentType("application/json").content(body(id)),200);
        assertEquals(id,updated.get("coverImageId").asText());
        var legacy=check(put("/api/products/"+productId).header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"title\":\"旧请求更新台灯\",\"priceInCents\":3500}"),200);
        assertEquals(id,legacy.get("coverImageId").asText());
        check(put("/api/products/"+productId+"/status").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"status\":\"OFF_SHELF\"}"),200);
        var removed=check(put("/api/products/"+productId).header("Authorization","Bearer "+token).contentType("application/json").content(body("")),200);
        assertTrue(removed.get("coverImageId").isNull());assertTrue(removed.get("coverImageUrl").isNull());
        assertEquals("OFF_SHELF",removed.get("status").asText());assertEquals(1,imageCount());assertEquals(2,count());
    }
    @Test
    void rollbackRemovesFileAndImageRow() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        byte[] bytes=picture("png",20,15);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(state->{
            images.upload(file(bytes),"Bearer "+token);throw new IllegalStateException("模拟后续步骤失败");
        }));
        assertEquals(0,imageCount());assertEquals(0,fileCount());
    }
    @Test
    void missingOrInvalidImageReturns404() throws Exception {
        check(get("/api/images/not-an-id"),404);
        check(get("/api/images/"+java.util.UUID.randomUUID()),404);
        String id=upload(picture("png",20,15));java.nio.file.Files.delete(directory().resolve(id+".png"));
        check(get("/api/images/"+id),404);
    }
    @Test
    void expiredSessionCannotUpload() throws Exception {
        jdbc.update("UPDATE login_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");
        check(multipart("/api/images").file(file(picture("png",20,15))).header("Authorization","Bearer "+token),401);
        assertEquals(0,imageCount());assertEquals(0,fileCount());
    }
    @Test
    void realHttpUploadLimitReturns413() throws Exception {
        String boundary="lesson24boundary";
        var bytes=new java.io.ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(new byte[5*1024*1024+1]);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        int port=environment.getProperty("local.server.port",Integer.class);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/images"))
                .header("Content-Type","multipart/form-data; boundary="+boundary).header("Authorization","Bearer "+token)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        var response=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
                .send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(413,response.statusCode());assertEquals(0,imageCount());
    }
}
