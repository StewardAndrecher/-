-- Frozen V87 migration. Add future changes in a new version; never edit applied SQL.
-- 第87课：最新商品列表复合索引；已有受管库由Flyway执行一次。
-- 不删除旧索引；LIKE '%keyword%'仍不能依靠普通B树进行标题定位。
ALTER TABLE products
    ADD INDEX idx_products_status_created_id (status, created_at DESC, id DESC),
    ADD INDEX idx_products_category_status_created_id (category_code, status, created_at DESC, id DESC),
    ALGORITHM=INPLACE,
    LOCK=NONE;
