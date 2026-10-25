USE campus_market;
CREATE TABLE image_storage_records (
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 byte_size BIGINT NOT NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 trashed_at DATETIME(6) NULL,
 purged_at DATETIME(6) NULL,
 physical_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 KEY idx_image_storage_trash(trashed_at,image_id),
 CONSTRAINT fk_storage_image FOREIGN KEY(image_id) REFERENCES product_images(id) ON DELETE CASCADE,
 CONSTRAINT chk_storage_size CHECK(byte_size BETWEEN 1 AND 10485760)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
