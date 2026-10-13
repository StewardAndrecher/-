-- 第21课：在已有campus_market数据库执行一次。
-- 迁移前备份products；历史商品的seller_id保持NULL，不推测原卖家。
USE campus_market;

ALTER TABLE products
    ADD COLUMN seller_id BIGINT NULL,
    ADD INDEX idx_products_seller_id (seller_id),
    ADD CONSTRAINT fk_products_seller
        FOREIGN KEY (seller_id) REFERENCES users(id)
        ON DELETE RESTRICT;
