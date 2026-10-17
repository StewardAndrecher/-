-- 第55课：保留待沟通和已取消记录，新增卖家接受状态与时间。
USE campus_market;
ALTER TABLE purchase_intents
 DROP CHECK chk_intent_status,
 DROP CHECK chk_intent_cancellation,
 ADD COLUMN accepted_at DATETIME(6) NULL,
 ADD CONSTRAINT chk_intent_status CHECK(status IN ('PENDING','CANCELLED','ACCEPTED')),
 ADD CONSTRAINT chk_intent_timestamps CHECK(
  (status='PENDING' AND cancelled_at IS NULL AND accepted_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL AND accepted_at IS NULL) OR
  (status='ACCEPTED' AND cancelled_at IS NULL AND accepted_at IS NOT NULL)
 );
