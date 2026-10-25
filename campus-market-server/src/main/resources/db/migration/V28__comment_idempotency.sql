-- Frozen migration adapted from sql/lesson28-comment-idempotency.sql. Do not edit after applying.
-- 第28课：执行一次。为历史留言补齐标识，再添加非空与唯一约束。

ALTER TABLE product_comments
    ADD COLUMN request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE product_comments SET request_id = UUID() WHERE request_id IS NULL;
ALTER TABLE product_comments
    MODIFY COLUMN request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT uk_comments_submission UNIQUE (product_id, author_id, request_id),
    ADD CONSTRAINT chk_comments_request_id CHECK (
        REGEXP_LIKE(request_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    );
