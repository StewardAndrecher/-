-- Frozen migration adapted from sql/lesson61-intent-message-read-state.sql. Do not edit after applying.
-- 第61课：每个意向、每个参与者各自保存单调递增的阅读进度。

CREATE TABLE purchase_intent_message_reads (
 intent_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 last_read_message_id BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(intent_id,user_id),
 CONSTRAINT fk_intent_read_intent FOREIGN KEY(intent_id) REFERENCES purchase_intents(id),
 CONSTRAINT fk_intent_read_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_intent_read_message FOREIGN KEY(last_read_message_id) REFERENCES purchase_intent_messages(id),
 CONSTRAINT chk_intent_read_message CHECK(last_read_message_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
