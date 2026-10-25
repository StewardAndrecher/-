-- Frozen migration adapted from sql/lesson25-product-version.sql. Do not edit after applying.
-- 第25课：执行一次；历史商品与新商品从版本0开始。

ALTER TABLE products
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_products_version
        CHECK (version BETWEEN 0 AND 9007199254740991);
