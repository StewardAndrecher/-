package com.campus.market.auth;

import com.campus.market.user.UserResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SessionRepository {
    public static final long TOKEN_TTL_SECONDS = 7200;
    private final JdbcTemplate jdbcTemplate;

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String tokenHash, long userId) {
        jdbcTemplate.update("""
                INSERT INTO login_sessions (token_hash, user_id, expires_at)
                VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND))
                """, tokenHash, userId, TOKEN_TTL_SECONDS);
    }

    public Optional<UserResponse> findCurrentUser(String tokenHash) {
        var users = jdbcTemplate.query("""
                SELECT u.id, u.username, u.nickname
                FROM login_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ?
                  AND s.expires_at > CURRENT_TIMESTAMP
                  AND s.revoked_at IS NULL
                """, (rs, rowNum) -> new UserResponse(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("nickname")
        ), tokenHash);
        return users.stream().findFirst();
    }

    public void revoke(String tokenHash) {
        jdbcTemplate.update("""
                UPDATE login_sessions
                SET revoked_at = CURRENT_TIMESTAMP
                WHERE token_hash = ? AND revoked_at IS NULL
                """, tokenHash);
    }
}