package com.campus.market.experience;
import com.campus.market.auth.*;
import com.campus.market.user.*;
import com.campus.market.product.*;
import com.campus.market.order.*;
import com.campus.market.publication.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.notifications.mq.enabled=false","market.orders.timeout-sweep-enabled=false","market.orders.simulated-payment-enabled=true","springdoc.api-docs.enabled=false","market.governance.rate-enabled=true","market.email.otp-key=isolated-email-test-key-with-48-characters-only"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class PlatformGovernanceIntegrationTest {
 static Connection control;static String schema;
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
  String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
  try(var st=control.createStatement();var row=st.executeQuery("SELECT @@datadir")){assertTrue(row.next());assertTrue(row.getString(1).contains("campus-lesson24-"));}
  schema="campus_lesson130_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  try(var st=control.createStatement()){st.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
  registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
  registry.add("market.upload-dir",()->System.getenv("LESSON24_UPLOAD_DIR"));
 }
 @AfterAll static void cleanup()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson130_case_[0-9a-f]{12}"))try(var st=control.createStatement()){st.execute("DROP DATABASE "+schema);}}finally{control.close();}}
 @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired SessionService sessions;@Autowired PasswordEncoder encoder;@Autowired ProductService products;@Autowired OrderService orders;@Autowired OrderRefundService refunds;@Autowired PublicationService publications;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 final JsonMapper json=JsonMapper.builder().build();long buyerId,sellerId,outsiderId;String buyer,seller,outsider,username;
 @org.springframework.test.context.bean.override.mockito.MockitoBean VerificationMail mail;
 String deliveredCode;
 @BeforeEach void prepare(){
  org.mockito.Mockito.reset(mail);org.mockito.Mockito.when(mail.available()).thenReturn(true);
  org.mockito.Mockito.doAnswer(call->{deliveredCode=call.getArgument(1);return null;}).when(mail).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);username="buyer_"+suffix;
  buyerId=user(username,"买家");sellerId=user("seller_"+suffix,"卖家");outsiderId=user("other_"+suffix,"其他同学");
  buyer=auth(buyerId);seller=auth(sellerId);outsider=auth(outsiderId);
 }
 long user(String name,String nickname){jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES(?,?,?)",name,encoder.encode("Campus2026!"),nickname);return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);}
 String auth(long id){return "Bearer "+sessions.issueToken(new UserResponse(id,"fixture_"+id,"隔离样本")).accessToken();}
 JsonNode check(RequestBuilder request,int expected)throws Exception {var response=mvc.perform(request).andReturn().getResponse();assertEquals(expected,response.getStatus(),response.getContentAsString());return response.getContentAsByteArray().length==0?null:json.readTree(response.getContentAsByteArray());}
 String body(Object value){return json.writeValueAsString(value);}
 JsonNode getAuth(String path,String auth,int expected)throws Exception {var request=get(path);if(auth!=null)request.header("Authorization",auth);return check(request,expected);}
 JsonNode postAuth(String path,Object value,String auth,int expected)throws Exception {var request=post(path).contentType("application/json").content(body(value));if(auth!=null)request.header("Authorization",auth);return check(request,expected);}
 JsonNode putAuth(String path,Object value,String auth,int expected)throws Exception {var request=put(path).contentType("application/json").content(body(value));if(auth!=null)request.header("Authorization",auth);return check(request,expected);}
 ProductResponse product(){return products.createProduct(new CreateProductRequest("隔离验收教材","完整资料和售后流程测试",3250L),seller);}
 long paidOrder(){var product=product();long id=orders.create(product.id(),new OrderModels.Create(product.version(),UUID.randomUUID().toString()),buyer).order().id();orders.simulatePayment(id,buyer);return id;}
 String picture(String auth)throws Exception {var picture=new java.awt.image.BufferedImage(40,30,java.awt.image.BufferedImage.TYPE_INT_RGB);var buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(picture,"png",buffer);return check(multipart("/api/images").file(new MockMultipartFile("file","fixture.png","image/png",buffer.toByteArray())).header("Authorization",auth),201).get("id").asString();}
 Map<String,Object> details(List<String> ids){return Map.of("campusCode","EAST","conditionCode","GOOD","handoverLocation","图书馆门口","imageIds",ids);}
 Map<String,Object> productBody(List<String> ids){return Map.of("title","多图隔离测试教材","description","保留全部资料","priceInCents",3500,"categoryCode","BOOK","coverImageId",ids.isEmpty()?"":ids.get(0),"details",details(ids));}
 Map<String,Object> refundBody(String request){return Map.of("requestId",request,"reason","NOT_AS_DESCRIBED","description","交接时发现商品状况与描述不符，希望协商处理。");}
 JsonNode request(long order)throws Exception{return postAuth("/api/orders/"+order+"/refunds",refundBody(UUID.randomUUID().toString()),buyer,201);}

 @Autowired com.campus.market.governance.GovernanceService governance;
 @Autowired com.campus.market.media.ImageLibraryService library;
 void admin(){jdbc.update("INSERT INTO user_role_grants(user_id,role_code) VALUES(?,'ADMIN')",outsiderId);}
 void cooldown(){jdbc.update("UPDATE account_email_challenges SET created_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 MINUTE) WHERE user_id=?",sellerId);}
 JsonNode challenge(String purpose,String address,String auth,int status)throws Exception{return postAuth("/api/users/email/challenges",Map.of("purpose",purpose,"email",address,"currentPassword","Campus2026!","username",username),auth,status);}
 String bind()throws Exception {String id=challenge("BIND","seller"+sellerId+"@example.test",seller,202).get("challengeId").asString();postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",deliveredCode),seller,200);return id;}
 @Test void bindingRequiresLoginAndCurrentPassword()throws Exception {
  challenge("BIND","seller@example.test",null,401);
  postAuth("/api/users/email/challenges",Map.of("purpose","BIND","email","seller@example.test","currentPassword","wrong"),seller,403);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM account_email_challenges WHERE user_id=?",Integer.class,sellerId));
 }
 @Test void bindingStoresOnlyDigestMasksEmailAndConsumesCode()throws Exception {
  var pending=challenge("BIND","seller"+sellerId+"@example.test",seller,202);String id=pending.get("challengeId").asString(),code=deliveredCode;
  String stored=jdbc.queryForObject("SELECT code_hash FROM account_email_challenges WHERE id=?",String.class,id);assertEquals(64,stored.length());assertFalse(stored.contains(code));
  var state=postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",code),seller,200);assertTrue(state.get("bound").asBoolean());assertEquals("s***@example.test",state.get("maskedEmail").asString());assertFalse(body(state).contains("seller"+sellerId+"@"));
  postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",code),seller,403);
 }
 @Test void codeAttemptsRemainConsumedAcrossRejectedRequests()throws Exception {
  String id=challenge("BIND","seller"+sellerId+"@example.test",seller,202).get("challengeId").asString(),code=deliveredCode;
  String wrong="00000000".equals(code)?"11111111":"00000000";
  for(int i=0;i<5;i++)postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",wrong),seller,403);
  assertEquals(5,jdbc.queryForObject("SELECT attempts FROM account_email_challenges WHERE id=?",Integer.class,id));postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",code),seller,403);
 }
 @Test void expiredAndOtherOwnerChallengesCannotBind()throws Exception {
  String id=challenge("BIND","seller"+sellerId+"@example.test",seller,202).get("challengeId").asString();String code=deliveredCode;
  postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",code),buyer,403);
  jdbc.update("UPDATE account_email_challenges SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",id);postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",code),seller,403);
 }
 @Test void bindingSendCooldownAndDailyCap()throws Exception {
  challenge("BIND","seller"+sellerId+"@example.test",seller,202);challenge("BIND","seller"+sellerId+"@example.test",seller,429);cooldown();
  for(int i=0;i<19;i++){jdbc.update("INSERT INTO account_email_challenges(id,user_id,purpose,email,binding_version,code_hash,created_at,expires_at) VALUES(?,?,'BIND','daily@example.test',-1,?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 HOUR),CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),sellerId,"0".repeat(64));}
  challenge("BIND","seller"+sellerId+"@example.test",seller,429);
 }
 @Test void replacingMailboxRequiresVerificationOfOldMailbox()throws Exception {
  bind();cooldown();challenge("BIND","replacement@example.test",seller,409);challenge("UNBIND","not-owned@example.test",seller,409);
  String id=challenge("UNBIND","seller"+sellerId+"@example.test",seller,202).get("challengeId").asString();var state=postAuth("/api/users/me/email/confirm",Map.of("challengeId",id,"code",deliveredCode),seller,200);assertFalse(state.get("bound").asBoolean());
 }
 @Test void duplicateVerifiedMailboxCannotReplaceAnotherAccount()throws Exception {
  bind();var challenge=challenge("BIND","seller"+sellerId+"@example.test",buyer,202);postAuth("/api/users/me/email/confirm",Map.of("challengeId",challenge.get("challengeId").asString(),"code",deliveredCode),buyer,409);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM account_email_bindings WHERE email=?",Integer.class,"seller"+sellerId+"@example.test"));
 }
 @Test void unknownRecoveryHasSameAcceptedShapeAndNeverSendsMail()throws Exception {
  var result=challenge("RECOVER","unknown@example.test",null,202);assertEquals(600,result.get("expiresInSeconds").asInt());org.mockito.Mockito.verify(mail,org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
 }
 @Test void emailRecoveryRevokesSessionsAndRecoveryCodesAndIsSingleUse()throws Exception {
  bind();cooldown();String account=jdbc.queryForObject("SELECT username FROM users WHERE id=?",String.class,sellerId);
  jdbc.update("INSERT INTO account_recovery_codes(user_id,code_hash) VALUES(?,?)",sellerId,"a".repeat(64));
  var pending=postAuth("/api/users/email/challenges",Map.of("purpose","RECOVER","username",account,"email","seller"+sellerId+"@example.test"),null,202);String code=deliveredCode;
  var body=Map.of("challengeId",pending.get("challengeId").asString(),"code",code,"username",account,"newPassword","ChangedCampus2026!");
  var result=postAuth("/api/users/recover-by-email",body,null,200);assertTrue(result.get("sessionsRevoked").asBoolean());getAuth("/api/users/me",seller,401);postAuth("/api/users/recover-by-email",body,null,403);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery_codes WHERE user_id=?",Integer.class,sellerId));assertTrue(encoder.matches("ChangedCampus2026!",jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,sellerId)));
 }
 @Test void unconfiguredMailIsUnavailableWithoutReturningTestCodes()throws Exception {
  org.mockito.Mockito.when(mail.available()).thenReturn(false);assertFalse(getAuth("/api/users/me/email",seller,200).get("deliveryAvailable").asBoolean());challenge("BIND","seller@example.test",seller,503);
 }
 @Test void mailFailureConsumesUnsentChallengeAndDoesNotBind()throws Exception {
  org.mockito.Mockito.doThrow(new com.campus.market.governance.PlatformException(503,"EMAIL_DELIVERY_UNAVAILABLE","不可用")).when(mail).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());challenge("BIND","seller@example.test",seller,503);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM account_email_challenges WHERE user_id=? AND consumed_at IS NOT NULL",Integer.class,sellerId));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM account_email_bindings WHERE user_id=?",Integer.class,sellerId));
 }
 @Test void publicProductsNeverExposeVerifiedEmail()throws Exception {bind();var product=product();assertFalse(body(getAuth("/api/products/"+product.id(),null,200)).contains("example.test"));}
 @Test void blockedTextIsCheckedOnDirectCreateAndPreflight()throws Exception {
  var data=new HashMap<>(productBody(List.of()));data.put("title","刷\u200b单返利教材");postAuth("/api/products",data,seller,422);postAuth("/api/products/quality-check",data,seller,422);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE seller_id=?",Integer.class,sellerId));
 }
 @Test void blockedCommentsAndProfileCannotBypassProductRules()throws Exception {
  var product=product();postAuth("/api/products/"+product.id()+"/comments",Map.of("content","代开发票","requestId",UUID.randomUUID().toString()),buyer,422);
  putAuth("/api/users/me/profile",Map.of("avatarImageId","","bio","银行卡四件套","campusCode","EAST","version",0),seller,422);
 }
 @Test void qualityCheckReturnsAdviceWithoutCreatingProduct()throws Exception {
  var checked=postAuth("/api/products/quality-check",productBody(List.of()),seller,200);assertTrue(checked.get("canPublish").asBoolean());assertFalse(checked.get("suggestions").isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE seller_id=?",Integer.class,sellerId));
 }
 @Test void restrictionRequiresAdminAndProtectsAdminAccounts()throws Exception {
  admin();putAuth("/api/admin/governance/accounts/"+sellerId,Map.of("action","RESTRICT","hours",24,"reason","隔离测试：核实异常发布","version",0),buyer,403);
  putAuth("/api/admin/governance/accounts/"+outsiderId,Map.of("action","RESTRICT","hours",24,"reason","隔离测试：核实异常发布","version",0),outsider,409);
 }
 void restrict()throws Exception {admin();putAuth("/api/admin/governance/accounts/"+sellerId,Map.of("action","RESTRICT","hours",24,"reason","隔离测试：多次虚假商品描述","version",0),outsider,200);}
 @Test void restrictedUserCannotPublishButKeepsAccountSafetyAndRecovery()throws Exception {
  restrict();postAuth("/api/products",productBody(List.of()),seller,403);getAuth("/api/users/me/email",seller,200);getAuth("/api/users/me/recovery",seller,200);getAuth("/api/users/me",seller,200);
  assertTrue(getAuth("/api/users/me/content-status",seller,200).get("restricted").asBoolean());
 }
 @Test void appealIsOnePerVersionAndAdminReleaseRestoresWrites()throws Exception {
  restrict();var appeal=Map.of("reason","隔离复核：请核对商品与发布情况","version",1);postAuth("/api/users/me/content-status/appeal",appeal,seller,200);postAuth("/api/users/me/content-status/appeal",appeal,seller,409);
  putAuth("/api/admin/governance/accounts/"+sellerId,Map.of("action","RELEASE","reason","隔离复核：已核实并解除限制","version",0),outsider,409);
  var state=putAuth("/api/admin/governance/accounts/"+sellerId,Map.of("action","RELEASE","reason","隔离复核：已核实并解除限制","version",1),outsider,200);assertFalse(state.get("restricted").asBoolean());assertEquals(3,state.get("events").size());postAuth("/api/products",productBody(List.of()),seller,201);
 }
 @Test void expiredRestrictionDoesNotBlockAndWriteWindowIsEnforced()throws Exception {
  restrict();jdbc.update("UPDATE account_write_restrictions SET restricted_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE user_id=?",sellerId);
  product();jdbc.update("UPDATE public_write_windows SET uses=30 WHERE user_id=? AND action='PRODUCT'",sellerId);postAuth("/api/products",productBody(List.of()),seller,429);
 }
 @Test void imageLibraryChecksOwnerAndRecordsActualBytes()throws Exception {
  String image=picture(seller);var list=getAuth("/api/users/me/images",seller,200);assertEquals(1,list.get("items").size());assertTrue(list.at("/items/0/sizeKnown").asBoolean());assertTrue(list.at("/items/0/bytes").asLong()>0);
  putAuth("/api/users/me/images/"+image+"/trash",Map.of("version",0),buyer,404);
 }
 @Test void trashRestoreUsesVersionsAndPublicReadsStopWhileTrashed()throws Exception {
  String image=picture(seller);putAuth("/api/users/me/images/"+image+"/trash",Map.of("version",0),seller,204);getAuth("/api/images/"+image,null,404);
  var trash=getAuth("/api/users/me/images?scope=TRASH",seller,200);assertEquals(1,trash.get("total").asInt());putAuth("/api/users/me/images/"+image+"/restore",Map.of("version",0),seller,409);putAuth("/api/users/me/images/"+image+"/restore",Map.of("version",1),seller,204);
  assertEquals(0,getAuth("/api/users/me/images?scope=TRASH",seller,200).get("total").asInt());assertEquals(200,mvc.perform(get("/api/images/"+image)).andReturn().getResponse().getStatus());
 }
 @Test void galleryAndAvatarReferencesProtectImages()throws Exception {
  String image=picture(seller);postAuth("/api/products",productBody(List.of(image)),seller,201);putAuth("/api/users/me/images/"+image+"/trash",Map.of("version",0),seller,409);
  String avatar=picture(seller);putAuth("/api/users/me/profile",Map.of("avatarImageId",avatar,"bio","隔离测试头像","campusCode","EAST","version",0),seller,200);putAuth("/api/users/me/images/"+avatar+"/trash",Map.of("version",0),seller,409);
 }
 @Test void legacyImageSizesAreConservativelyChargedAndQuotaChecked()throws Exception {
  for(int i=0;i<50;i++)jdbc.update("INSERT INTO product_images(id,owner_id,width,height) VALUES(?,?,40,30)",UUID.randomUUID().toString(),sellerId);
  var image=new java.awt.image.BufferedImage(40,30,java.awt.image.BufferedImage.TYPE_INT_RGB);var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);
  check(multipart("/api/images").file(new MockMultipartFile("file","test.png","image/png",output.toByteArray())).header("Authorization",seller),413);
 }
 @Test void cleanupRequiresThirtyDaysAndLiveVersionAndAdmin()throws Exception {
  admin();String image=picture(seller);putAuth("/api/users/me/images/"+image+"/trash",Map.of("version",0),seller,204);assertEquals(0,getAuth("/api/admin/media/cleanup",outsider,200).get("candidates").size());getAuth("/api/admin/media/cleanup",buyer,403);
  jdbc.update("UPDATE image_storage_records SET trashed_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 31 DAY) WHERE image_id=?",image);var candidates=getAuth("/api/admin/media/cleanup",outsider,200).get("candidates");assertEquals(1,candidates.size());
  putAuth("/api/users/me/images/"+image+"/restore",Map.of("version",1),seller,204);postAuth("/api/admin/media/cleanup",Map.of("images",candidates),outsider,409);assertEquals(200,mvc.perform(get("/api/images/"+image)).andReturn().getResponse().getStatus());
 }
 @Test void confirmedIsolatedCleanupDeletesOnlyEligibleObjectAndKeepsTombstone()throws Exception {
  admin();String image=picture(seller),keep=picture(seller);putAuth("/api/users/me/images/"+image+"/trash",Map.of("version",0),seller,204);jdbc.update("UPDATE image_storage_records SET trashed_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 31 DAY) WHERE image_id=?",image);
  var candidates=getAuth("/api/admin/media/cleanup",outsider,200).get("candidates");postAuth("/api/admin/media/cleanup",Map.of("images",candidates),outsider,200);
  assertTrue(jdbc.queryForObject("SELECT physical_deleted FROM image_storage_records WHERE image_id=?",Boolean.class,image));assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("LESSON24_UPLOAD_DIR")).resolve(image+".png")));assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("LESSON24_UPLOAD_DIR")).resolve(keep+".png")));
 }
 @Test void twoConcurrentRecoveryRequestsCanConsumeCodeOnlyOnce()throws Exception {
  bind();cooldown();String account=jdbc.queryForObject("SELECT username FROM users WHERE id=?",String.class,sellerId);
  String id=postAuth("/api/users/email/challenges",Map.of("purpose","RECOVER","username",account,"email","seller"+sellerId+"@example.test"),null,202).get("challengeId").asString(),code=deliveredCode;
  var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++){final int n=i;jobs.add(pool.submit(()->{ready.countDown();go.await();return mvc.perform(post("/api/users/recover-by-email").contentType("application/json").content(body(Map.of("challengeId",id,"code",code,"username",account,"newPassword","ConcurrentCampus2026!"+n)))).andReturn().getResponse().getStatus();}));}assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get(10,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(200,403),codes);}finally{pool.shutdownNow();}
 }
 @Test void twoConcurrentTrashRequestsCannotOverwriteImageVersion()throws Exception {
  String image=picture(seller);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{ready.countDown();go.await();return mvc.perform(put("/api/users/me/images/"+image+"/trash").header("Authorization",seller).contentType("application/json").content(body(Map.of("version",0)))).andReturn().getResponse().getStatus();}));assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get(10,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(204,409),codes);}finally{pool.shutdownNow();}
 }
 @Test void registeredNicknameAndRelistingCannotBypassTextPolicy()throws Exception {
  postAuth("/api/users/register",Map.of("username","risk_"+UUID.randomUUID().toString().substring(0,8),"password","Campus2026!","nickname","代开发票"),null,422);
  var product=product();jdbc.update("UPDATE products SET title='代开发票',status='OFF_SHELF' WHERE id=?",product.id());putAuth("/api/products/"+product.id()+"/status",Map.of("status","ON_SALE","version",0),seller,422);
 }

 @Test void normalPasswordChangeInvalidatesOutstandingMailChallenges()throws Exception {
  String id=challenge("BIND","seller"+sellerId+"@example.test",seller,202).get("challengeId").asString();
  postAuth("/api/users/me/password",Map.of("currentPassword","Campus2026!","newPassword","NewPassword2026!"),seller,200);
  assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM account_email_challenges WHERE id=?",java.sql.Timestamp.class,id));
 }

}
