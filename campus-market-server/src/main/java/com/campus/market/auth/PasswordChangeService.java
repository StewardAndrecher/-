package com.campus.market.auth;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PasswordChangeService {
    public record Result(long userId, long revokedSessions, String changedAt) {}
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final SessionRepository repository;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    public PasswordChangeService(JdbcTemplate jdbc, SessionService sessions, SessionRepository repository,
            PasswordEncoder encoder, LoginRateLimiter limiter) {
        this.jdbc=jdbc; this.sessions=sessions; this.repository=repository;
        this.encoder=encoder; this.limiter=limiter;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result change(String authorization, PasswordChangeRequest request, String remoteAddress) {
        var user=sessions.currentUser(authorization);
        validate(request);
        repository.lockUser(user.id());
        Timestamp cutoff=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if(jdbc.queryForList("""
                SELECT user_id FROM login_sessions WHERE token_hash=? AND user_id=?
                    AND revoked_at IS NULL AND expires_at>? FOR UPDATE
                """, Long.class, sessions.tokenHash(authorization), user.id(), cutoff).isEmpty()) {
            throw new LoginRequiredException();
        }
        var attempt=limiter.begin("password-change:"+user.username(), remoteAddress);
        boolean matched;
        String replacement;
        try {
            String stored=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user.id());
            matched=encoder.matches(request.currentPassword(), stored);
            if(matched && encoder.matches(request.newPassword(), stored)) {
                limiter.succeeded(attempt);
                throw new PasswordChangeException(400,"INVALID_PASSWORD_CHANGE","新密码不能与原密码相同");
            }
            replacement=matched?encoder.encode(request.newPassword()):null;
        } catch(PasswordChangeException invalid) { throw invalid; }
        catch(RuntimeException failure) { limiter.aborted(attempt); throw failure; }
        if(!matched) {
            limiter.failed(attempt);
            throw new PasswordChangeException(403,"CURRENT_PASSWORD_MISMATCH","原密码不正确，本次未修改");
        }
        // Redis must confirm the attempt before any database mutation; fail closed on outage.
        limiter.succeeded(attempt);
        if(jdbc.update("UPDATE users SET password_hash=? WHERE id=?", replacement, user.id())!=1) {
            throw new IllegalStateException("未能更新当前账号密码");
        }
        jdbc.update("DELETE FROM account_recovery_codes WHERE user_id=?",user.id());
        jdbc.update("UPDATE account_email_challenges SET consumed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL",user.id());
        int revoked=jdbc.update("""
                UPDATE login_sessions SET revoked_at=? WHERE user_id=?
                    AND revoked_at IS NULL AND expires_at>?
                """, cutoff, user.id(), cutoff);
        if(revoked<1) throw new IllegalStateException("未能退出当前登录");
        return new Result(user.id(), revoked, cutoff.toLocalDateTime().toString());
    }
    private void validate(PasswordChangeRequest request) {
        if(request==null || request.currentPassword()==null || request.currentPassword().isEmpty()
                || request.currentPassword().codePointCount(0,request.currentPassword().length())>64
                || request.currentPassword().getBytes(StandardCharsets.UTF_8).length>72) {
            throw new PasswordChangeException(400,"INVALID_PASSWORD_CHANGE","请填写有效的原密码");
        }
        String value=request.newPassword();
        if(value==null || value.isBlank() || value.codePointCount(0,value.length())<8
                || value.codePointCount(0,value.length())>64
                || value.getBytes(StandardCharsets.UTF_8).length>72
                || value.codePoints().anyMatch(cp->cp>=0xd800 && cp<=0xdfff)) {
            throw new PasswordChangeException(400,"INVALID_PASSWORD_CHANGE","新密码需要8～64个字符，UTF-8长度不超过72字节，不能全为空白");
        }
    }
}
