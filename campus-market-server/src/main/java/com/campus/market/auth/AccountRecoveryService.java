package com.campus.market.auth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
@Service
public class AccountRecoveryService {
 public record Status(boolean enabled,String createdAt) {}
 public record Generate(String currentPassword) {}
 public record Generated(String recoveryCode,String createdAt) {}
 public record Reset(String username,String recoveryCode,String newPassword) {}
 public record ResetResult(boolean passwordChanged,boolean sessionsRevoked) {}
 private final JdbcTemplate jdbc;private final SessionService sessions;private final SessionRepository repository;private final PasswordEncoder encoder;private final LoginRateLimiter limiter;private final SecureRandom random=new SecureRandom();
 public AccountRecoveryService(JdbcTemplate jdbc,SessionService sessions,SessionRepository repository,PasswordEncoder encoder,LoginRateLimiter limiter){this.jdbc=jdbc;this.sessions=sessions;this.repository=repository;this.encoder=encoder;this.limiter=limiter;}
 @Transactional(readOnly=true) public Status status(String auth){long user=sessions.currentUser(auth).id();return jdbc.query("SELECT created_at FROM account_recovery_codes WHERE user_id=?",(r,n)->new Status(true,r.getTimestamp(1).toLocalDateTime().toString()),user).stream().findFirst().orElse(new Status(false,null));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Generated generate(String auth,Generate request,String ip){
  var user=sessions.currentUser(auth);if(request==null||!validPassword(request.currentPassword(),false))throw invalid("请填写有效的当前密码");
  repository.lockUser(user.id());sessions.currentUser(auth);
  var attempt=limiter.begin("recovery-generate:"+user.username(),ip);
  boolean match;try{match=encoder.matches(request.currentPassword(),jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,user.id()));}catch(RuntimeException ex){limiter.aborted(attempt);throw ex;}
  if(!match){limiter.failed(attempt);throw new PasswordChangeException(403,"CURRENT_PASSWORD_MISMATCH","当前密码不正确");}
  limiter.succeeded(attempt);
  byte[] bytes=new byte[32];random.nextBytes(bytes);String code=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  jdbc.update("INSERT INTO account_recovery_codes(user_id,code_hash) VALUES(?,?) ON DUPLICATE KEY UPDATE code_hash=?,created_at=CURRENT_TIMESTAMP(6)",user.id(),hash(user.id(),code),hash(user.id(),code));
  return new Generated(code,status(auth).createdAt());
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public ResetResult reset(Reset request,String ip){
  if(request==null||request.username()==null||!request.username().matches("[a-z0-9_]{4,32}")||request.recoveryCode()==null||!request.recoveryCode().matches("[A-Za-z0-9_-]{43}")||!validPassword(request.newPassword(),true))throw invalid("请填写用户名、有效恢复码及8～64个字符的新密码");
  var attempt=limiter.begin("account-recovery:"+request.username(),ip);
  boolean matched=false;long user=0;String replacement=null;
  try{
   var ids=jdbc.queryForList("SELECT id FROM users WHERE username=? FOR UPDATE",Long.class,request.username());
   user=ids.isEmpty()?0:ids.get(0);
   String supplied=hash(user,request.recoveryCode());
   var stored=jdbc.queryForList("SELECT code_hash FROM account_recovery_codes WHERE user_id=? FOR UPDATE",String.class,user);
   String expected=stored.isEmpty()?"0".repeat(64):stored.get(0);
   matched=MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII))&&user>0&&!stored.isEmpty();
   // Always hash a valid new password, including unknown accounts, to reduce enumeration signals.
   replacement=encoder.encode(request.newPassword());
  }catch(RuntimeException ex){limiter.aborted(attempt);throw ex;}
  if(!matched){limiter.failed(attempt);throw new PasswordChangeException(403,"RECOVERY_REJECTED","用户名或恢复码无效，或恢复码已经使用");}
  limiter.succeeded(attempt);
  if(jdbc.update("DELETE FROM account_recovery_codes WHERE user_id=?",user)!=1)throw new IllegalStateException("恢复码状态已变化");
  jdbc.update("UPDATE account_email_challenges SET consumed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL",user);
  jdbc.update("UPDATE users SET password_hash=? WHERE id=?",replacement,user);
  jdbc.update("UPDATE login_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL",user);
  return new ResetResult(true,true);
 }
 private static String hash(long user,String code){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((user+":"+code).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static boolean validPassword(String value,boolean newPassword){return value!=null&&!value.isBlank()&&value.codePointCount(0,value.length())>=(newPassword?8:1)&&value.codePointCount(0,value.length())<=64&&value.getBytes(StandardCharsets.UTF_8).length<=72&&value.codePoints().noneMatch(c->c>=0xd800&&c<=0xdfff);}
 private static PasswordChangeException invalid(String message){return new PasswordChangeException(400,"INVALID_ACCOUNT_RECOVERY",message);}
}
