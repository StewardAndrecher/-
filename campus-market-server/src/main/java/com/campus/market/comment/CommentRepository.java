package com.campus.market.comment;

import org.springframework.jdbc.core.JdbcTemplate;
import com.campus.market.product.ProductStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class CommentRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT c.id, c.product_id, c.content, c.created_at,
                   u.id AS author_id, u.nickname AS author_nickname,
                   CASE WHEN p.seller_id = c.author_id THEN TRUE ELSE FALSE END AS seller
            FROM product_comments c
            JOIN users u ON u.id = c.author_id
            JOIN products p ON p.id = c.product_id
            """;

    public CommentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long productId, long authorId, String content, String requestId) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO product_comments(product_id, author_id, content, request_id) VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, productId);
            statement.setLong(2, authorId);
            statement.setString(3, content);
            statement.setString(4, requestId);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("未取得留言ID");
        return id.longValue();
    }

    // 只锁商品行，不额外锁卖家用户行。
    public Optional<ProductStatus> lockProduct(long productId) {
        return jdbc.query("SELECT status FROM products WHERE id = ? FOR UPDATE",
                (rs, row) -> ProductStatus.valueOf(rs.getString("status")), productId).stream().findFirst();
    }

    public record Submission(long id, String content, boolean withdrawn) {}

    public Optional<Submission> findSubmissionForUpdate(long productId, long authorId, String requestId) {
        return jdbc.query("""
                SELECT id, content, is_deleted FROM product_comments
                WHERE product_id = ? AND author_id = ? AND request_id = ? FOR UPDATE
                """, (rs, row) -> new Submission(rs.getLong("id"), rs.getString("content"),
                rs.getBoolean("is_deleted")), productId, authorId, requestId).stream().findFirst();
    }

    // 当前读：等待其他提交后也能读取其结果；包含已撤回记录，只用于作者的提交回执。
    public Optional<CommentResponse> findSubmissionComment(long productId, long id) {
        return jdbc.query(SELECT + " WHERE c.product_id = ? AND c.id = ? FOR SHARE",
                this::map, productId, id).stream().findFirst();
    }

    public Optional<CommentResponse> findActive(long productId, long id) {
        return jdbc.query(SELECT + " WHERE c.product_id = ? AND c.id = ? AND c.is_deleted = FALSE",
                this::map, productId, id).stream().findFirst();
    }

    public Optional<Long> findAuthor(long productId, long id) {
        return jdbc.queryForList("SELECT author_id FROM product_comments WHERE product_id = ? AND id = ?",
                Long.class, productId, id).stream().findFirst();
    }

    public long count(long productId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_comments WHERE product_id = ? AND is_deleted = FALSE
                """, Long.class, productId);
        return total == null ? 0 : total;
    }

    public List<CommentResponse> findPage(long productId, int size, long offset) {
        return jdbc.query(SELECT + """
                 WHERE c.product_id = ? AND c.is_deleted = FALSE
                 ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?
                """, this::map, productId, size, offset);
    }

    public void withdraw(long productId, long id, long authorId) {
        jdbc.update("""
                UPDATE product_comments SET is_deleted = TRUE, deleted_at = CURRENT_TIMESTAMP(6)
                WHERE product_id = ? AND id = ? AND author_id = ? AND is_deleted = FALSE
                """, productId, id, authorId);
    }

    private CommentResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new CommentResponse(rs.getLong("id"), rs.getLong("product_id"), rs.getString("content"),
                new CommentAuthorResponse(rs.getLong("author_id"), rs.getString("author_nickname")),
                rs.getBoolean("seller"), rs.getTimestamp("created_at").toInstant().toString());
    }
}
