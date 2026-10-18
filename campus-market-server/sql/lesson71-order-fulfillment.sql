-- 第71课：卖家交付与买家收货。单条ALTER保留既有订单字段。
USE campus_market;
ALTER TABLE market_orders
 DROP CHECK chk_order_status,
 DROP CHECK chk_order_lifecycle,
 ADD COLUMN delivered_at DATETIME(6) NULL,
 ADD COLUMN completed_at DATETIME(6) NULL,
 MODIFY COLUMN active_product_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING_PAYMENT','PAID','DELIVERED','COMPLETED') THEN product_id ELSE NULL END) STORED,
 ADD CONSTRAINT chk_order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED','PAID','CLOSED_TIMEOUT','DELIVERED','COMPLETED')),
 ADD CONSTRAINT chk_order_lifecycle CHECK(
  (status='PENDING_PAYMENT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL AND delivered_at IS NULL AND completed_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL AND delivered_at IS NULL AND completed_at IS NULL) OR
  (status='CLOSED_TIMEOUT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NOT NULL AND closed_at >= payment_due_at AND payment_kind IS NULL AND delivered_at IS NULL AND completed_at IS NULL) OR
  (status IN ('PAID','DELIVERED','COMPLETED') AND cancelled_at IS NULL AND paid_at IS NOT NULL AND paid_at < payment_due_at AND closed_at IS NULL AND payment_kind IS NOT NULL AND payment_kind='SIMULATED' AND (
   (status='PAID' AND delivered_at IS NULL AND completed_at IS NULL) OR
   (status='DELIVERED' AND delivered_at IS NOT NULL AND delivered_at >= paid_at AND completed_at IS NULL) OR
   (status='COMPLETED' AND delivered_at IS NOT NULL AND delivered_at >= paid_at AND completed_at IS NOT NULL AND completed_at >= delivered_at)
  ))
 );
