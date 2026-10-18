-- 第73课：先停旧后端，再建表并补录历史快照，最后启动新后端。
USE campus_market;
CREATE TABLE order_events (
 order_id BIGINT NOT NULL,
 event_no INT NOT NULL,
 action VARCHAR(32) NOT NULL,
 from_status VARCHAR(24) NULL,
 to_status VARCHAR(24) NOT NULL,
 actor_role VARCHAR(8) NOT NULL,
 actor_id BIGINT NULL,
 actor_nickname VARCHAR(50) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_id,event_no),
 CONSTRAINT uq_order_event_action UNIQUE(order_id,action),
 CONSTRAINT fk_event_order FOREIGN KEY(order_id) REFERENCES market_orders(id) ON DELETE CASCADE,
 CONSTRAINT fk_event_actor FOREIGN KEY(actor_id) REFERENCES users(id),
 CONSTRAINT chk_event_no CHECK(event_no BETWEEN 1 AND 4),
 CONSTRAINT chk_event_time CHECK(recorded_at >= occurred_at),
 CONSTRAINT chk_event_actor CHECK(
  (actor_role IN ('BUYER','SELLER') AND actor_id IS NOT NULL) OR
  (actor_role='SYSTEM' AND actor_id IS NULL)
 ),
 CONSTRAINT chk_event_transition CHECK(
  (action='CREATED' AND event_no=1 AND from_status IS NULL AND to_status='PENDING_PAYMENT' AND actor_role='BUYER') OR
  (action='SIMULATED_PAYMENT' AND from_status IS NOT NULL AND from_status='PENDING_PAYMENT' AND to_status='PAID' AND actor_role='BUYER') OR
  (action='DELIVERED' AND from_status IS NOT NULL AND from_status='PAID' AND to_status='DELIVERED' AND actor_role='SELLER') OR
  (action='COMPLETED' AND from_status IS NOT NULL AND from_status='DELIVERED' AND to_status='COMPLETED' AND actor_role='BUYER') OR
  (action='CANCELLED' AND from_status IS NOT NULL AND from_status='PENDING_PAYMENT' AND to_status='CANCELLED' AND actor_role='BUYER') OR
  (action='TIMEOUT_CLOSED' AND from_status IS NOT NULL AND from_status='PENDING_PAYMENT' AND to_status='CLOSED_TIMEOUT' AND actor_role='SYSTEM') OR
  (action='HISTORY_SNAPSHOT' AND event_no=1 AND from_status IS NULL AND to_status IN ('PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT') AND actor_role='SYSTEM')
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 这是迁移时观察到的状态，不伪造历史操作人或历史事件发生时间。
INSERT INTO order_events(order_id,event_no,action,from_status,to_status,actor_role,actor_id,actor_nickname,occurred_at,recorded_at)
 SELECT id,1,'HISTORY_SNAPSHOT',NULL,status,'SYSTEM',NULL,'迁移补录',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
 FROM market_orders;
