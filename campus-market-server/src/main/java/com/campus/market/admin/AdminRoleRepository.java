package com.campus.market.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRoleRepository {
    private final JdbcTemplate jdbc;
    public AdminRoleRepository(JdbcTemplate jdbc) {this.jdbc = jdbc;}
    public boolean hasAdminRole(long userId) {
        return !jdbc.queryForList("SELECT user_id FROM user_role_grants WHERE user_id = ? AND role_code = 'ADMIN'", Long.class, userId).isEmpty();
    }
}
