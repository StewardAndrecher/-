-- Frozen migration adapted from sql/lesson38-product-publications.sql. Do not edit after applying.
-- 仅新增回执表；首次发布和回执写入必须在同一事务。
CREATE TABLE product_publications (
    seller_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    title VARCHAR(50) NOT NULL,
    description VARCHAR(500) NOT NULL,
    price_in_cents BIGINT NOT NULL,
    cover_image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    category_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (seller_id, request_id),
    UNIQUE KEY uk_publication_product (product_id),
    CONSTRAINT fk_publication_seller FOREIGN KEY (seller_id) REFERENCES users(id),
    CONSTRAINT fk_publication_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_publication_price CHECK (price_in_cents BETWEEN 1 AND 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
