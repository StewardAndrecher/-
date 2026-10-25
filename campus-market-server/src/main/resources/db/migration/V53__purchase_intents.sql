-- Frozen migration adapted from sql/lesson53-purchase-intents.sql. Do not edit after applying.
-- 第53课：只新增购买意向表，不修改商品、账号或原有业务记录。

CREATE TABLE purchase_intents (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 product_title_snapshot VARCHAR(50) NOT NULL,
 price_in_cents_snapshot BIGINT NOT NULL,
 product_version_snapshot BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT uq_purchase_intent_buyer_product UNIQUE(product_id,buyer_id),
 CONSTRAINT fk_intent_product FOREIGN KEY(product_id) REFERENCES products(id),
 CONSTRAINT fk_intent_buyer FOREIGN KEY(buyer_id) REFERENCES users(id),
 CONSTRAINT fk_intent_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_intent_participants CHECK(buyer_id<>seller_id),
 CONSTRAINT chk_intent_price CHECK(price_in_cents_snapshot BETWEEN 1 AND 99999999),
 CONSTRAINT chk_intent_version CHECK(product_version_snapshot BETWEEN 0 AND 9007199254740991),
 CONSTRAINT chk_intent_status CHECK(status='PENDING'),
 INDEX idx_intent_buyer_created(buyer_id,created_at DESC,id DESC),
 INDEX idx_intent_seller_created(seller_id,created_at DESC,id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
