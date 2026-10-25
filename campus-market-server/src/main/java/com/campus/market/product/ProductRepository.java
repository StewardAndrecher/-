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
import java.util.ArrayList;

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
        if (sort == ProductSort.NEWEST) return findNewestPage(keyword, category, size, offset);
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
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

    // LIMIT forces this derived table to materialize: decorate only the selected page.
    // The outer ORDER BY preserves the public ordering; derived-table order alone is not guaranteed.
    private List<ProductResponse> findNewestPage(String keyword, String category, int size, long offset) {
        String page = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location, p.seller_id, p.created_at
                FROM products p
                WHERE p.status = 'ON_SALE' AND p.title LIKE ? ESCAPE '!'
                """;
        if (!category.isEmpty()) page += " AND p.category_code = ?";
        page += " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?";
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM (
                """ + page + """
                ) p LEFT JOIN users u ON u.id = p.seller_id
                ORDER BY p.created_at DESC, p.id DESC
                """;
        return category.isEmpty()
                ? jdbcTemplate.query(sql, this::mapProduct, searchPattern(keyword), size, offset)
                : jdbcTemplate.query(sql, this::mapProduct, searchPattern(keyword), category, size, offset);
    }

    public record FeedRow(ProductResponse product, long epochSecond) {}

    public List<FeedRow> findFeed(String keyword, String category, ProductFeedCursor cursor, int limit) {
        String page = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location, p.seller_id, p.created_at
                FROM products p
                WHERE p.status = 'ON_SALE' AND p.title LIKE ? ESCAPE '!'
                """;
        var arguments = new java.util.ArrayList<Object>();
        arguments.add(searchPattern(keyword));
        if (!category.isEmpty()) {page += " AND p.category_code = ?"; arguments.add(category);}
        if (cursor != null) {
            // Compare raw indexed columns; apply the timezone conversion to constants, not created_at.
            page += " AND (p.created_at < FROM_UNIXTIME(?) OR (p.created_at = FROM_UNIXTIME(?) AND p.id < ?))";
            arguments.add(cursor.epochSecond()); arguments.add(cursor.epochSecond()); arguments.add(cursor.id());
        }
        page += " ORDER BY p.created_at DESC, p.id DESC LIMIT ?";
        arguments.add(limit);
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       UNIX_TIMESTAMP(p.created_at) AS cursor_epoch,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM (
                """ + page + """
                ) p LEFT JOIN users u ON u.id = p.seller_id
                ORDER BY p.created_at DESC, p.id DESC
                """;
        return jdbcTemplate.query(sql, (rs, row) -> new FeedRow(mapProduct(rs, row), rs.getLong("cursor_epoch")), arguments.toArray());
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
                SELECT p.id, p.status, p.version, u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p LEFT JOIN users u ON u.id = p.seller_id
                WHERE p.id = ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Long sellerId = rs.getObject("seller_id", Long.class);
            SellerResponse seller = sellerId == null ? null
                    : new SellerResponse(sellerId, rs.getString("seller_nickname"),rs.getString("seller_avatar"),rs.getString("seller_bio"),rs.getString("seller_campus"));
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
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
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
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status, p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
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

    public void requireNotModerated(long id) {
        if (!jdbcTemplate.queryForList("SELECT product_id FROM product_moderation WHERE product_id=? AND state IN ('BLOCKED','APPEALED')",Long.class,id).isEmpty())
            throw new ProductConflictException("商品受平台限制，暂不能上架或标记售出，请查看平台处理记录并申请复核");
    }

    public int updateOwnedStatus(long id, long sellerId, ProductStatus status, long version) {
        return jdbcTemplate.update("""
                UPDATE products SET status = ?, version = version + 1
                WHERE id = ? AND seller_id = ? AND version = ? AND version < 9007199254740991
                  AND status <> 'SOLD'
                """, status.name(), id, sellerId, version);
    }

    public long countOnSaleBySeller(long sellerId) {
        return countOnSaleBySeller(sellerId, null);
    }

    private static String publicSellerCategoryClause(ProductCategory category) {
        return category == null ? "" : " AND p.category_code = ?";
    }

    public long countOnSaleBySeller(long sellerId, ProductCategory category) {
        String sql = "SELECT COUNT(*) FROM products p WHERE p.seller_id = ? AND p.status = 'ON_SALE'";
        Long total = category == null ? jdbcTemplate.queryForObject(sql, Long.class, sellerId)
                : jdbcTemplate.queryForObject(sql + publicSellerCategoryClause(category), Long.class, sellerId, category.name());
        return total == null ? 0 : total;
    }

    public List<ProductResponse> findPublicPageBySeller(long sellerId, ProductSort sort, int size, long offset) {
        return findPublicPageBySeller(sellerId, sort, size, offset, null);
    }

    public List<ProductResponse> findPublicPageBySeller(long sellerId, ProductSort sort, int size, long offset, ProductCategory category) {
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p JOIN users u ON u.id = p.seller_id
                WHERE p.seller_id = ? AND p.status = 'ON_SALE'
                """;
        List<Object> args = new ArrayList<>(); args.add(sellerId);
        if (category != null) args.add(category.name());
        args.add(size); args.add(offset);
        sql += publicSellerCategoryClause(category) + " ORDER BY " + sort.orderBy() + " LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, this::mapProduct, args.toArray());
    }

    // A single read of public products; request order is restored by the service.
    public List<ProductResponse> findPublicByIds(List<Long> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
                       p.cover_image_id, p.version, p.category_code, p.campus_code, p.condition_code, p.handover_location,
                       u.id AS seller_id, u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                       (SELECT COUNT(*) FROM product_favorites pf WHERE pf.product_id = p.id) AS favorite_count
                FROM products p LEFT JOIN users u ON u.id = p.seller_id
                WHERE p.status = 'ON_SALE' AND p.id IN (
                """ + placeholders + ")";
        return jdbcTemplate.query(sql, this::mapProduct, ids.toArray());
    }

    public void saveDetails(long id,ProductDetails details) {
        jdbcTemplate.update("UPDATE products SET campus_code=?,condition_code=?,handover_location=? WHERE id=?",details.campusCode(),details.conditionCode(),details.handoverLocation(),id);
        jdbcTemplate.update("DELETE FROM product_gallery WHERE product_id=?",id);
        for(int position=0;position<details.imageIds().size();position++)jdbcTemplate.update("INSERT INTO product_gallery(product_id,position,image_id) VALUES(?,?,?)",id,position,details.imageIds().get(position));
    }
    public ProductPageResponse campusPage(String keyword,String category,String campus,ProductSort sort,int page,int size) {
        String where=" WHERE p.status='ON_SALE' AND p.title LIKE ? ESCAPE '!' AND p.campus_code=?";
        var args=new ArrayList<Object>();args.add(searchPattern(keyword));args.add(campus);
        if(!category.isEmpty()){where+=" AND p.category_code=?";args.add(category);}
        long total=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products p"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        String sql="""
          SELECT p.id,p.title,p.description,p.price_in_cents,p.status,p.cover_image_id,p.version,p.category_code,p.campus_code,p.condition_code,p.handover_location,
           u.id AS seller_id,u.nickname AS seller_nickname,u.avatar_image_id AS seller_avatar,u.bio AS seller_bio,u.campus_code AS seller_campus,
           (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
           (SELECT COUNT(*) FROM product_favorites f WHERE f.product_id=p.id) AS favorite_count
          FROM products p LEFT JOIN users u ON u.id=p.seller_id
          """+where+" ORDER BY "+sort.orderBy()+" LIMIT ? OFFSET ?";
        return new ProductPageResponse(jdbcTemplate.query(sql,this::mapProduct,args.toArray()),total,page,size);
    }
    public ProductResponse mapProduct(ResultSet rs, int rowNum) throws SQLException {
        Long sellerId = rs.getObject("seller_id", Long.class);
        SellerResponse seller = sellerId == null ? null
                : new SellerResponse(sellerId, rs.getString("seller_nickname"),rs.getString("seller_avatar"),rs.getString("seller_bio"),rs.getString("seller_campus"));
        return new ProductResponse(
                rs.getLong("id"), rs.getString("title"),
                rs.getString("description"), rs.getLong("price_in_cents"), seller,
                ProductStatus.valueOf(rs.getString("status")),
                rs.getString("cover_image_id"),
                rs.getString("cover_image_id") == null ? null : "/api/images/" + rs.getString("cover_image_id"),
                rs.getLong("version"), ProductCategory.parse(rs.getString("category_code")).response(),
                rs.getLong("favorite_count"), new ProductDetails(rs.getString("campus_code"),rs.getString("condition_code"),rs.getString("handover_location"),
                rs.getString("gallery_ids")==null?ProductDetails.legacy(rs.getString("cover_image_id")).imageIds():List.of(rs.getString("gallery_ids").split(",")))
        );
    }
}
