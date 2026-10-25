-- Each participant keeps one immutable issue report per paid order. No historical backfill.
CREATE TABLE order_issues (
 order_id BIGINT NOT NULL,
 applicant_id BIGINT NOT NULL,
 reason VARCHAR(24) NOT NULL,
 description VARCHAR(1000) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_id,applicant_id),
 CONSTRAINT fk_order_issue_order FOREIGN KEY(order_id) REFERENCES market_orders(id),
 CONSTRAINT fk_order_issue_applicant FOREIGN KEY(applicant_id) REFERENCES users(id),
 CONSTRAINT chk_order_issue_reason CHECK(reason IN ('ITEM_CONDITION','HANDOVER','OTHER')),
 CONSTRAINT chk_order_issue_description CHECK(CHAR_LENGTH(description) BETWEEN 10 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
