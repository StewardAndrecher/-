-- Product report tickets with immutable submission evidence and one terminal resolution.
CREATE TABLE product_reports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(16) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    product_snapshot JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    resolution VARCHAR(300) NULL,
    resolved_by BIGINT NULL,
    resolved_at DATETIME(6) NULL,
    resolution_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_product_version BIGINT NULL,
    moderation_event_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    open_product_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN product_id ELSE NULL END) STORED,
    CONSTRAINT fk_report_product FOREIGN KEY(product_id) REFERENCES products(id),
    CONSTRAINT fk_report_user FOREIGN KEY(reporter_id) REFERENCES users(id),
    CONSTRAINT fk_report_resolver FOREIGN KEY(resolved_by) REFERENCES users(id),
    CONSTRAINT fk_report_event FOREIGN KEY(moderation_event_id) REFERENCES product_moderation_events(id),
    CONSTRAINT uq_report_request UNIQUE(reporter_id,request_id),
    CONSTRAINT uq_report_pending UNIQUE(reporter_id,open_product_id),
    CONSTRAINT uq_report_resolution UNIQUE(resolved_by,resolution_request_id),
    INDEX idx_report_reporter(reporter_id,id DESC),
    INDEX idx_report_status(status,id DESC),
    CONSTRAINT chk_report_category CHECK(category IN ('MISLEADING','PROHIBITED','SPAM','OTHER')),
    CONSTRAINT chk_report_reason CHECK(CHAR_LENGTH(reason) BETWEEN 2 AND 300),
    CONSTRAINT chk_report_state CHECK(
      (status='OPEN' AND version=0 AND resolution IS NULL AND resolved_by IS NULL AND resolved_at IS NULL AND resolution_request_id IS NULL AND expected_product_version IS NULL AND moderation_event_id IS NULL)
      OR (status IN ('DISMISSED','ACTIONED') AND version=1 AND resolution IS NOT NULL AND CHAR_LENGTH(resolution) BETWEEN 2 AND 300 AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL AND resolved_at>=created_at AND resolution_request_id IS NOT NULL
          AND ((status='DISMISSED' AND expected_product_version IS NULL AND moderation_event_id IS NULL)
            OR (status='ACTIONED' AND expected_product_version IS NOT NULL AND expected_product_version BETWEEN 0 AND 9007199254740990 AND moderation_event_id IS NOT NULL))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
