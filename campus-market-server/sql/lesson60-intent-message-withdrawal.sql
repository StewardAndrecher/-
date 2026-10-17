-- 第60课：保留原留言及请求编号，增加撤回状态和时间。
USE campus_market;
ALTER TABLE purchase_intent_messages
 ADD COLUMN is_withdrawn BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN withdrawn_at DATETIME(6) NULL,
 ADD CONSTRAINT chk_intent_message_withdrawal CHECK(
  (is_withdrawn=FALSE AND withdrawn_at IS NULL) OR
  (is_withdrawn=TRUE AND withdrawn_at IS NOT NULL)
 );
