package com.campus.market.product;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static com.campus.market.product.ProductModerationModels.*;

@Repository
public class ProductModerationRepository {
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final JsonMapper json=JsonMapper.builder().build();
    public ProductModerationRepository(JdbcTemplate jdbc, ProductRepository products) {this.jdbc=jdbc;this.products=products;}
    private static final String SELECT = """
            SELECT p.id,p.title,p.description,p.price_in_cents,p.status,p.cover_image_id,p.version,p.category_code, p.campus_code, p.condition_code, p.handover_location,
                   u.id AS seller_id,u.nickname AS seller_nickname, u.avatar_image_id AS seller_avatar, u.bio AS seller_bio, u.campus_code AS seller_campus,
                       (SELECT GROUP_CONCAT(g.image_id ORDER BY g.position SEPARATOR ',') FROM product_gallery g WHERE g.product_id=p.id) AS gallery_ids,
                   (SELECT COUNT(*) FROM product_favorites f WHERE f.product_id=p.id) AS favorite_count,
                   COALESCE(m.state,'CLEAR') AS moderation_state,COALESCE(m.reason,'') AS moderation_reason,
                   m.appeal_reason,COALESCE(m.last_event_id,0) AS last_event_id,m.updated_at AS moderation_updated_at
            FROM products p LEFT JOIN users u ON u.id=p.seller_id
            LEFT JOIN product_moderation m ON m.product_id=p.id
            """;
    public Item item(long productId) {
        return jdbc.query(SELECT+" WHERE p.id=?",(rs,row)->new Item(products.mapProduct(rs,row),
                new Review(rs.getString("moderation_state"),rs.getString("moderation_reason"),rs.getString("appeal_reason"),
                        rs.getLong("last_event_id"),rs.getString("moderation_updated_at"))),productId).stream().findFirst()
                .orElseThrow(()->new ProductNotFoundException("商品不存在"));
    }
    private String where(String status,String state) {
        return " WHERE p.title LIKE ? ESCAPE '!'"+(status.isEmpty()?"":" AND p.status=?")
                +(state.isEmpty()?"":" AND COALESCE(m.state,'CLEAR')=?");
    }
    private ArrayList<Object> filters(String keyword,String status,String state) {
        var values=new ArrayList<Object>();values.add("%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        if(!status.isEmpty())values.add(status);if(!state.isEmpty())values.add(state);return values;
    }
    public long count(String keyword,String status,String state) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM products p LEFT JOIN product_moderation m ON m.product_id=p.id"+where(status,state),Long.class,filters(keyword,status,state).toArray());
    }
    public List<Item> page(String keyword,String status,String state,int size,long offset) {
        var args=filters(keyword,status,state);args.add(size);args.add(offset);
        return jdbc.query(SELECT+where(status,state)+" ORDER BY p.id DESC LIMIT ? OFFSET ?",(rs,row)->new Item(products.mapProduct(rs,row),
                new Review(rs.getString("moderation_state"),rs.getString("moderation_reason"),rs.getString("appeal_reason"),
                        rs.getLong("last_event_id"),rs.getString("moderation_updated_at"))),args.toArray());
    }
    public List<Event> events(long productId) {
        return jdbc.query("SELECT * FROM product_moderation_events WHERE product_id=? ORDER BY id DESC LIMIT 20",(rs,row)->new Event(
                rs.getLong("id"),rs.getString("action"),rs.getString("actor_role"),rs.getString("reason"),rs.getString("from_state"),
                rs.getString("to_state"),rs.getLong("from_version"),rs.getLong("to_version"),rs.getString("created_at"),
                json.readValue(rs.getString("product_snapshot"),Evidence.class)),productId);
    }
    public Optional<Existing> existing(long actorId,String requestId) {
        return jdbc.query("SELECT product_id,action,reason,from_version FROM product_moderation_events WHERE actor_id=? AND request_id=?",
                (rs,row)->new Existing(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4)),actorId,requestId).stream().findFirst();
    }
    public long append(ProductResponse product,long actorId,String actorRole,String action,String reason,String from,String to,String requestId) {
        var key=new GeneratedKeyHolder();String snapshot=json.writeValueAsString(new Evidence(product.title(),product.description(),product.priceInCents(),product.category().code(),product.coverImageId()));
        jdbc.update(connection->{var statement=connection.prepareStatement("""
                INSERT INTO product_moderation_events(product_id,actor_id,actor_role,action,request_id,reason,from_state,to_state,from_version,to_version,product_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,product.id());statement.setLong(2,actorId);statement.setString(3,actorRole);statement.setString(4,action);
            statement.setString(5,requestId);statement.setString(6,reason);statement.setString(7,from);statement.setString(8,to);
            statement.setLong(9,product.version());statement.setLong(10,product.version()+1);statement.setString(11,snapshot);return statement;
        },key);return Objects.requireNonNull(key.getKey()).longValue();
    }
    public void state(long productId,String state,String reason,String appeal,long eventId) {
        jdbc.update("""
                INSERT INTO product_moderation(product_id,state,reason,appeal_reason,last_event_id) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE state=?,reason=?,appeal_reason=?,last_event_id=?,updated_at=CURRENT_TIMESTAMP(6)
                """,productId,state,reason,appeal,eventId,state,reason,appeal,eventId);
    }
    public int keepOffAndAdvance(long productId,long version) {
        return jdbc.update("UPDATE products SET status='OFF_SHELF',version=version+1 WHERE id=? AND version=? AND version<9007199254740991 AND status<>'SOLD'",productId,version);
    }
}
