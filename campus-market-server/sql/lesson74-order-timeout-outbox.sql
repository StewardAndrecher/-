-- 第74课：与下单同事务的待投递任务；不向RabbitMQ做数据库事务内双写。
USE campus_market;
CREATE TABLE order_timeout_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 published_at DATETIME(6) NULL,
 last_error VARCHAR(40) NULL,
 CONSTRAINT uq_timeout_outbox_order UNIQUE(order_id),
 CONSTRAINT fk_timeout_outbox_order FOREIGN KEY(order_id) REFERENCES market_orders(id) ON DELETE CASCADE,
 CONSTRAINT chk_timeout_attempts CHECK(attempts>=0),
 CONSTRAINT chk_timeout_lease CHECK((lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
 INDEX idx_timeout_outbox_due(published_at,next_attempt_at,lease_until,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 历史待付款订单仍由原扫描任务补偿；不伪造历史消息。
