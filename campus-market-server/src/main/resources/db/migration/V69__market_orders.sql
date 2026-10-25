-- Frozen migration adapted from sql/lesson69-market-orders.sql. Do not edit after applying.
-- 第69课：新增订单表，保留原有商品、账号及购买意向数据。

CREATE TABLE market_orders (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_title_snapshot VARCHAR(50) NOT NULL,
 price_in_cents_snapshot BIGINT NOT NULL,
 product_version_snapshot BIGINT NOT NULL,
 buyer_nickname_snapshot VARCHAR(30) NOT NULL,
 seller_nickname_snapshot VARCHAR(30) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 cancelled_at DATETIME(6) NULL,
 active_product_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN status <> 'CANCELLED' THEN product_id ELSE NULL END) STORED,
 CONSTRAINT uq_order_active_product UNIQUE(active_product_id),
 CONSTRAINT uq_order_buyer_request UNIQUE(buyer_id,request_id),
 CONSTRAINT fk_order_product FOREIGN KEY(product_id) REFERENCES products(id),
 CONSTRAINT fk_order_buyer FOREIGN KEY(buyer_id) REFERENCES users(id),
 CONSTRAINT fk_order_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_order_participants CHECK(buyer_id <> seller_id),
 CONSTRAINT chk_order_price CHECK(price_in_cents_snapshot BETWEEN 1 AND 99999999),
 CONSTRAINT chk_order_version CHECK(product_version_snapshot BETWEEN 0 AND 9007199254740991),
 CONSTRAINT chk_order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED')),
 CONSTRAINT chk_order_cancelled CHECK(
  (status='PENDING_PAYMENT' AND cancelled_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL)),
 INDEX idx_order_buyer_created(buyer_id,created_at DESC,id DESC),
 INDEX idx_order_seller_created(seller_id,created_at DESC,id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
