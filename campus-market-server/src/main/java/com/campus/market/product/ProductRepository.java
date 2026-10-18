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

    public long countByKeyword(String keyword, String category) {
        String sql = "SELECT COUNT(*) FROM products WHERE status = 'ON_SALE' AND title LIKE ? ESCAPE '!'";
        Long total = category.isEmpty()
                ? jdbcTemplate.queryForObject(sql, Long.class, searchPattern(keyword))
                : jdbcTemplate.queryForObject(sql + " AND category_code = ?", Long.class, searchPattern(keyword), category);
        return total == null ? 0 : total;
    }

    public List<ProductResponse> findPage(String keyword, String category, int size, long offset) {
        return findPage(keyword, category, ProductSort.NEWEST, size, offset);
    }

    public List<ProductResponse> findPage(String keyword, String category, ProductSort sort,
                                          int size, long offset) {
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code,
                       u.id AS seller_id, u.nickname AS seller_nickname,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p LEFT JOIN users u ON u.id = p.seller_id
                WHERE p.status = 'ON_SALE' AND p.title LIKE ? ESCAPE '!'
                """;
        if (!category.isEmpty()) sql += " AND p.category_code = ?";
        // ORDER BY不能用?绑定列名，只拼接后端枚举中的固定SQL片段。
        sql += " ORDER BY " + sort.orderBy() + " LIMIT ? OFFSET ?";
        return category.isEmpty()
                ? jdbcTemplate.query(sql, this::mapProduct, searchPattern(keyword), size, offset)
                : jdbcTemplate.query(sql, this::mapProduct, searchPattern(keyword), category, size, offset);
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
            String coverImageId, String categoryCode
    ) {
        String sql = """
                INSERT INTO products (title, description, price_in_cents, seller_id, cover_image_id, category_code)
                VALUES (?, ?, ?, ?, ?, ?)
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
            statement.setString(6, categoryCode);

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

    public Optional<ProductDetailState> findDetailState(long id) {
        String sql = """
                SELECT p.id, p.status, p.version, u.id AS seller_id, u.nickname AS seller_nickname,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p LEFT JOIN users u ON u.id = p.seller_id
                WHERE p.id = ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Long sellerId = rs.getObject("seller_id", Long.class);
            SellerResponse seller = sellerId == null ? null
                    : new SellerResponse(sellerId, rs.getString("seller_nickname"));
            return new ProductDetailState(rs.getLong("id"), ProductStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"), seller, rs.getLong("favorite_count"));
        }, id).stream().findFirst();
    }

    // 必须在事务中调用，行锁持续到事务结束。
    public Optional<ProductResponse> findByIdForUpdate(long id) {
        return findById(id, true);
    }

    private Optional<ProductResponse> findById(long id, boolean lock) {
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version, p.category_code,
                       u.id AS seller_id, u.nickname AS seller_nickname,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
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
        String category = findById(id).orElseThrow().category().code();
        return updateOwnedProduct(id, sellerId, title, description, priceInCents, coverImageId, version, category);
    }

    public int updateOwnedProduct(long id, long sellerId, String title,
                                  String description, long priceInCents, String coverImageId,
                                  long version, String categoryCode) {
        return jdbcTemplate.update("""
                UPDATE products SET title = ?, description = ?, price_in_cents = ?,
                                    cover_image_id = ?, category_code = ?, version = version + 1
                WHERE id = ? AND seller_id = ? AND version = ? AND version < 9007199254740991
                  AND status <> 'SOLD'
                """, title, description, priceInCents, coverImageId, categoryCode, id, sellerId, version);
    }
    public long countBySeller(long sellerId) {
        return countBySeller(sellerId, null);
    }

    public long countBySeller(long sellerId, ProductStatus status) {
        return countBySeller(sellerId, status, "");
    }

    public long countBySeller(long sellerId, ProductStatus status, String keyword) {
        String sql = "SELECT COUNT(*) FROM products WHERE seller_id = ? AND title LIKE ? ESCAPE '!'";
        Long total = status == null ? jdbcTemplate.queryForObject(sql, Long.class, sellerId, searchPattern(keyword))
                : jdbcTemplate.queryForObject(sql + " AND status = ?", Long.class, sellerId, searchPattern(keyword), status.name());
        return total == null ? 0 : total;
    }

    public List<ProductResponse> findPageBySeller(long sellerId, int size, long offset) {
        return findPageBySeller(sellerId, null, "", size, offset);
    }

    public List<ProductResponse> findPageBySeller(long sellerId, ProductStatus status, int size, long offset) {
        return findPageBySeller(sellerId, status, "", size, offset);
    }

    public List<ProductResponse> findPageBySeller(long sellerId, ProductStatus status, String keyword, int size, long offset) {
        return findPageBySeller(sellerId, status, keyword, ProductOwnerSort.NEWEST, size, offset);
    }

    public List<ProductResponse> findPageBySeller(long sellerId, ProductStatus status, String keyword, ProductOwnerSort sort, int size, long offset) {
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version, p.category_code,
                       u.id AS seller_id, u.nickname AS seller_nickname,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p
                JOIN users u ON u.id = p.seller_id
                WHERE p.seller_id = ? AND p.title LIKE ? ESCAPE '!'
                """;
        if (status != null) sql += " AND p.status = ?";
        // 排序列不能通过?绑定，只允许拼接后端枚举中的固定SQL。
        sql += " ORDER BY " + sort.orderBy() + " LIMIT ? OFFSET ?";
        return status == null ? jdbcTemplate.query(sql, this::mapProduct, sellerId, searchPattern(keyword), size, offset)
                : jdbcTemplate.query(sql, this::mapProduct, sellerId, searchPattern(keyword), status.name(), size, offset);
    }

    public ProductOwnerSummaryResponse summarizeBySeller(long sellerId) {
        // 一次聚合读取全部发布，不关联收藏/留言，避免一对多JOIN重复计数。
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN status = 'ON_SALE' THEN 1 ELSE 0 END), 0) AS on_sale,
                       COALESCE(SUM(CASE WHEN status = 'OFF_SHELF' THEN 1 ELSE 0 END), 0) AS off_shelf,
                       COALESCE(SUM(CASE WHEN status = 'SOLD' THEN 1 ELSE 0 END), 0) AS sold,
                       COALESCE(SUM(CASE WHEN status = 'SOLD' THEN price_in_cents ELSE 0 END), 0) AS sold_price
                FROM products WHERE seller_id = ?
                """, (rs, row) -> new ProductOwnerSummaryResponse(rs.getLong("total"), rs.getLong("on_sale"),
                rs.getLong("off_shelf"), rs.getLong("sold"), rs.getLong("sold_price")), sellerId);
    }

    public int updateOwnedStatus(long id, long sellerId, ProductStatus status, long version) {
        return jdbcTemplate.update("""
                UPDATE products SET status = ?, version = version + 1
                WHERE id = ? AND seller_id = ? AND version = ? AND version < 9007199254740991
                  AND status <> 'SOLD'
                """, status.name(), id, sellerId, version);
    }

    public long countOnSaleBySeller(long sellerId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE seller_id = ? AND status = 'ON_SALE'",
                Long.class, sellerId);
        return total == null ? 0 : total;
    }

    public List<ProductResponse> findPublicPageBySeller(long sellerId, ProductSort sort, int size, long offset) {
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code,
                       u.id AS seller_id, u.nickname AS seller_nickname,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p JOIN users u ON u.id = p.seller_id
                WHERE p.seller_id = ? AND p.status = 'ON_SALE'
                """;
        sql += " ORDER BY " + sort.orderBy() + " LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, this::mapProduct, sellerId, size, offset);
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
                rs.getLong("version"), ProductCategory.parse(rs.getString("category_code")).response(),
                rs.getLong("favorite_count")
        );
    }
}
