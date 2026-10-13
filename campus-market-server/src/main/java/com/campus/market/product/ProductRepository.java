package com.campus.market.product;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class ProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countByKeyword(String keyword) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE status = 'ON_SALE' AND title LIKE ? ESCAPE '!'",
                Long.class,
                searchPattern(keyword)
        );

        return total == null ? 0 : total;
    }

    public List<ProductResponse> findPage(
            String keyword,
            int size,
            long offset
    ) {
        String sql = """
            SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version,
                   u.id AS seller_id, u.nickname AS seller_nickname
            FROM products p
            LEFT JOIN users u ON u.id = p.seller_id
            WHERE p.status = 'ON_SALE' AND p.title LIKE ? ESCAPE '!'
            ORDER BY p.id DESC
            LIMIT ? OFFSET ?
            """;

        return jdbcTemplate.query(
                sql,
                this::mapProduct,
                searchPattern(keyword),
                size,
                offset
        );
    }

    private String searchPattern(String keyword) {
        String escaped = keyword.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        return "%" + escaped + "%";
    }

    public long insert(
            String title,
            String description,
            long priceInCents,
            long sellerId,
            String coverImageId
    ) {
        String sql = """
                INSERT INTO products (title, description, price_in_cents, seller_id, cover_image_id)
                VALUES (?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(
                            sql,
                            Statement.RETURN_GENERATED_KEYS
                    );

            statement.setString(1, title);
            statement.setString(2, description);
            statement.setLong(3, priceInCents);
            statement.setLong(4, sellerId);
            statement.setString(5, coverImageId);

            return statement;
        }, keyHolder);

        Number generatedId = keyHolder.getKey();

        if (generatedId == null) {
            throw new IllegalStateException(
                    "未取得数据库生成的商品ID"
            );
        }

        return generatedId.longValue();
    }

    public Optional<ProductResponse> findById(long id) {
        return findById(id, false);
    }

    // 必须在事务中调用，行锁持续到事务结束。
    public Optional<ProductResponse> findByIdForUpdate(long id) {
        return findById(id, true);
    }

    private Optional<ProductResponse> findById(long id, boolean lock) {
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version,
                       u.id AS seller_id, u.nickname AS seller_nickname
                FROM products p
                LEFT JOIN users u ON u.id = p.seller_id
                WHERE p.id = ?
                """ + (lock ? " FOR UPDATE" : "");

        List<ProductResponse> results = jdbcTemplate.query(
                sql,
                this::mapProduct,
                id
        );

        return results.stream().findFirst();
    }

    public int updateOwnedProduct(long id, long sellerId, String title,
                                  String description, long priceInCents, String coverImageId, long version) {
        return jdbcTemplate.update("""
                UPDATE products SET title = ?, description = ?, price_in_cents = ?,
                                    cover_image_id = ?, version = version + 1
                WHERE id = ? AND seller_id = ? AND version = ? AND version < 9007199254740991
                """, title, description, priceInCents, coverImageId, id, sellerId, version);
    }
    public long countBySeller(long sellerId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE seller_id = ?", Long.class, sellerId);
        return total == null ? 0 : total;
    }

    public List<ProductResponse> findPageBySeller(long sellerId, int size, long offset) {
        return jdbcTemplate.query("""
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version,
                       u.id AS seller_id, u.nickname AS seller_nickname
                FROM products p
                JOIN users u ON u.id = p.seller_id
                WHERE p.seller_id = ?
                ORDER BY p.id DESC
                LIMIT ? OFFSET ?
                """, this::mapProduct, sellerId, size, offset);
    }

    public int updateOwnedStatus(long id, long sellerId, ProductStatus status, long version) {
        return jdbcTemplate.update("""
                UPDATE products SET status = ?, version = version + 1
                WHERE id = ? AND seller_id = ? AND version = ? AND version < 9007199254740991
                """, status.name(), id, sellerId, version);
    }

    public ProductResponse mapProduct(ResultSet rs, int rowNum) throws SQLException {
        Long sellerId = rs.getObject("seller_id", Long.class);
        SellerResponse seller = sellerId == null ? null
                : new SellerResponse(sellerId, rs.getString("seller_nickname"));
        return new ProductResponse(
                rs.getLong("id"), rs.getString("title"),
                rs.getString("description"), rs.getLong("price_in_cents"), seller,
                ProductStatus.valueOf(rs.getString("status")),
                rs.getString("cover_image_id"),
                rs.getString("cover_image_id") == null ? null : "/api/images/" + rs.getString("cover_image_id"),
                rs.getLong("version")
        );
    }
}
