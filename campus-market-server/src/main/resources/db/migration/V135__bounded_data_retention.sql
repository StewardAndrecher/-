-- Retention is explicit and bounded; financial records and audit history are never deleted by this policy.
CREATE TABLE database_maintenance_runs (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 policy_version INT NOT NULL,
 cutoff_at DATETIME(6) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 summary_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT chk_maintenance_status CHECK(status IN ('COMPLETED','NO_CHANGES'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE login_sessions ADD INDEX idx_session_retention(revoked_at,token_hash);
ALTER TABLE account_email_challenges ADD INDEX idx_email_retention(expires_at,id);
ALTER TABLE public_write_windows ADD INDEX idx_write_window_retention(bucket,user_id);
ALTER TABLE notification_outbox ADD INDEX idx_notification_retention(delivered_at,event_id);
ALTER TABLE order_timeout_outbox ADD INDEX idx_timeout_retention(published_at,event_id);
