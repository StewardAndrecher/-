-- 第59课：意向私密留言；只新增表，不改写原8张业务表。
USE campus_market;
CREATE TABLE purchase_intent_messages (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 intent_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_intent_message_intent FOREIGN KEY(intent_id) REFERENCES purchase_intents(id),
 CONSTRAINT fk_intent_message_sender FOREIGN KEY(sender_id) REFERENCES users(id),
 CONSTRAINT uq_intent_message_request UNIQUE(intent_id,sender_id,request_id),
 CONSTRAINT chk_intent_message_content CHECK(CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 500),
 INDEX idx_intent_message_created(intent_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
