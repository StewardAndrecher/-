package com.campus.market.auth;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SessionManagementService {
    public record Summary(long userId, long activeSessions, long otherActiveSessions,
            String currentCreatedAt, String currentExpiresAt, String sampledAt) {}
    public record Revocation(long userId, long revokedSessions, String processedAt) {}
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final SessionRepository repository;
    public SessionManagementService(JdbcTemplate jdbc,SessionService sessions,SessionRepository repository) {
        this.jdbc=jdbc;this.sessions=sessions;this.repository=repository;
    }
    @Transactional(readOnly=true)
    public Summary summary(String authorization) {
        String hash=sessions.tokenHash(authorization);
        var rows=jdbc.query("""
                SELECT s.user_id,s.created_at,s.expires_at,CURRENT_TIMESTAMP AS sampled_at,
                    (SELECT COUNT(*) FROM login_sessions a WHERE a.user_id=s.user_id
                     AND a.revoked_at IS NULL AND a.expires_at>CURRENT_TIMESTAMP) AS active_sessions
                FROM login_sessions s WHERE s.token_hash=? AND s.revoked_at IS NULL
                    AND s.expires_at>CURRENT_TIMESTAMP
                """,(rs,n)->{
            long user=rs.getLong("user_id"),active=rs.getLong("active_sessions");
            if(user<1||user>9007199254740991L||active<1||active>9007199254740991L)throw new IllegalStateException("登录状态超出可展示范围");
            return new Summary(user,active,active-1,rs.getTimestamp("created_at").toLocalDateTime().toString(),
                    rs.getTimestamp("expires_at").toLocalDateTime().toString(),rs.getTimestamp("sampled_at").toLocalDateTime().toString());
        },hash);
        return rows.stream().findFirst().orElseThrow(LoginRequiredException::new);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Revocation revokeOthers(String authorization) {
        String hash=sessions.tokenHash(authorization);
        long user=sessions.currentUser(authorization).id();
        repository.lockUser(user);
        // Recheck after waiting for the account lock; another request may have revoked this session.
        Timestamp cutoff=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class);
        if(jdbc.queryForList("""
                SELECT user_id FROM login_sessions WHERE token_hash=? AND user_id=?
                    AND revoked_at IS NULL AND expires_at>? FOR UPDATE
                """,Long.class,hash,user,cutoff).isEmpty())throw new LoginRequiredException();
        int changed=jdbc.update("""
                UPDATE login_sessions SET revoked_at=? WHERE user_id=? AND token_hash<>?
                    AND revoked_at IS NULL AND expires_at>?
                """,cutoff,user,hash,cutoff);
        return new Revocation(user,changed,cutoff.toLocalDateTime().toString());
    }
}
