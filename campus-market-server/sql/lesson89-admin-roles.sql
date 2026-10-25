USE campus_market;

CREATE TABLE user_role_grants (
    user_id BIGINT NOT NULL,
    role_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_role_grant_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_role_grant_code CHECK (role_code = 'ADMIN')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_role_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    target_user_id BIGINT NOT NULL,
    target_username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_source VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_role_audit_target (target_user_id, id),
    CONSTRAINT chk_role_audit_action CHECK (action IN ('GRANT', 'REVOKE')),
    CONSTRAINT chk_role_audit_operator CHECK (operator_source = 'LOCAL_OPERATOR')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
