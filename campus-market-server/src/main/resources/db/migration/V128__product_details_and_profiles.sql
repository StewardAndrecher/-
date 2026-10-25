-- Additive migration: keep historical product/user values unchanged.
ALTER TABLE products
 ADD COLUMN campus_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN condition_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN handover_location VARCHAR(80) NOT NULL DEFAULT '',
 ADD CONSTRAINT chk_product_campus CHECK (campus_code IN ('UNSPECIFIED','EAST','WEST','OTHER')),
 ADD CONSTRAINT chk_product_condition CHECK (condition_code IN ('UNSPECIFIED','NEW','LIKE_NEW','GOOD','FAIR')),
 ADD INDEX idx_products_campus_newest (status,campus_code,created_at DESC,id DESC);
CREATE TABLE product_gallery (
 product_id BIGINT NOT NULL,
 position TINYINT NOT NULL,
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(product_id,position),
 UNIQUE KEY uk_product_gallery_image(product_id,image_id),
 CONSTRAINT fk_gallery_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE,
 CONSTRAINT fk_gallery_image FOREIGN KEY(image_id) REFERENCES product_images(id),
 CONSTRAINT chk_gallery_position CHECK(position BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO product_gallery(product_id,position,image_id)
 SELECT id,0,cover_image_id FROM products WHERE cover_image_id IS NOT NULL;
ALTER TABLE product_publications ADD COLUMN details_json JSON NULL;
ALTER TABLE product_draft_backups ADD COLUMN details_json JSON NULL;
ALTER TABLE users
 ADD COLUMN avatar_image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN bio VARCHAR(160) NOT NULL DEFAULT '',
 ADD COLUMN campus_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT fk_user_avatar FOREIGN KEY(avatar_image_id) REFERENCES product_images(id),
 ADD CONSTRAINT chk_user_campus CHECK(campus_code IN ('UNSPECIFIED','EAST','WEST','OTHER')),
 ADD CONSTRAINT chk_profile_version CHECK(profile_version BETWEEN 0 AND 9007199254740991);
CREATE TABLE account_recovery_codes (
 user_id BIGINT NOT NULL PRIMARY KEY,
 code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_recovery_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
