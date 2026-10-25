CREATE TABLE account_email_bindings (
 user_id BIGINT PRIMARY KEY,
 email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 verified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_account_email(email),
 CONSTRAINT fk_email_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE account_email_challenges (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_version BIGINT NOT NULL,
 code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 consumed_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NOT NULL,
 KEY idx_email_challenge_user(user_id,purpose,created_at),
 KEY idx_email_challenge_expiry(expires_at),
 CONSTRAINT fk_email_challenge_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT chk_email_purpose CHECK(purpose IN ('BIND','UNBIND','RECOVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
