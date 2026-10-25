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
@SpringBootTest(properties={"spring.datasource.username=root","spring.datasource.password=","spring.flyway.enabled=true","market.product-cache.enabled=false","market.login-protection.enabled=false","market.orders.mq.enabled=false","market.notifications.mq.enabled=false","market.orders.timeout-sweep-enabled=false","market.orders.simulated-payment-enabled=true","springdoc.api-docs.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON85_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson85_verify\\?.*")
class MarketExperienceIntegrationTest {
 static Connection control;static String schema;
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry)throws Exception {
  String base=System.getenv("LESSON85_DB_URL");control=DriverManager.getConnection(base,"root","");
  try(var st=control.createStatement();var row=st.executeQuery("SELECT @@datadir")){assertTrue(row.next());assertTrue(row.getString(1).contains("campus-lesson24-"));}
  schema="campus_lesson128_case_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  try(var st=control.createStatement()){st.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
  registry.add("spring.datasource.url",()->base.replace("/campus_lesson85_verify?","/"+schema+"?"));
  registry.add("market.upload-dir",()->System.getenv("LESSON24_UPLOAD_DIR"));
 }
 @AfterAll static void cleanup()throws Exception {if(control!=null)try{if(schema!=null&&schema.matches("campus_lesson128_case_[0-9a-f]{12}"))try(var st=control.createStatement()){st.execute("DROP DATABASE "+schema);}}finally{control.close();}}
 @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired SessionService sessions;@Autowired PasswordEncoder encoder;@Autowired ProductService products;@Autowired OrderService orders;@Autowired OrderRefundService refunds;@Autowired PublicationService publications;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 final JsonMapper json=JsonMapper.builder().build();long buyerId,sellerId,outsiderId;String buyer,seller,outsider,username;
 @BeforeEach void prepare(){
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
 @Test void productStoresOrderedSixImagesAndRealFields()throws Exception {
  var ids=new ArrayList<String>();for(int i=0;i<6;i++)ids.add(picture(seller));var created=postAuth("/api/products",productBody(ids),seller,201);long id=created.get("id").asLong();
  assertEquals(body(ids),body(created.at("/details/imageIds")));assertEquals("EAST",created.at("/details/campusCode").asString());assertEquals("GOOD",created.at("/details/conditionCode").asString());assertEquals("图书馆门口",created.at("/details/handoverLocation").asString());
  assertEquals(ids,jdbc.queryForList("SELECT image_id FROM product_gallery WHERE product_id=? ORDER BY position",String.class,id));
  assertEquals(created.get("details"),getAuth("/api/products/"+id,null,200).get("details"));
 }
 @Test void galleryRejectsSevenDuplicateUnknownAndForeignImages()throws Exception {
  String mine=picture(seller),foreign=picture(outsider);var invalid=List.of(List.of(mine,mine),List.of(foreign),List.of(UUID.randomUUID().toString()),java.util.Collections.nCopies(7,mine));
  for(var ids:invalid)postAuth("/api/products",productBody(ids),seller,ids.equals(List.of(foreign))?403:400);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE seller_id=?",Long.class,sellerId));
 }
 @Test void nullImageElementIs400NotServerError()throws Exception {var input=new HashMap<>(productBody(List.of()));input.put("details",new HashMap<>(details(Arrays.asList((String)null))));postAuth("/api/products",input,seller,400);}
 @Test void coverMustMatchFirstGalleryImage()throws Exception {String a=picture(seller),b=picture(seller);var input=new HashMap<>(productBody(List.of(a,b)));input.put("coverImageId",b);postAuth("/api/products",input,seller,400);}
 @Test void invalidCampusConditionAndLocationNeverWrite()throws Exception {
  for(var field:List.of(Map.entry("campusCode","FAKE"),Map.entry("conditionCode","100%"),Map.entry("handoverLocation","x".repeat(81)),Map.entry("handoverLocation","room\nnumber"))){var input=new HashMap<>(productBody(List.of()));var extra=new HashMap<>(details(List.of()));extra.put(field.getKey(),field.getValue());input.put("details",extra);postAuth("/api/products",input,seller,400);}
 }
 @Test void campusFilterUsesDatabaseAndKeepsHistoricalUnspecified()throws Exception {
  var old=product();String title="校区筛选_"+UUID.randomUUID().toString().substring(0,8);var input=new HashMap<>(productBody(List.of()));input.put("title",title);postAuth("/api/products",input,seller,201);
  var east=check(get("/api/products").param("campus","EAST").param("keyword",title),200);assertEquals(1,east.get("total").asLong());
  assertEquals(0,check(get("/api/products").param("campus","WEST").param("keyword",title),200).get("total").asLong());
  assertEquals("UNSPECIFIED",getAuth("/api/products/"+old.id(),null,200).at("/details/campusCode").asString());getAuth("/api/products?campus=invalid",null,400);
 }
 @Test void metadataEditUsesVersionAndPreservesImagesWhenLegacyEditOmitsDetails()throws Exception {
  String a=picture(seller),b=picture(seller);var made=postAuth("/api/products",productBody(List.of(a,b)),seller,201);long id=made.get("id").asLong();var edit=new HashMap<>(productBody(List.of(b,a)));edit.put("version",made.get("version").asLong());
  var updated=putAuth("/api/products/"+id,edit,seller,200);assertEquals(1,updated.get("version").asLong());assertEquals(b,updated.get("coverImageId").asString());putAuth("/api/products/"+id,edit,seller,409);
  var legacy=Map.of("title","旧客户端仅修改标题","description","兼容旧版","priceInCents",3500,"version",updated.get("version").asLong());var saved=putAuth("/api/products/"+id,legacy,seller,200);assertEquals(updated.get("details"),saved.get("details"));
 }
 @Test void publicationReplayRetainsOriginalMetadataAfterProductEdit()throws Exception {
  String a=picture(seller),b=picture(seller),key=UUID.randomUUID().toString();var payload=Map.of("requestId",key,"product",productBody(List.of(a,b)));long id=postAuth("/api/product-publications",payload,seller,201).get("productId").asLong();var edit=new HashMap<>(productBody(List.of(b,a)));edit.put("version",0);putAuth("/api/products/"+id,edit,seller,200);
  assertTrue(postAuth("/api/product-publications",payload,seller,200).get("replayed").asBoolean());postAuth("/api/product-publications",Map.of("requestId",key,"product",productBody(List.of(b,a))),seller,409);
 }
 @Test void cloudDraftRoundTripsAllMetadataAndChecksImageOwnership()throws Exception {
  String a=picture(seller),key=UUID.randomUUID().toString();var content=Map.of("title","资料草稿","description","保留多图和校区","priceText","35.00","coverImageId",a,"categoryCode","BOOK","details",details(List.of(a)));var saved=putAuth("/api/product-draft-backups/"+key,Map.of("sourceRevision",1,"content",content),seller,201);
  assertEquals(json.readTree(body(content)),saved.get("content"));assertEquals(saved,getAuth("/api/product-draft-backups/"+key,seller,200));putAuth("/api/product-draft-backups/"+UUID.randomUUID(),Map.of("sourceRevision",1,"content",content),outsider,403);
 }
 @Test void accountProfileWritesOnlyCurrentUsersPublicFields()throws Exception {
  String avatar=picture(buyer);var saved=putAuth("/api/users/me/profile",Map.of("avatarImageId",avatar,"bio","热爱循环利用的同学","campusCode","WEST","version",0,"userId",outsiderId),buyer,200);assertEquals(buyerId,saved.get("userId").asLong());assertEquals(1,saved.get("version").asLong());
  assertEquals("",getAuth("/api/users/me/profile",outsider,200).get("bio").asString());assertEquals(saved,getAuth("/api/users/me/profile",buyer,200));
 }
 @Test void profileRejectsForeignAvatarStaleVersionAndControls()throws Exception {
  String foreign=picture(outsider);putAuth("/api/users/me/profile",Map.of("avatarImageId",foreign,"bio","","campusCode","EAST","version",0),buyer,403);
  putAuth("/api/users/me/profile",Map.of("avatarImageId","","bio","hello","campusCode","EAST","version",1),buyer,409);
  for(String bio:List.of("x".repeat(161),"line\nbreak"))putAuth("/api/users/me/profile",Map.of("avatarImageId","","bio",bio,"campusCode","EAST","version",0),buyer,400);
 }
 @Test void nicknameAndExtraProfileShareOptimisticVersion()throws Exception {
  putAuth("/api/users/me/nickname",Map.of("nickname","新昵称","expectedNickname","买家"),buyer,200);
  putAuth("/api/users/me/profile",Map.of("avatarImageId","","bio","新资料","campusCode","EAST","version",0),buyer,409);
 }
 @Test void sellerPublicProjectionIncludesAvatarBioButNeverRecovery()throws Exception {
  String avatar=picture(seller);putAuth("/api/users/me/profile",Map.of("avatarImageId",avatar,"bio","图书馆见","campusCode","EAST","version",0),seller,200);long id=product().id();
  var view=getAuth("/api/products/"+id,null,200).get("seller");assertEquals(avatar,view.get("avatarImageId").asString());assertEquals("图书馆见",view.get("bio").asString());assertEquals(Set.of("id","nickname","avatarImageId","bio","campusCode"),new HashSet<>(view.propertyNames()));
 }
 @Test void anonymousProfileAndRecoveryManagementAre401()throws Exception {getAuth("/api/users/me/profile",null,401);getAuth("/api/users/me/recovery",null,401);postAuth("/api/users/me/recovery",Map.of("currentPassword","Campus2026!"),null,401);}
 String recovery()throws Exception{return postAuth("/api/users/me/recovery",Map.of("currentPassword","Campus2026!"),buyer,200).get("recoveryCode").asString();}
 @Test void recoveryCodeIsHashedSingleUseAndRevokesAllSessions()throws Exception {
  String another=auth(buyerId),code=recovery();String stored=jdbc.queryForObject("SELECT code_hash FROM account_recovery_codes WHERE user_id=?",String.class,buyerId);assertNotEquals(code,stored);assertEquals(64,stored.length());
  postAuth("/api/users/recover",Map.of("username",username,"recoveryCode",code,"newPassword","Changed2026!"),null,200);assertTrue(encoder.matches("Changed2026!",jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,buyerId)));
  getAuth("/api/users/me",buyer,401);getAuth("/api/users/me",another,401);postAuth("/api/users/recover",Map.of("username",username,"recoveryCode",code,"newPassword","OtherPassword!"),null,403);
 }
 @Test void regeneratedCodeInvalidatesOldAndCannotBeReadBack()throws Exception {
  String old=recovery(),fresh=recovery();assertNotEquals(old,fresh);var status=getAuth("/api/users/me/recovery",buyer,200);assertTrue(status.get("enabled").asBoolean());assertFalse(body(status).contains(old));assertFalse(body(status).contains(fresh));
  postAuth("/api/users/recover",Map.of("username",username,"recoveryCode",old,"newPassword","Changed2026!"),null,403);
 }
 @Test void incorrectCurrentPasswordCannotRotateRecoveryCode()throws Exception {recovery();var before=jdbc.queryForList("SELECT * FROM account_recovery_codes WHERE user_id=?",buyerId);postAuth("/api/users/me/recovery",Map.of("currentPassword","incorrect"),buyer,403);assertEquals(before,jdbc.queryForList("SELECT * FROM account_recovery_codes WHERE user_id=?",buyerId));}
 @Test void invalidRecoveryNeverMutatesPasswordOrRevokesSessions()throws Exception {recovery();var before=jdbc.queryForList("SELECT * FROM users WHERE id=?",buyerId);postAuth("/api/users/recover",Map.of("username",username,"recoveryCode","a".repeat(43),"newPassword","Changed2026!"),null,403);assertEquals(before,jdbc.queryForList("SELECT * FROM users WHERE id=?",buyerId));getAuth("/api/users/me",buyer,200);}
 @Test void changingPasswordInvalidatesRecoveryCode()throws Exception {String code=recovery();postAuth("/api/users/me/password",Map.of("currentPassword","Campus2026!","newPassword","Changed2026!"),buyer,200);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery_codes WHERE user_id=?",Long.class,buyerId));postAuth("/api/users/recover",Map.of("username",username,"recoveryCode",code,"newPassword","NewPassword2026!"),null,403);}
 @Test void unknownAndWrongRecoveryHaveSameResponse()throws Exception {var one=postAuth("/api/users/recover",Map.of("username",username,"recoveryCode","a".repeat(43),"newPassword","Changed2026!"),null,403);var two=postAuth("/api/users/recover",Map.of("username","unknown_123456","recoveryCode","a".repeat(43),"newPassword","Changed2026!"),null,403);assertEquals(one,two);}
 @Test void recoveryRejectsWeakOrOversizedPassword()throws Exception {String code=recovery();for(String value:List.of("short"," ".repeat(10),"中".repeat(25)))postAuth("/api/users/recover",Map.of("username",username,"recoveryCode",code,"newPassword",value),null,400);getAuth("/api/users/me",buyer,200);}
 @Test void refundReadWriteChecksBothOrderMembershipAndRole()throws Exception {long id=paidOrder();getAuth("/api/orders/"+id+"/refunds",null,401);getAuth("/api/orders/"+id+"/refunds",outsider,404);postAuth("/api/orders/"+id+"/refunds",refundBody(UUID.randomUUID().toString()),seller,404);assertFalse(getAuth("/api/orders/"+id+"/refunds",seller,200).get("canApply").asBoolean());}
 @Test void unpaidOrderCannotApply()throws Exception {var product=product();long id=orders.create(product.id(),new OrderModels.Create(product.version(),UUID.randomUUID().toString()),buyer).order().id();postAuth("/api/orders/"+id+"/refunds",refundBody(UUID.randomUUID().toString()),buyer,409);}
 @Test void refundAmountComesFromOrderSnapshotAndNoFundsAreExecuted()throws Exception {
  long id=paidOrder();var before=jdbc.queryForList("SELECT * FROM market_orders WHERE id=?",id);var input=new HashMap<>(refundBody(UUID.randomUUID().toString()));input.put("amountInCents",99999999);var result=postAuth("/api/orders/"+id+"/refunds",input,buyer,201).get("refund");assertEquals(3250,result.get("amountInCents").asLong());assertEquals("SIMULATED",result.get("paymentKind").asString());
  putAuth("/api/orders/"+id+"/refunds/"+result.get("id").asLong()+"/decision",Map.of("version",0,"decision","APPROVE","explanation","已经核对商品问题，同意本次申请。"),seller,200);assertEquals(before,jdbc.queryForList("SELECT * FROM market_orders WHERE id=?",id));
 }
 @Test void repeatedRequestIsIdempotentAndChangedContentConflicts()throws Exception {long id=paidOrder();var input=refundBody(UUID.randomUUID().toString());var first=postAuth("/api/orders/"+id+"/refunds",input,buyer,201);var again=postAuth("/api/orders/"+id+"/refunds",input,buyer,200);assertTrue(again.get("replayed").asBoolean());assertEquals(first.get("refund"),again.get("refund"));var changed=new HashMap<>(input);changed.put("description","另一份不同的问题描述，不能覆盖此前申请。");postAuth("/api/orders/"+id+"/refunds",changed,buyer,409);}
 @Test void oneActiveRefundPerOrderAndWithdrawAllowsNewRequest()throws Exception {long id=paidOrder();long refund=request(id).at("/refund/id").asLong();postAuth("/api/orders/"+id+"/refunds",refundBody(UUID.randomUUID().toString()),buyer,409);putAuth("/api/orders/"+id+"/refunds/"+refund+"/withdraw",Map.of("version",0),buyer,200);assertTrue(getAuth("/api/orders/"+id+"/refunds",buyer,200).get("canApply").asBoolean());request(id);}
 @Test void sellerDecisionsAndBuyerWithdrawalAreIsolated()throws Exception {long id=paidOrder(),refund=request(id).at("/refund/id").asLong();var decision=Map.of("version",0,"decision","REJECT","explanation","已核对交接记录，暂不同意本次申请。");String path="/api/orders/"+id+"/refunds/"+refund;putAuth(path+"/decision",decision,buyer,404);putAuth(path+"/withdraw",Map.of("version",0),seller,404);putAuth(path+"/decision",decision,outsider,404);var result=putAuth(path+"/decision",decision,seller,200);assertEquals("REJECTED",result.at("/refund/status").asString());assertEquals(2,result.at("/refund/events").size());assertTrue(putAuth(path+"/decision",decision,seller,200).get("replayed").asBoolean());putAuth(path+"/withdraw",Map.of("version",1),buyer,409);}
 @Test void staleDecisionVersionCannotOverwrite()throws Exception {long id=paidOrder(),refund=request(id).at("/refund/id").asLong();putAuth("/api/orders/"+id+"/refunds/"+refund+"/decision",Map.of("version",1,"decision","APPROVE","explanation","已经确认处理依据，同意本次申请。"),seller,409);assertEquals("REQUESTED",getAuth("/api/orders/"+id+"/refunds",buyer,200).at("/requests/0/status").asString());}
 @Test void activeRefundPausesDeliveryUntilBuyerWithdraws()throws Exception {long id=paidOrder(),refund=request(id).at("/refund/id").asLong();var blocked=assertThrows(OrderException.class,()->orders.deliver(id,seller));assertEquals("REFUND_IN_PROGRESS",blocked.code());putAuth("/api/orders/"+id+"/refunds/"+refund+"/withdraw",Map.of("version",0),buyer,200);assertEquals("DELIVERED",orders.deliver(id,seller).order().status());}
 @Test void sellerCannotUndoApprovedDecisionOrPretendRefundCompleted()throws Exception {long id=paidOrder(),refund=request(id).at("/refund/id").asLong();String path="/api/orders/"+id+"/refunds/"+refund+"/decision";putAuth(path,Map.of("version",0,"decision","APPROVE","explanation","同意申请，后续等待资金渠道处理。"),seller,200);putAuth(path,Map.of("version",1,"decision","REFUNDED","explanation","不能伪造资金到账状态，必须校验渠道。"),seller,400);putAuth(path,Map.of("version",1,"decision","REJECT","explanation","不能直接覆盖此前已经保存的处理结果。"),seller,409);}
 @Test void refundListIsPrivateFilteredPaginatedAndReadOnly()throws Exception {long id=paidOrder();request(id);var before=jdbc.queryForList("SELECT * FROM order_refunds WHERE order_id=?",id);var page=getAuth("/api/orders/refunds?role=SELLER&status=REQUESTED&page=99&size=1",seller,200);assertTrue(page.get("total").asLong()>=1);assertEquals(page.get("totalPages"),page.get("page"));assertEquals(0,getAuth("/api/orders/refunds",outsider,200).get("total").asLong());assertEquals(before,jdbc.queryForList("SELECT * FROM order_refunds WHERE order_id=?",id));getAuth("/api/orders/refunds?status=REFUNDED",buyer,400);}
 @Test void malformedReasonAndDescriptionAre400()throws Exception {long id=paidOrder();for(String text:List.of("短","字".repeat(1001),"控制字符不能被保存\u0000")){var input=new HashMap<>(refundBody(UUID.randomUUID().toString()));input.put("description",text);postAuth("/api/orders/"+id+"/refunds",input,buyer,400);}var bad=new HashMap<>(refundBody(UUID.randomUUID().toString()));bad.put("reason","SQL' OR 1=1");postAuth("/api/orders/"+id+"/refunds",bad,buyer,400);}
 @Test void concurrentRefundRequestsCreateOnlyOneActiveRecord()throws Exception {long id=paidOrder();var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var tasks=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)tasks.add(executor.submit(()->{start.await();try{refunds.create(id,new OrderRefundService.Create(UUID.randomUUID().toString(),"OTHER","并发申请应当只有一份有效记录，避免重复处理。"),buyer);return 201;}catch(OrderException failure){return failure.status();}}));start.countDown();var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(10,TimeUnit.SECONDS));Collections.sort(results);assertEquals(List.of(201,409),results);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_refunds WHERE order_id=?",Long.class,id));}finally{executor.shutdownNow();}}
 @Test void metadataOnlyDraftCanBeSavedWithoutATitle()throws Exception {
  var content=Map.of("title","","description","","priceText","","coverImageId","","categoryCode","OTHER","details",details(List.of()));
  putAuth("/api/product-draft-backups/"+UUID.randomUUID(),Map.of("sourceRevision",1,"content",content),seller,201);
 }
 @Test void malformedMetadataOnlyDraftReturns400Not500()throws Exception {
  var extra=new HashMap<String,Object>(details(List.of()));extra.put("campusCode",null);
  var content=Map.of("title","","description","","priceText","","coverImageId","","categoryCode","OTHER","details",extra);
  putAuth("/api/product-draft-backups/"+UUID.randomUUID(),Map.of("sourceRevision",1,"content",content),seller,400);
 }

 @Test void paymentAndJournalCommitWithOrderAndRemainDemo(){long id=paidOrder();orders.simulatePayment(id,buyer);var payment=jdbc.queryForMap("SELECT * FROM payment_attempts WHERE order_id=?",id);assertEquals("SIMULATED",payment.get("channel"));assertEquals("DEMO",payment.get("book"));assertEquals("SUCCEEDED",payment.get("status"));assertNull(payment.get("channel_trade_no"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM finance_journals WHERE payment_id=? AND debit_account='PROVIDER_CLEARING' AND credit_account='SELLER_PENDING' AND amount_in_cents=3250",Long.class,payment.get("id")));}
 @Test void concurrentSimulatedPaymentPostsOnce()throws Exception{var p=product();long id=orders.create(p.id(),new OrderModels.Create(p.version(),UUID.randomUUID().toString()),buyer).order().id();var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return orders.simulatePayment(id,buyer);});var b=pool.submit(()->{start.await();return orders.simulatePayment(id,buyer);});start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM finance_journals j JOIN payment_attempts p ON p.id=j.payment_id WHERE p.order_id=?",Long.class,id));}finally{pool.shutdownNow();}}
 @Test void failedEvidenceRollsBackOrderAndProduct(){var first=product();long id=orders.create(first.id(),new OrderModels.Create(first.version(),UUID.randomUUID().toString()),buyer).order().id();var other=product();long otherId=orders.create(other.id(),new OrderModels.Create(other.version(),UUID.randomUUID().toString()),buyer).order().id();jdbc.update("INSERT INTO payment_attempts(id,order_id,request_id,merchant_order_no,channel,merchant_scope,book,amount_in_cents) VALUES(?,?,?,?,'SIMULATED','DEMO','DEMO',3250)",UUID.randomUUID().toString(),otherId,UUID.randomUUID().toString(),"DEMO"+id);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->orders.simulatePayment(id,buyer));assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM market_orders WHERE id=?",String.class,id));assertEquals("ON_SALE",jdbc.queryForObject("SELECT status FROM products WHERE id=?",String.class,first.id()));}
 @Test void approvedApplicationDoesNotFabricateRefundSuccess(){long id=paidOrder();var application=refunds.create(id,new OrderRefundService.Create(UUID.randomUUID().toString(),"OTHER","需要检查的隔离退款申请，不能自动认定资金到账。"),buyer).refund();refunds.decide(id,application.id(),new OrderRefundService.Decision(0L,"APPROVE","同意协商申请，等待后续执行验收。"),seller);refunds.decide(id,application.id(),new OrderRefundService.Decision(0L,"APPROVE","同意协商申请，等待后续执行验收。"),seller);var row=jdbc.queryForMap("SELECT * FROM payment_refund_attempts WHERE application_id=?",application.id());assertEquals("CREATED",row.get("status"));assertNull(row.get("succeeded_at"));assertNull(row.get("channel_refund_no"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM finance_journals WHERE refund_attempt_id=?",Long.class,row.get("id")));}
 @Test void duplicatePaymentReceiptAndDuplicateJournalRejected(){long order=paidOrder();String id=jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=?",String.class,order);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO finance_journals(id,payment_id,book,kind,amount_in_cents,debit_account,credit_account) VALUES(?,?,'DEMO','PAYMENT',3250,'PROVIDER_CLEARING','SELLER_PENDING')",UUID.randomUUID().toString(),id));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO payment_attempts(id,order_id,request_id,merchant_order_no,channel,merchant_scope,book,amount_in_cents,status,confirmation_source,succeeded_at) VALUES(?,?,?,?,'SIMULATED','DEMO','DEMO',3250,'SUCCEEDED','LOCAL_SIMULATION',CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),order,UUID.randomUUID().toString(),"OTHER"+order));}
 @Test void journalCannotCrossDemoRealBooks(){long order=paidOrder();String id=jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=?",String.class,order);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO finance_journals(id,payment_id,book,kind,amount_in_cents,debit_account,credit_account) VALUES(?,?,'REAL','PAYMENT',3250,'PROVIDER_CLEARING','SELLER_PENDING')",UUID.randomUUID().toString(),id));}
 @Test void providerSuccessRequiresEvidenceAndMoneyInCents(){var p=product();long id=orders.create(p.id(),new OrderModels.Create(p.version(),UUID.randomUUID().toString()),buyer).order().id();for(long amount:List.of(0L,-1L,100000000L))assertConstraint(()->jdbc.update("INSERT INTO payment_attempts(id,order_id,request_id,merchant_order_no,channel,merchant_scope,book,amount_in_cents) VALUES(?,?,?,?,'WECHAT','fixture','REAL',?)",UUID.randomUUID().toString(),id,UUID.randomUUID().toString(),UUID.randomUUID().toString().replace("-",""),amount));assertConstraint(()->jdbc.update("INSERT INTO payment_attempts(id,order_id,request_id,merchant_order_no,channel,merchant_scope,book,amount_in_cents,status,succeeded_at) VALUES(?,?,?,?,'WECHAT','fixture','REAL',3250,'SUCCEEDED',CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),id,UUID.randomUUID().toString(),UUID.randomUUID().toString().replace("-","")));}
 @Test void externalIdentityIsUniqueWithinProviderApp(){jdbc.update("INSERT INTO user_external_identities(user_id,provider,app_id,subject_id,verified_at) VALUES(?,'WECHAT_MINIPROGRAM','wx-fixture','subject-one',CURRENT_TIMESTAMP(6))",buyerId);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO user_external_identities(user_id,provider,app_id,subject_id,verified_at) VALUES(?,'WECHAT_MINIPROGRAM','wx-fixture','subject-one',CURRENT_TIMESTAMP(6))",sellerId));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO user_external_identities(user_id,provider,app_id,subject_id,verified_at) VALUES(?,'WECHAT_MINIPROGRAM','wx-fixture','subject-two',CURRENT_TIMESTAMP(6))",buyerId));jdbc.update("INSERT INTO user_external_identities(user_id,provider,app_id,subject_id,verified_at) VALUES(?,'WECHAT_MINIPROGRAM','wx-other','subject-one',CURRENT_TIMESTAMP(6))",sellerId);}
 private void assertConstraint(Runnable operation){var error=assertThrows(org.springframework.dao.DataAccessException.class,operation::run);var cause=assertInstanceOf(java.sql.SQLException.class,error.getRootCause());assertEquals(3819,cause.getErrorCode());}
}
