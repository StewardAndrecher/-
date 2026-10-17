package com.campus.market.seller;

import com.campus.market.product.SellerResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class SellerRepository {
    private final JdbcTemplate jdbc;
    public SellerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SellerResponse> findPublicSeller(long id) {
        return jdbc.query("SELECT id, nickname FROM users WHERE id = ?",
                (rs, row) -> new SellerResponse(rs.getLong("id"), rs.getString("nickname")), id)
                .stream().findFirst();
    }
}
