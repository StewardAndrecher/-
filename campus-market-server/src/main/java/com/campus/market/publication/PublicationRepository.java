package com.campus.market.publication;

import com.campus.market.product.CreateProductRequest;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PublicationRepository {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    public PublicationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lockPublisher(long sellerId) {
        return !jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE", (rs,n)->rs.getLong("id"), sellerId).isEmpty();
    }
    public Optional<StoredPublication> findForUpdate(long sellerId, String requestId) {
        return jdbc.query("""
                SELECT product_id,title,description,price_in_cents,cover_image_id,category_code,details_json
                FROM product_publications WHERE seller_id=? AND request_id=? FOR UPDATE
                """, (rs,n)->new StoredPublication(rs.getLong("product_id"), new CreateProductRequest(
                rs.getString("title"),rs.getString("description"),rs.getLong("price_in_cents"),
                rs.getString("cover_image_id"),rs.getString("category_code"),rs.getString("details_json")==null?com.campus.market.product.ProductDetails.legacy(rs.getString("cover_image_id")):json.readValue(rs.getString("details_json"),com.campus.market.product.ProductDetails.class))),sellerId,requestId).stream().findFirst();
    }
    public void insert(long sellerId, String requestId, long productId, CreateProductRequest product) {
        jdbc.update("""
                INSERT INTO product_publications(seller_id,request_id,product_id,title,description,
                    price_in_cents,cover_image_id,category_code,details_json) VALUES(?,?,?,?,?,?,?,?,?)
                """, sellerId,requestId,productId,product.title(),product.description(),product.priceInCents(),
                product.coverImageId(),product.categoryCode(),json.writeValueAsString(product.details()));
    }
    public record StoredPublication(long productId, CreateProductRequest product) {}
}
