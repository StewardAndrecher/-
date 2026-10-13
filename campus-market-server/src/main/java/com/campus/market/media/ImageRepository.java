package com.campus.market.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class ImageRepository {
    private final JdbcTemplate jdbc;
    public ImageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String id, long ownerId, int width, int height) {
        jdbc.update("INSERT INTO product_images(id, owner_id, width, height) VALUES(?,?,?,?)",
                id, ownerId, width, height);
    }
    public Optional<Long> findOwner(String id) {
        return jdbc.queryForList("SELECT owner_id FROM product_images WHERE id=?", Long.class, id)
                .stream().findFirst();
    }
}
