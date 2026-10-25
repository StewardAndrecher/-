package com.campus.market.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String username, String passwordHash, String nickname) {
        String sql = """
                INSERT INTO users (username, password_hash, nickname)
                VALUES (?, ?, ?)
                """;

        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, nickname);
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("未取得数据库生成的用户ID");
        }
        return id.longValue();
    }

    // 在事务中锁定当前账号行；昵称修改不接收客户端传入的用户ID。
    public Optional<UserResponse> findProfileForUpdate(long id) {
        return jdbcTemplate.query("SELECT id, username, nickname FROM users WHERE id = ? FOR UPDATE",
                (rs, row) -> new UserResponse(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nickname")), id).stream().findFirst();
    }

    public int updateNickname(long id, String nickname, String expectedNickname) {
        return jdbcTemplate.update("""
                UPDATE users SET nickname = ?, profile_version=profile_version+1
                WHERE id = ? AND CAST(nickname AS BINARY) = CAST(? AS BINARY) AND profile_version < 9007199254740991
                """, nickname, id, expectedNickname);
    }

    public Optional<UserCredentials> findCredentialsByUsername(String username) {
        String sql = """
                SELECT id, username, password_hash, nickname
                FROM users
                WHERE username = ? FOR UPDATE
                """;
        List<UserCredentials> users = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new UserCredentials(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("nickname")
                ),
                username
        );
        return users.stream().findFirst();
    }
}