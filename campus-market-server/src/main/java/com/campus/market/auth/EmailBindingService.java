package com.campus.market.auth;
import com.campus.market.governance.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class EmailBindingService {
    public record Status(boolean deliveryAvailable,boolean bound,String maskedEmail,long version){}
    public record Start(String purpose,String email,String currentPassword,String username){}
    public record Challenge(String challengeId,int expiresInSeconds,int resendAfterSeconds,String message){}
    public record Confirm(String challengeId,String code){}
    public record Reset(String challengeId,String code,String username,String newPassword){}
    private record Binding(String email,long version){}
    private record Pending(String id,long user,String purpose,String email,long version,String hash,int attempts,boolean expired,boolean consumed){}
    private final JdbcTemplate jdbc;private final SessionService sessions;private final PasswordEncoder encoder;private final VerificationMail mail;private final LoginRateLimiter limiter;private final String key;
    private final SecureRandom random=new SecureRandom();
    public EmailBindingService(JdbcTemplate jdbc,SessionService sessions,PasswordEncoder encoder,VerificationMail mail,LoginRateLimiter limiter,@Value("${market.email.otp-key:}")String key){
        this.jdbc=jdbc;this.sessions=sessions;this.encoder=encoder;this.mail=mail;this.limiter=limiter;this.key=key;
        if(mail.available()&&key.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("邮件验证码摘要密钥需要至少32字节");
    }
    public boolean deliveryAvailable(){return mail.available();}
    public static boolean validEmail(String value){return value!=null&&value.length()<=254&&value.matches("[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+");}
    private String email(String raw){if(!validEmail(raw))throw invalid("请填写有效邮箱，不支持空格或国际化邮箱地址");return raw.toLowerCase(Locale.ROOT);}
    private Binding binding(long user){return jdbc.query("SELECT email,version FROM account_email_bindings WHERE user_id=?",(r,n)->new Binding(r.getString(1),r.getLong(2)),user).stream().findFirst().orElse(null);}
    private void available(){if(!mail.available())throw new PlatformException(503,"EMAIL_NOT_CONFIGURED","邮件服务尚未配置，请继续使用恢复码");}
    private void lock(long user){jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);}
    private String digest(String id,String code){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((id+":"+code).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException ex){throw new IllegalStateException(ex);}}
    private void password(long user,String value){
        if(value==null||value.length()>128)throw invalid("请验证当前密码");
        if(!encoder.matches(value,jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,user)))throw new PlatformException(403,"CURRENT_PASSWORD_MISMATCH","当前密码不正确");
    }
    private Challenge response(String id){return new Challenge(id,600,60,"如账号和邮箱匹配，验证码将发送到对应邮箱，请检查收件箱与垃圾邮件。");}
    @Transactional(readOnly=true) public Status status(String auth){long user=sessions.currentUser(auth).id();Binding b=binding(user);return new Status(mail.available(),b!=null,b==null?null:b.email().substring(0,1)+"***"+b.email().substring(b.email().indexOf('@')),b==null?0:b.version());}
    @Transactional(noRollbackFor=PlatformException.class,isolation=Isolation.READ_COMMITTED) public Challenge start(Start request,String auth,String ip){
        available();if(request==null||request.purpose()==null||!Set.of("BIND","UNBIND","RECOVER").contains(request.purpose()))throw invalid("验证用途无效");
        String address=email(request.email());boolean recovery="RECOVER".equals(request.purpose());
        if(recovery&&(request.username()==null||!request.username().matches("[a-z0-9_]{4,32}")))throw invalid("请填写注册用户名");
        long user=recovery?0:sessions.currentUser(auth).id();
        var ticket=limiter.begin("email-send:"+(recovery?request.username():user),ip);
        try {
            if(recovery){var ids=jdbc.queryForList("SELECT id FROM users WHERE username=?",Long.class,request.username());if(!ids.isEmpty())user=ids.get(0);}
            if(user==0){limiter.succeeded(ticket);return response(UUID.randomUUID().toString());}
            lock(user);if(!recovery){sessions.currentUser(auth);password(user,request.currentPassword());}
        }catch(RuntimeException ex){limiter.failed(ticket);throw ex;}
        Binding b=binding(user);
        if(recovery&&(b==null||!b.email().equals(address))){limiter.succeeded(ticket);return response(UUID.randomUUID().toString());}
        if(!recovery&&("BIND".equals(request.purpose())?b!=null:b==null||!b.email().equals(address))){limiter.failed(ticket);throw new PlatformException(409,"EMAIL_BINDING_CONFLICT","绑定前请先验证旧邮箱并解除绑定；解除时请填写当前邮箱");}
        jdbc.update("DELETE FROM account_email_challenges WHERE user_id=? AND expires_at<DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY)",user);
        long recent=jdbc.queryForObject("SELECT COUNT(*) FROM account_email_challenges WHERE user_id=? AND created_at>DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 60 SECOND)",Long.class,user);
        long daily=jdbc.queryForObject("SELECT COUNT(*) FROM account_email_challenges WHERE user_id=? AND created_at>DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY)",Long.class,user);
        if(recent>0||daily>=20){limiter.failed(ticket);if(recovery)return response(UUID.randomUUID().toString());throw new PlatformException(429,"EMAIL_SEND_LIMITED","请至少间隔60秒，每个账号每天最多发送20封验证邮件");}
        limiter.succeeded(ticket);
        String id=UUID.randomUUID().toString(),code=String.format(Locale.ROOT,"%08d",random.nextInt(100000000));
        // A replacement challenge invalidates prior messages of the same purpose.
        jdbc.update("UPDATE account_email_challenges SET consumed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND purpose=? AND consumed_at IS NULL",user,request.purpose());
        jdbc.update("INSERT INTO account_email_challenges(id,user_id,purpose,email,binding_version,code_hash,expires_at) VALUES(?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))",id,user,request.purpose(),address,b==null?-1:b.version(),digest(id,code));
        try{mail.send(address,code,request.purpose());}catch(PlatformException ex){jdbc.update("UPDATE account_email_challenges SET consumed_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);if(!recovery)throw ex;}
        return response(id);
    }
    private Pending pending(Confirm input){return pending(input,false);}
    private Pending pending(Confirm input,boolean locking){
        if(input==null||input.challengeId()==null||!input.challengeId().matches("[0-9a-f-]{36}")||input.code()==null||!input.code().matches("[0-9]{8}"))throw rejected();
        var rows=jdbc.query("SELECT id,user_id,purpose,email,binding_version,code_hash,attempts,expires_at<=CURRENT_TIMESTAMP(6),consumed_at IS NOT NULL FROM account_email_challenges WHERE id=?"+(locking?" FOR UPDATE":""),(r,n)->new Pending(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getInt(7),r.getBoolean(8),r.getBoolean(9)),input.challengeId());
        if(rows.isEmpty())throw rejected();return rows.get(0);
    }
    private Pending verify(Confirm input,long expected,String purpose){
        Pending hint=pending(input);if(hint.user()!=expected)throw rejected();lock(expected);
        Pending p=pending(input,true);if(!p.purpose().equals(purpose)||p.expired()||p.consumed()||p.attempts()>=5)throw rejected();
        jdbc.update("UPDATE account_email_challenges SET attempts=attempts+1 WHERE id=?",p.id());
        Binding b=binding(expected);long version=b==null?-1:b.version();
        if(version!=p.version()||(!purpose.equals("BIND")&&(b==null||!b.email().equals(p.email())))||!MessageDigest.isEqual(p.hash().getBytes(StandardCharsets.US_ASCII),digest(p.id(),input.code()).getBytes(StandardCharsets.US_ASCII)))throw rejected();
        return p;
    }
    @Transactional(noRollbackFor=PlatformException.class,isolation=Isolation.READ_COMMITTED) public Status confirm(Confirm input,String auth,String ip){
        available();long user=sessions.currentUser(auth).id();var ticket=limiter.begin("email-confirm:"+user,ip);
        Pending hint;try{hint=pending(input);if(!Set.of("BIND","UNBIND").contains(hint.purpose()))throw rejected();}catch(RuntimeException ex){limiter.failed(ticket);throw ex;}
        Pending p;try{p=verify(input,user,hint.purpose());sessions.currentUser(auth);}catch(RuntimeException ex){limiter.failed(ticket);throw ex;}
        limiter.succeeded(ticket);
        if(p.purpose().equals("BIND")){
            if(binding(user)!=null)throw rejected();
            try{jdbc.update("INSERT INTO account_email_bindings(user_id,email) VALUES(?,?)",user,p.email());}catch(org.springframework.dao.DuplicateKeyException ex){throw new PlatformException(409,"EMAIL_UNAVAILABLE","该邮箱无法绑定，请更换邮箱或找回原账号");}
        }else jdbc.update("DELETE FROM account_email_bindings WHERE user_id=?",user);
        jdbc.update("UPDATE account_email_challenges SET consumed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL",user);
        return status(auth);
    }
    @Transactional(noRollbackFor=PlatformException.class,isolation=Isolation.READ_COMMITTED) public AccountRecoveryService.ResetResult recover(Reset input,String ip){
        available();if(input==null||input.username()==null||!input.username().matches("[a-z0-9_]{4,32}")||!validNewPassword(input.newPassword()))throw invalid("请填写有效用户名与8～64字符的新密码");
        var ticket=limiter.begin("email-recover:"+input.username(),ip);String replacement=encoder.encode(input.newPassword());
        Pending p;try{var ids=jdbc.queryForList("SELECT id FROM users WHERE username=?",Long.class,input.username());if(ids.isEmpty())throw rejected();p=verify(new Confirm(input.challengeId(),input.code()),ids.get(0),"RECOVER");}catch(RuntimeException ex){limiter.failed(ticket);throw ex;}
        limiter.succeeded(ticket);
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?",replacement,p.user());
        jdbc.update("UPDATE login_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL",p.user());
        jdbc.update("DELETE FROM account_recovery_codes WHERE user_id=?",p.user());
        jdbc.update("UPDATE account_email_challenges SET consumed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL",p.user());
        return new AccountRecoveryService.ResetResult(true,true);
    }
    private static boolean validNewPassword(String value){return value!=null&&!value.isBlank()&&value.codePointCount(0,value.length())>=8&&value.codePointCount(0,value.length())<=64&&value.getBytes(StandardCharsets.UTF_8).length<=72&&value.codePoints().noneMatch(c->c>=0xd800&&c<=0xdfff);}
    private static PlatformException invalid(String message){return new PlatformException(400,"INVALID_EMAIL_VERIFICATION",message);}
    private static PlatformException rejected(){return new PlatformException(403,"EMAIL_VERIFICATION_REJECTED","验证信息无效、已使用或已过期，请重新获取验证码");}
}
