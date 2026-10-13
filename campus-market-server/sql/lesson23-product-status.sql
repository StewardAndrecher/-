-- 第23课：在已有campus_market数据库执行一次。
-- 历史商品和新发布商品默认在售；下架只改变状态，不删除商品。
USE campus_market;
ALTER TABLE products
    ADD COLUMN status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ON_SALE',
    ADD CONSTRAINT chk_products_status CHECK (CAST(status AS BINARY) IN ('ON_SALE', 'OFF_SHELF')),
    ADD INDEX idx_products_status_id (status, id);
