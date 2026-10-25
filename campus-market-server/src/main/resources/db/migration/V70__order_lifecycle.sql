-- Frozen migration adapted from sql/lesson70-order-lifecycle.sql. Do not edit after applying.
-- 第70课：扩展订单状态；单条ALTER由MySQL原子DDL提交。

ALTER TABLE market_orders
 DROP CHECK chk_order_status,
 DROP CHECK chk_order_cancelled,
 ADD COLUMN payment_due_at DATETIME(6) GENERATED ALWAYS AS (created_at + INTERVAL 15 MINUTE) STORED,
 ADD COLUMN paid_at DATETIME(6) NULL,
 ADD COLUMN closed_at DATETIME(6) NULL,
 ADD COLUMN payment_kind VARCHAR(16) NULL,
 MODIFY COLUMN active_product_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING_PAYMENT','PAID') THEN product_id ELSE NULL END) STORED,
 ADD CONSTRAINT chk_order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED','PAID','CLOSED_TIMEOUT')),
 ADD CONSTRAINT chk_order_lifecycle CHECK(
  (status='PENDING_PAYMENT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL) OR
  (status='PAID' AND cancelled_at IS NULL AND paid_at IS NOT NULL AND paid_at < payment_due_at AND closed_at IS NULL AND payment_kind IS NOT NULL AND payment_kind='SIMULATED') OR
  (status='CLOSED_TIMEOUT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NOT NULL AND closed_at >= payment_due_at AND payment_kind IS NULL)
 ),
 ADD INDEX idx_order_timeout(status,payment_due_at,id);
