-- Frozen migration adapted from sql/lesson43-product-sold.sql. Do not edit after applying.
-- 第43课：扩大允许的状态集合，不改变任何商品记录。
-- 用同一条ALTER同时替换约束，避免出现没有状态约束的中间表结构。

ALTER TABLE products
    DROP CHECK chk_products_status,
    ADD CONSTRAINT chk_products_status
        CHECK (CAST(status AS BINARY) IN ('ON_SALE', 'OFF_SHELF', 'SOLD'));
