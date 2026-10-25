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
        return count(userId, "ALL");
    }

    private static String availabilityClause(String availability) {
        return switch (availability) {
            case "ALL" -> "";
            case "AVAILABLE" -> " AND p.id IS NOT NULL";
            case "UNAVAILABLE" -> " AND p.id IS NULL";
            default -> throw new IllegalArgumentException("Unknown favorite availability");
        };
    }

    public long count(long userId, String availability) {
        return count(userId, availability, "");
    }

    private static String keywordClause(String keyword) {
        // Search only the already-visible LEFT JOIN projection, never hidden products.
        return keyword.isEmpty() ? "" : " AND p.title LIKE ? ESCAPE '!'";
    }

    private static List<Object> queryArgs(long userId, String keyword) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (!keyword.isEmpty()) args.add("%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        return args;
    }

    public long count(long userId, String availability, String keyword) {
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorites f "
                + "LEFT JOIN products p ON p.id = f.product_id AND p.status = 'ON_SALE' "
                + "WHERE f.user_id = ?" + availabilityClause(availability) + keywordClause(keyword),
                Long.class, queryArgs(userId, keyword).toArray());
        return result == null ? 0 : result;
    }

    public List<FavoriteItemResponse> findPage(long userId, int size, long offset) {
        return findPage(userId, size, offset, "ALL");
    }

    public List<FavoriteItemResponse> findPage(long userId, int size, long offset, String availability) {
        return findPage(userId, size, offset, availability, "");
    }

    public List<FavoriteItemResponse> findPage(long userId, int size, long offset, String availability, String keyword) {
        List<Object> args = queryArgs(userId, keyword);
        args.add(size); args.add(offset);
        String select = """
                SELECT f.product_id,
                       p.id, p.title, p.description, p.price_in_cents,
                       p.status, p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM product_favorites f
                LEFT JOIN products p ON p.id = f.product_id AND p.status = 'ON_SALE'
                LEFT JOIN users u ON u.id = p.seller_id
                WHERE f.user_id = ?
                """;
        return jdbc.query(select + availabilityClause(availability) + keywordClause(keyword) + """

                ORDER BY f.created_at DESC, f.product_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new FavoriteItemResponse(
                rs.getLong("product_id"), rs.getObject("id", Long.class) == null
                ? null : products.mapProduct(rs, rowNum)), args.toArray());
    }
}
