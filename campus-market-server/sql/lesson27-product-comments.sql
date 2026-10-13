-- 第27课：只执行一次。留言属于商品，作者由登录会话确定。
USE campus_market;
CREATE TABLE product_comments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL DEFAULT NULL,
    INDEX idx_comments_product_page (product_id, is_deleted, created_at, id),
    CONSTRAINT chk_comments_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 500),
    CONSTRAINT chk_comments_deleted CHECK (
        (is_deleted = FALSE AND deleted_at IS NULL) OR
        (is_deleted = TRUE AND deleted_at IS NOT NULL)
    ),
    CONSTRAINT fk_comments_product FOREIGN KEY (product_id)
        REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id)
        REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
