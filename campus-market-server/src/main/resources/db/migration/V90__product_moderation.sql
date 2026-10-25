-- Post-publication review; existing products are unchanged and initially unrestricted.
CREATE TABLE product_moderation_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_role VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(200) NOT NULL,
    from_state VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_state VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_version BIGINT NOT NULL,
    to_version BIGINT NOT NULL,
    product_snapshot JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_moderation_actor_request (actor_id, request_id),
    KEY idx_moderation_product_event (product_id, id),
    CONSTRAINT chk_moderation_event_identity CHECK (product_id > 0 AND actor_id > 0),
    CONSTRAINT chk_moderation_event_version CHECK (from_version >= 0 AND to_version = from_version + 1 AND to_version <= 9007199254740991),
    CONSTRAINT chk_moderation_event_transition CHECK (
        (action = 'BLOCK' AND actor_role = 'ADMIN' AND from_state = 'CLEAR' AND to_state = 'BLOCKED') OR
        (action = 'APPEAL' AND actor_role = 'SELLER' AND from_state = 'BLOCKED' AND to_state = 'APPEALED') OR
        (action = 'RELEASE' AND actor_role = 'ADMIN' AND from_state IN ('BLOCKED','APPEALED') AND to_state = 'CLEAR')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_moderation (
    product_id BIGINT NOT NULL,
    state VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(200) NOT NULL,
    appeal_reason VARCHAR(200) NULL,
    last_event_id BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    KEY idx_moderation_state_product (state, product_id),
    CONSTRAINT fk_moderation_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_moderation_last_event FOREIGN KEY (last_event_id) REFERENCES product_moderation_events(id),
    CONSTRAINT chk_moderation_state CHECK (state IN ('CLEAR','BLOCKED','APPEALED')),
    CONSTRAINT chk_moderation_appeal CHECK ((state = 'APPEALED' AND appeal_reason IS NOT NULL) OR (state <> 'APPEALED' AND appeal_reason IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
