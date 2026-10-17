package com.campus.market.favorite;

import com.campus.market.product.ProductRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class FavoriteRepository {
    private final JdbcTemplate jdbc;
    private final ProductRepository products;

    public FavoriteRepository(JdbcTemplate jdbc, ProductRepository products) {
        this.jdbc = jdbc;
        this.products = products;
    }

    public void add(long userId, long productId) {
        jdbc.update("""
                INSERT INTO product_favorites(user_id, product_id) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE product_id = product_id
                """, userId, productId);
    }

    public void remove(long userId, long productId) {
        jdbc.update("DELETE FROM product_favorites WHERE user_id = ? AND product_id = ?",
                userId, productId);
    }

    public List<Long> findSelectedIds(long userId, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(ids);
        return jdbc.queryForList("SELECT product_id FROM product_favorites WHERE user_id = ? "
                + "AND product_id IN (" + placeholders + ") ORDER BY product_id", Long.class, args.toArray());
    }

    public long count(long userId) {
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorites WHERE user_id = ?", Long.class, userId);
        return result == null ? 0 : result;
    }

    public List<FavoriteItemResponse> findPage(long userId, int size, long offset) {
        return jdbc.query("""
                SELECT f.product_id,
                       p.id, p.title, p.description, p.price_in_cents,
                       p.status, p.cover_image_id, p.version, p.category_code,
                       u.id AS seller_id, u.nickname AS seller_nickname,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM product_favorites f
                LEFT JOIN products p ON p.id = f.product_id AND p.status = 'ON_SALE'
                LEFT JOIN users u ON u.id = p.seller_id
                WHERE f.user_id = ?
                ORDER BY f.created_at DESC, f.product_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new FavoriteItemResponse(
                rs.getLong("product_id"), rs.getObject("id", Long.class) == null
                ? null : products.mapProduct(rs, rowNum)), userId, size, offset);
    }
}
