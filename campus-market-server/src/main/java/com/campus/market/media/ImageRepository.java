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
    public Optional<Long> findOwnerForUpdate(String id) {
        return jdbc.queryForList("SELECT i.owner_id FROM product_images i LEFT JOIN image_storage_records s ON s.image_id=i.id WHERE i.id=? AND s.trashed_at IS NULL AND s.purged_at IS NULL FOR UPDATE",Long.class,id).stream().findFirst();
    }
    public Optional<Long> findOwner(String id) {
        return jdbc.queryForList("SELECT i.owner_id FROM product_images i LEFT JOIN image_storage_records s ON s.image_id=i.id WHERE i.id=? AND s.trashed_at IS NULL AND s.purged_at IS NULL", Long.class, id)
                .stream().findFirst();
    }
}
