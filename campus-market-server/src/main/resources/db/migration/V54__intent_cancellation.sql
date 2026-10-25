-- Frozen migration adapted from sql/lesson54-intent-cancellation.sql. Do not edit after applying.
-- 第54课：保留所有意向记录和原快照，只增加取消状态和时间。

ALTER TABLE purchase_intents
 DROP CHECK chk_intent_status,
 ADD COLUMN cancelled_at DATETIME(6) NULL,
 ADD CONSTRAINT chk_intent_status CHECK(status IN ('PENDING','CANCELLED')),
 ADD CONSTRAINT chk_intent_cancellation CHECK(
  (status='PENDING' AND cancelled_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL)
 );
