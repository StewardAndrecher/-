-- Frozen migration adapted from sql/lesson31-product-category.sql. Do not edit after applying.
-- 先备份；本迁移只执行一次。旧商品不根据标题猜测分类。

ALTER TABLE products
    ADD COLUMN category_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD CONSTRAINT chk_products_category CHECK
        (category_code IN ('UNCLASSIFIED','BOOK','DIGITAL','DAILY','SPORT','OTHER')),
    ADD INDEX idx_products_category_status_id (category_code, status, id);
