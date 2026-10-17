USE campus_market;
CREATE TABLE product_draft_backups (
 user_id BIGINT NOT NULL,
 draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_revision BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 title VARCHAR(50) NOT NULL,
 description VARCHAR(500) NOT NULL,
 price_text VARCHAR(9) NOT NULL,
 cover_image_id CHAR(36) NULL,
 category_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id,draft_id),
 KEY idx_draft_owner_updated(user_id,updated_at,draft_id),
 CONSTRAINT fk_draft_owner FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT chk_draft_revision CHECK(source_revision BETWEEN 1 AND 9007199254740991),
 CONSTRAINT chk_draft_version CHECK(version BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
