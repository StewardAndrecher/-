package com.campus.market.auth;

import com.campus.market.common.GlobalExceptionHandler;
import com.campus.market.user.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
 "spring.datasource.url=${LESSON68_DB_URL:jdbc:mysql://127.0.0.1:1/disabled}",
 "spring.datasource.username=root", "spring.datasource.password=",
 "spring.data.redis.port=${LESSON67_REDIS_PORT:1}",
 "market.login-protection.enabled=true", "market.login-protection.prefix=campus:lesson68:test:",
 "market.login-protection.port=${LESSON68_REDIS_PORT:1}"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="LESSON68_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/campus_lesson68_verify\\?.*")
class LoginRateLimitIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired LoginRateLimiter limiter;
 @Autowired JdbcTemplate jdbc;
 @Autowired PasswordEncoder encoder;
 @Autowired UserRepository users;
 @Autowired SessionService sessions;
 final JsonMapper json=JsonMapper.builder().build();
 final String prefix="campus:lesson68:test:";
 LettuceConnectionFactory redisFactory;
 StringRedisTemplate redis;
 String hash;
 long userId;
 int port;
 @BeforeAll void prepareRedis() {
  port=Integer.parseInt(System.getenv("LESSON68_REDIS_PORT"));
  redisFactory=new LettuceConnectionFactory("127.0.0.1",port);redisFactory.afterPropertiesSet();redisFactory.start();
  redis=new StringRedisTemplate(redisFactory);hash=encoder.encode("Campus2026!");
 }
 @AfterAll void closeRedis() {if(redisFactory!=null)redisFactory.destroy();}
 @BeforeEach void fixtures() {
  assertTrue(System.getenv("LESSON68_DB_URL").contains("/campus_lesson68_verify?"));
  try(var cursor=redis.scan(ScanOptions.scanOptions().match(prefix+"*").build())) {cursor.forEachRemaining(redis::delete);}
  jdbc.update("DELETE FROM login_sessions");jdbc.update("DELETE FROM users");
  jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES(?,?,?)","limit_student",hash,"限流同学");
  userId=jdbc.queryForObject("SELECT id FROM users WHERE username='limit_student'",Long.class);
 }
 MockHttpServletRequestBuilder login(String username,String password) {
  return post("/api/users/login").contentType("application/json")
   .content(json.writeValueAsString(Map.of("username",username,"password",password)));
 }
 JsonNode check(MockHttpServletRequestBuilder request,int status)throws Exception {
  var r=mvc.perform(request).andReturn().getResponse();
  assertEquals(status,r.getStatus(),r.getContentAsString(StandardCharsets.UTF_8));
  return r.getContentAsByteArray().length==0?null:json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
 }
 List<String> keys() {
  var result=new ArrayList<String>();try(var cursor=redis.scan(ScanOptions.scanOptions().match(prefix+"*").build())) {cursor.forEachRemaining(result::add);}return result;
 }
 String ipKey(){return keys().stream().filter(k->k.endsWith(":ip")).findFirst().orElseThrow();}
 long sessionsCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions",Long.class);}
 void failures(int count,String username,String ip) {
  for(int i=0;i<count;i++)limiter.failed(limiter.begin(username,ip));
 }
 LoginRateLimiter custom(boolean enabled,int targetPort,int accountLimit,int accountSeconds,int ipLimit,int ipSeconds) {
  return new LoginRateLimiter(enabled,"127.0.0.1",targetPort,"",prefix,accountLimit,accountSeconds,ipLimit,ipSeconds);
 }
 MockMvc standalone(LoginRateLimiter protection,UserRepository repository,PasswordEncoder passwordEncoder) {
  return MockMvcBuilders.standaloneSetup(new LoginController(new LoginService(repository,passwordEncoder,protection),sessions))
   .setControllerAdvice(new GlobalExceptionHandler()).build();
 }
 @Test void fifthWrongPasswordIs401AndSixthAttemptIs429WithoutIssuingSession()throws Exception {
  for(int i=0;i<5;i++)assertEquals("INVALID_CREDENTIALS",check(login("limit_student","Wrong123!"),401).get("code").asText());
  var r=mvc.perform(login("limit_student","Wrong123!")).andReturn().getResponse();
  assertEquals(429,r.getStatus());assertEquals("LOGIN_RATE_LIMITED",json.readTree(r.getContentAsString(StandardCharsets.UTF_8)).get("code").asText());
  long retry=Long.parseLong(r.getHeader("Retry-After"));assertTrue(retry>=1&&retry<=900);
  assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-cache",r.getHeader("Pragma"));assertEquals(0,sessionsCount());
 }
 @Test void correctPasswordCannotBypassAnActiveLimit()throws Exception {
  failures(5,"limit_student","127.0.0.1");check(login("limit_student","Campus2026!"),429);assertEquals(0,sessionsCount());
 }
 @Test void successfulLoginClearsEarlierFailuresAndIssuesUsableSession()throws Exception {
  failures(4,"limit_student","127.0.0.1");
  var result=check(login("limit_student","Campus2026!"),200);String token=result.get("accessToken").asText();
  assertEquals("limit_student",check(get("/api/users/me").header("Authorization","Bearer "+token),200).get("username").asText());
  assertTrue(keys().stream().noneMatch(k->k.contains(":account:")));check(login("limit_student","Wrong123!"),401);assertEquals(1,sessionsCount());
 }
 @Test void invalidInputDoesNotCreateRedisReservations()throws Exception {
  check(login("BAD","password"),400);check(login("limit_student",""),400);
  check(login("limit_student","x".repeat(73)),400);assertEquals(List.of(),keys());assertEquals(0,sessionsCount());
 }
 @Test void unknownUserHasSameErrorAndLimitAsAnExistingUser()throws Exception {
  for(int i=0;i<5;i++)assertEquals("用户名或密码错误",check(login("missing_student","Wrong123!"),401).get("message").asText());
  check(login("missing_student","Wrong123!"),429);assertEquals(0,sessionsCount());
 }
 @Test void whitespaceDoesNotCreateAnotherAccountBucket()throws Exception {
  failures(5,"limit_student","127.0.0.1");check(login("  limit_student  ","Wrong123!"),429);
 }
 @Test void oneAccountsLimitDoesNotLockOtherAccountsAtTheSameAddress()throws Exception {
  failures(5,"missing_student","127.0.0.1");check(login("limit_student","Campus2026!"),200);
 }
 @Test void oneAddressesLimitDoesNotLockTheSameAccountAtAnotherAddress()throws Exception {
  failures(5,"limit_student","127.0.0.1");
  check(login("limit_student","Campus2026!").with(r->{r.setRemoteAddr("127.0.0.2");return r;}),200);
 }
 @Test void forgedForwardingHeadersCannotChangeTheRateLimitAddress()throws Exception {
  failures(5,"limit_student","127.0.0.1");
  check(login("limit_student","Campus2026!").header("X-Forwarded-For","198.51.100.8")
   .header("X-Real-IP","198.51.100.9").header("Forwarded","for=198.51.100.10"),429);
 }
 @Test void equivalentIpv6AndMappedIpv4AddressesShareTheirBuckets() {
  failures(5,"limit_student","::1");assertThrows(LoginRateLimitedException.class,()->limiter.begin("limit_student","0:0:0:0:0:0:0:1"));
  failures(5,"limit_student","127.0.0.1");assertThrows(LoginRateLimitedException.class,()->limiter.begin("limit_student","::ffff:127.0.0.1"));
 }
 @Test void sourceBudgetIsSharedAcrossDifferentUsernames() {
  for(int i=0;i<30;i++)limiter.failed(limiter.begin("missing_"+i,"127.0.0.1"));
  assertThrows(LoginRateLimitedException.class,()->limiter.begin("another_user","127.0.0.1"));
  assertEquals("30",redis.opsForValue().get(ipKey()));
 }
 @Test void successfulLoginsDoNotResetTheSourceBudget() {
  for(int i=0;i<30;i++)limiter.succeeded(limiter.begin("limit_student","127.0.0.1"));
  assertThrows(LoginRateLimitedException.class,()->limiter.begin("limit_student","127.0.0.1"));
  assertEquals("30",redis.opsForValue().get(ipKey()));
 }
 @Test void concurrentRequestsCannotReserveMoreThanFiveAccountSlots()throws Exception {
  var executor=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
  try {
   for(int i=0;i<24;i++)futures.add(executor.submit(()->{start.await();try{limiter.begin("limit_student","127.0.0.1");return true;}catch(LoginRateLimitedException expected){return false;}}));
   start.countDown();int allowed=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS))allowed++;
   assertEquals(5,allowed);String account=keys().stream().filter(k->k.contains(":account:")).findFirst().orElseThrow();
   assertEquals(5,redis.opsForHash().size(account));assertEquals("5",redis.opsForValue().get(ipKey()));
  } finally {start.countDown();executor.shutdownNow();}
 }
 @Test void concurrentDifferentAccountsCannotExceedTheSharedSourceBudget()throws Exception {
  var executor=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
  try {
   for(int i=0;i<60;i++){final int number=i;futures.add(executor.submit(()->{start.await();try{limiter.begin("missing_"+number,"127.0.0.1");return true;}catch(LoginRateLimitedException expected){return false;}}));}
   start.countDown();int allowed=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS))allowed++;
   assertEquals(30,allowed);assertEquals("30",redis.opsForValue().get(ipKey()));
  } finally {start.countDown();executor.shutdownNow();}
 }
 @Test void bothCountersAlwaysHaveAnExpiryAfterAdmission() {
  var attempt=limiter.begin("limit_student","127.0.0.1");
  long accountTtl=redis.getExpire(attempt.key(),TimeUnit.MILLISECONDS),ipTtl=redis.getExpire(ipKey(),TimeUnit.MILLISECONDS);
  assertTrue(accountTtl>0&&accountTtl<=900000);assertTrue(ipTtl>0&&ipTtl<=60000);
 }
 @Test void deniedRequestsDoNotExtendTheAccountWindow()throws Exception {
  failures(5,"limit_student","127.0.0.1");String account=keys().stream().filter(k->k.contains(":account:")).findFirst().orElseThrow();
  long before=redis.getExpire(account,TimeUnit.MILLISECONDS);Thread.sleep(100);
  assertThrows(LoginRateLimitedException.class,()->limiter.begin("limit_student","127.0.0.1"));
  assertTrue(redis.getExpire(account,TimeUnit.MILLISECONDS)<=before-50);
 }
 @Test void windowExpiryAllowsAttemptsAgain()throws Exception {
  var shortLimit=custom(true,port,1,1,30,1);
  try {shortLimit.failed(shortLimit.begin("limit_student","127.0.0.1"));
   assertThrows(LoginRateLimitedException.class,()->shortLimit.begin("limit_student","127.0.0.1"));
   Thread.sleep(1150);assertNotNull(shortLimit.begin("limit_student","127.0.0.1"));
  } finally {shortLimit.destroy();}
 }
 @Test void successDoesNotEraseFailuresThatCompletedAfterItStarted() {
  var old=limiter.begin("limit_student","127.0.0.1");limiter.failed(old);
  var success=limiter.begin("limit_student","127.0.0.1");
  var newer=limiter.begin("limit_student","127.0.0.1");limiter.failed(newer);limiter.succeeded(success);
  assertEquals(1,redis.opsForHash().size(newer.key()));assertEquals("failed",redis.opsForHash().get(newer.key(),newer.ticket()));
  assertFalse(redis.opsForHash().hasKey(old.key(),old.ticket()));
 }
 @Test void successKeepsOtherInFlightReservations() {
  var success=limiter.begin("limit_student","127.0.0.1");var other=limiter.begin("limit_student","127.0.0.1");
  limiter.succeeded(success);assertEquals("pending",redis.opsForHash().get(other.key(),other.ticket()));
  limiter.failed(other);assertEquals("failed",redis.opsForHash().get(other.key(),other.ticket()));
 }
 @Test void infrastructureFailureReleasesAccountSlotButKeepsSourceBudget() {
  var repository=mock(UserRepository.class);when(repository.findCredentialsByUsername("limit_student")).thenThrow(new IllegalStateException("isolated database failure"));
  var service=new LoginService(repository,encoder,limiter);
  assertThrows(IllegalStateException.class,()->service.login(new LoginUserRequest("limit_student","Campus2026!"),"127.0.0.1"));
  assertTrue(keys().stream().noneMatch(k->k.contains(":account:")));assertEquals("1",redis.opsForValue().get(ipKey()));
 }
 @Test void blockedAttemptDoesNotQueryCredentialsOrComputePasswordHash() {
  failures(5,"limit_student","127.0.0.1");var repository=mock(UserRepository.class);var password=mock(PasswordEncoder.class);
  var service=new LoginService(repository,password,limiter);clearInvocations(password);
  assertThrows(LoginRateLimitedException.class,()->service.login(new LoginUserRequest("limit_student","Campus2026!"),"127.0.0.1"));
  verifyNoInteractions(repository,password);
 }
 @Test void unavailableProtectionReturns503AndCannotIssueSession()throws Exception {
  var unavailable=custom(true,1,5,900,30,60);var repository=mock(UserRepository.class);var password=mock(PasswordEncoder.class);
  try {
   var isolated=standalone(unavailable,repository,password);clearInvocations(password);
   var response=isolated.perform(login("limit_student","Campus2026!")).andReturn().getResponse();
   assertEquals(503,response.getStatus());assertEquals("5",response.getHeader("Retry-After"));assertEquals("no-store",response.getHeader("Cache-Control"));
   assertEquals("LOGIN_PROTECTION_UNAVAILABLE",json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("code").asText());
   assertThrows(LoginProtectionUnavailableException.class,()->unavailable.begin("limit_student","127.0.0.1"));
   verifyNoInteractions(repository,password);assertEquals(0,sessionsCount());
  } finally {unavailable.destroy();}
 }
 @Test void failedFinalizationAfterPasswordSuccessStillCannotIssueSession()throws Exception {
  var failing=spy(limiter);doThrow(new LoginProtectionUnavailableException()).when(failing).succeeded(any());
  var isolated=standalone(failing,users,encoder);
  assertEquals(503,isolated.perform(login("limit_student","Campus2026!")).andReturn().getResponse().getStatus());assertEquals(0,sessionsCount());
 }
 @Test void existingSessionRemainsUsableWhileNewLoginProtectionIsUnavailable()throws Exception {
  var token=sessions.issueToken(new UserResponse(userId,"limit_student","限流同学")).accessToken();
  var unavailable=custom(true,1,5,900,30,60);
  try {assertThrows(LoginProtectionUnavailableException.class,()->unavailable.begin("limit_student","127.0.0.1"));
   check(get("/api/users/me").header("Authorization","Bearer "+token),200);assertEquals(1,sessionsCount());
  } finally {unavailable.destroy();}
 }
 @Test void corruptedRedisStateFailsClosedWithoutPasswordLookup() {
  var protection=custom(true,port,5,900,30,60);
  try {
   protection.begin("limit_student","127.0.0.1");redis.opsForValue().set(ipKey(),"not-a-number",Duration.ofSeconds(60));
   var repository=mock(UserRepository.class);var password=mock(PasswordEncoder.class);var service=new LoginService(repository,password,protection);clearInvocations(password);
   assertThrows(LoginProtectionUnavailableException.class,()->service.login(new LoginUserRequest("limit_student","Campus2026!"),"127.0.0.1"));verifyNoInteractions(repository,password);
  } finally {protection.destroy();}
 }
 @Test void expiredOldTicketCannotClearANewWindowsFailures()throws Exception {
  var shortLimit=custom(true,port,5,1,30,60);
  try {var old=shortLimit.begin("limit_student","127.0.0.1");Thread.sleep(1150);
   var current=shortLimit.begin("limit_student","127.0.0.1");shortLimit.failed(current);shortLimit.succeeded(old);shortLimit.failed(old);
   assertEquals(1,redis.opsForHash().size(current.key()));assertEquals("failed",redis.opsForHash().get(current.key(),current.ticket()));
  } finally {shortLimit.destroy();}
 }
 @Test void corsExposesRetryAfterToTheWebClient()throws Exception {
  failures(5,"limit_student","127.0.0.1");var response=mvc.perform(login("limit_student","Wrong123!")
   .header("Origin","http://localhost:5173")).andReturn().getResponse();
  assertEquals(429,response.getStatus());assertTrue(response.getHeader("Access-Control-Expose-Headers").contains("Retry-After"));
 }
 @Test void redisKeysAndValuesDoNotContainRawAccountAddressOrPassword() {
  var attempt=limiter.begin("limit_student","127.0.0.1");limiter.failed(attempt);
  for(String key:keys()) {assertFalse(key.contains("limit_student"));assertFalse(key.contains("127.0.0.1"));assertFalse(key.contains("Campus2026!"));}
  assertEquals(Map.of(attempt.ticket(),"failed"),redis.opsForHash().entries(attempt.key()));
 }
 @Test void duplicateCompletionAndAbortDoNotConsumeAdditionalSlots() {
  var failed=limiter.begin("limit_student","127.0.0.1");limiter.failed(failed);limiter.failed(failed);
  var aborted=limiter.begin("limit_student","127.0.0.1");limiter.aborted(aborted);limiter.failed(aborted);
  assertEquals(1,redis.opsForHash().size(failed.key()));assertEquals("failed",redis.opsForHash().get(failed.key(),failed.ticket()));
 }
 @Test void explicitlyDisabledProtectionKeepsOriginalLoginBehavior() {
  var disabled=custom(false,1,5,900,30,60);
  try {var service=new LoginService(users,encoder,disabled);assertEquals("limit_student",service.login(new LoginUserRequest("limit_student","Campus2026!"),null).username());assertEquals(List.of(),keys());}
  finally {disabled.destroy();}
 }
 @Test void missingExpiryOnAnExistingLimitIsRepairedToABoundedWindow() {
  var attempt=limiter.begin("limit_student","127.0.0.1");limiter.failed(attempt);failures(4,"limit_student","127.0.0.1");redis.persist(attempt.key());
  assertThrows(LoginRateLimitedException.class,()->limiter.begin("limit_student","127.0.0.1"));
  long ttl=redis.getExpire(attempt.key());assertTrue(ttl>0&&ttl<=900);
 }
 @Test void invalidRemoteAddressFailsClosedInsteadOfResolvingAHostname() {
  for(String address:Arrays.asList(null,"localhost","999.1.1.1","127.00.0.1","https://example.com","::1%unknown"))
   assertThrows(LoginProtectionUnavailableException.class,()->limiter.begin("limit_student",address));
  assertEquals(List.of(),keys());
 }
}
