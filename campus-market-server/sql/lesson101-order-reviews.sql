USE campus_market;

-- Private feedback between completed-order participants; no historical backfill.
CREATE TABLE order_reviews (
 order_id BIGINT NOT NULL,
 reviewer_id BIGINT NOT NULL,
 rating TINYINT UNSIGNED NOT NULL,
 content VARCHAR(500) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_id,reviewer_id),
 CONSTRAINT fk_order_review_order FOREIGN KEY(order_id) REFERENCES market_orders(id),
 CONSTRAINT fk_order_review_user FOREIGN KEY(reviewer_id) REFERENCES users(id),
 CONSTRAINT chk_order_review_rating CHECK(rating BETWEEN 1 AND 5),
 CONSTRAINT chk_order_review_content CHECK(CHAR_LENGTH(content)<=500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
