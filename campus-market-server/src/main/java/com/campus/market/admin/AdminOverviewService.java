package com.campus.market.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOverviewService {
    private final JdbcTemplate jdbc;
    public AdminOverviewService(JdbcTemplate jdbc) {this.jdbc = jdbc;}
    public record Overview(long users, long products, long onSaleProducts, long orders) {}
    @Transactional(readOnly=true)
    public Overview overview() {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM users) AS users,
                       (SELECT COUNT(*) FROM products) AS products,
                       (SELECT COUNT(*) FROM products WHERE status='ON_SALE') AS on_sale_products,
                       (SELECT COUNT(*) FROM market_orders) AS orders
                """, (rs,row) -> new Overview(rs.getLong("users"), rs.getLong("products"),
                        rs.getLong("on_sale_products"), rs.getLong("orders")));
    }
}
