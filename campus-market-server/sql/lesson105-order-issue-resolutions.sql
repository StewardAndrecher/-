-- Append a single audited resolution; never overwrite the original issue description.
CREATE TABLE order_issue_resolutions (
 order_id BIGINT NOT NULL,
 applicant_id BIGINT NOT NULL,
 resolver_id BIGINT NOT NULL,
 decision VARCHAR(16) NOT NULL,
 explanation VARCHAR(1000) NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_id,applicant_id),
 UNIQUE KEY uq_issue_resolution_request(resolver_id,request_id),
 CONSTRAINT fk_issue_resolution_issue FOREIGN KEY(order_id,applicant_id) REFERENCES order_issues(order_id,applicant_id),
 CONSTRAINT fk_issue_resolution_admin FOREIGN KEY(resolver_id) REFERENCES users(id),
 CONSTRAINT chk_issue_resolution_decision CHECK(decision IN ('ANSWERED','DISMISSED')),
 CONSTRAINT chk_issue_resolution_explanation CHECK(CHAR_LENGTH(explanation) BETWEEN 10 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
