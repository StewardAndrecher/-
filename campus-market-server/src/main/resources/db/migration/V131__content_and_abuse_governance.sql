CREATE TABLE account_write_restrictions (
 user_id BIGINT PRIMARY KEY,
 restricted_until DATETIME(6) NULL,
 reason VARCHAR(200) NOT NULL DEFAULT '',
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_restriction_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE account_restriction_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL,
 appeal_version BIGINT NULL,
 reason VARCHAR(200) NOT NULL,
 restricted_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_restriction_events(user_id,id),
 UNIQUE KEY uk_account_appeal(user_id,appeal_version),
 CONSTRAINT fk_restriction_event_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_restriction_event_actor FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE public_write_windows (
 user_id BIGINT NOT NULL,
 action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 bucket BIGINT NOT NULL,
 uses INT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,action,bucket),
 CONSTRAINT fk_write_window_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
