-- Campus Market：仅用于全新campus_market数据库。
-- 在仓库根目录使用MySQL客户端批量执行；不包含真实用户、会话或商品数据。

-- ===== 00-create-database-and-products.sql =====
-- 仅用于全新数据库。已有数据库按课程顺序迁移，不要重复初始化。
CREATE DATABASE campus_market CHARACTER SET utf8mb4;
USE campus_market;
CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(50) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    price_in_cents BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_products_price CHECK (price_in_cents BETWEEN 1 AND 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson15-users.sql =====
USE campus_market;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nickname VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    CONSTRAINT chk_users_username CHECK (
        REGEXP_LIKE(username, '^[a-z0-9_]{4,32}$', 'c')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson19-login-sessions.sql =====
USE campus_market;

CREATE TABLE IF NOT EXISTS login_sessions (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (token_hash),
    KEY idx_sessions_user (user_id),
    KEY idx_sessions_expiry (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson21-product-seller.sql =====
-- 第21课：在已有campus_market数据库执行一次。
-- 迁移前备份products；历史商品的seller_id保持NULL，不推测原卖家。
USE campus_market;

ALTER TABLE products
    ADD COLUMN seller_id BIGINT NULL,
    ADD INDEX idx_products_seller_id (seller_id),
    ADD CONSTRAINT fk_products_seller
        FOREIGN KEY (seller_id) REFERENCES users(id)
        ON DELETE RESTRICT;

-- ===== lesson23-product-status.sql =====
-- 第23课：在已有campus_market数据库执行一次。
-- 历史商品和新发布商品默认在售；下架只改变状态，不删除商品。
USE campus_market;
ALTER TABLE products
    ADD COLUMN status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ON_SALE',
    ADD CONSTRAINT chk_products_status CHECK (CAST(status AS BINARY) IN ('ON_SALE', 'OFF_SHELF')),
    ADD INDEX idx_products_status_id (status, id);

-- ===== lesson24-product-cover.sql =====
USE campus_market;
CREATE TABLE product_images (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_images_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_image_dimensions CHECK (width BETWEEN 1 AND 2000 AND height BETWEEN 1 AND 2000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE products
    ADD COLUMN cover_image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_product_cover FOREIGN KEY (cover_image_id) REFERENCES product_images(id) ON DELETE RESTRICT;

-- ===== lesson25-product-version.sql =====
-- 第25课：执行一次；历史商品与新商品从版本0开始。
USE campus_market;
ALTER TABLE products
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_products_version
        CHECK (version BETWEEN 0 AND 9007199254740991);

-- ===== lesson26-product-favorites.sql =====
-- 第26课：只执行一次。收藏属于账号，不属于设备。
USE campus_market;
CREATE TABLE product_favorites (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, product_id),
    INDEX idx_favorites_user_created (user_id, created_at, product_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_product FOREIGN KEY (product_id)
        REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson27-product-comments.sql =====
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

-- ===== lesson28-comment-idempotency.sql =====
-- 第28课：执行一次。为历史留言补齐标识，再添加非空与唯一约束。
USE campus_market;
ALTER TABLE product_comments
    ADD COLUMN request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE product_comments SET request_id = UUID() WHERE request_id IS NULL;
ALTER TABLE product_comments
    MODIFY COLUMN request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT uk_comments_submission UNIQUE (product_id, author_id, request_id),
    ADD CONSTRAINT chk_comments_request_id CHECK (
        REGEXP_LIKE(request_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    );

-- ===== lesson31-product-category.sql =====
-- 先备份；本迁移只执行一次。旧商品不根据标题猜测分类。
USE campus_market;
ALTER TABLE products
    ADD COLUMN category_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD CONSTRAINT chk_products_category CHECK
        (category_code IN ('UNCLASSIFIED','BOOK','DIGITAL','DAILY','SPORT','OTHER')),
    ADD INDEX idx_products_category_status_id (category_code, status, id);

-- ===== lesson38-product-publications.sql =====
USE campus_market;

-- 仅新增回执表；首次发布和回执写入必须在同一事务。
CREATE TABLE product_publications (
    seller_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    title VARCHAR(50) NOT NULL,
    description VARCHAR(500) NOT NULL,
    price_in_cents BIGINT NOT NULL,
    cover_image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    category_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (seller_id, request_id),
    UNIQUE KEY uk_publication_product (product_id),
    CONSTRAINT fk_publication_seller FOREIGN KEY (seller_id) REFERENCES users(id),
    CONSTRAINT fk_publication_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_publication_price CHECK (price_in_cents BETWEEN 1 AND 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson43-product-sold.sql =====
-- 第43课：扩大允许的状态集合，不改变任何商品记录。
-- 用同一条ALTER同时替换约束，避免出现没有状态约束的中间表结构。
USE campus_market;
ALTER TABLE products
    DROP CHECK chk_products_status,
    ADD CONSTRAINT chk_products_status
        CHECK (CAST(status AS BINARY) IN ('ON_SALE', 'OFF_SHELF', 'SOLD'));

-- ===== lesson49-product-draft-backups.sql =====
USE campus_market;
CREATE TABLE product_draft_backups (
 user_id BIGINT NOT NULL,
 draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_revision BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 title VARCHAR(50) NOT NULL,
 description VARCHAR(500) NOT NULL,
 price_text VARCHAR(9) NOT NULL,
 cover_image_id CHAR(36) NULL,
 category_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id,draft_id),
 KEY idx_draft_owner_updated(user_id,updated_at,draft_id),
 CONSTRAINT fk_draft_owner FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT chk_draft_revision CHECK(source_revision BETWEEN 1 AND 9007199254740991),
 CONSTRAINT chk_draft_version CHECK(version BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson53-purchase-intents.sql =====
-- 第53课：只新增购买意向表，不修改商品、账号或原有业务记录。
USE campus_market;
CREATE TABLE purchase_intents (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 product_title_snapshot VARCHAR(50) NOT NULL,
 price_in_cents_snapshot BIGINT NOT NULL,
 product_version_snapshot BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT uq_purchase_intent_buyer_product UNIQUE(product_id,buyer_id),
 CONSTRAINT fk_intent_product FOREIGN KEY(product_id) REFERENCES products(id),
 CONSTRAINT fk_intent_buyer FOREIGN KEY(buyer_id) REFERENCES users(id),
 CONSTRAINT fk_intent_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_intent_participants CHECK(buyer_id<>seller_id),
 CONSTRAINT chk_intent_price CHECK(price_in_cents_snapshot BETWEEN 1 AND 99999999),
 CONSTRAINT chk_intent_version CHECK(product_version_snapshot BETWEEN 0 AND 9007199254740991),
 CONSTRAINT chk_intent_status CHECK(status='PENDING'),
 INDEX idx_intent_buyer_created(buyer_id,created_at DESC,id DESC),
 INDEX idx_intent_seller_created(seller_id,created_at DESC,id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson54-intent-cancellation.sql =====
-- 第54课：保留所有意向记录和原快照，只增加取消状态和时间。
USE campus_market;
ALTER TABLE purchase_intents
 DROP CHECK chk_intent_status,
 ADD COLUMN cancelled_at DATETIME(6) NULL,
 ADD CONSTRAINT chk_intent_status CHECK(status IN ('PENDING','CANCELLED')),
 ADD CONSTRAINT chk_intent_cancellation CHECK(
  (status='PENDING' AND cancelled_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL)
 );

-- ===== lesson55-intent-acceptance.sql =====
-- 第55课：保留待沟通和已取消记录，新增卖家接受状态与时间。
USE campus_market;
ALTER TABLE purchase_intents
 DROP CHECK chk_intent_status,
 DROP CHECK chk_intent_cancellation,
 ADD COLUMN accepted_at DATETIME(6) NULL,
 ADD CONSTRAINT chk_intent_status CHECK(status IN ('PENDING','CANCELLED','ACCEPTED')),
 ADD CONSTRAINT chk_intent_timestamps CHECK(
  (status='PENDING' AND cancelled_at IS NULL AND accepted_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL AND accepted_at IS NULL) OR
  (status='ACCEPTED' AND cancelled_at IS NULL AND accepted_at IS NOT NULL)
 );

-- ===== lesson59-intent-messages.sql =====
-- 第59课：意向私密留言；只新增表，不改写原8张业务表。
USE campus_market;
CREATE TABLE purchase_intent_messages (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 intent_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_intent_message_intent FOREIGN KEY(intent_id) REFERENCES purchase_intents(id),
 CONSTRAINT fk_intent_message_sender FOREIGN KEY(sender_id) REFERENCES users(id),
 CONSTRAINT uq_intent_message_request UNIQUE(intent_id,sender_id,request_id),
 CONSTRAINT chk_intent_message_content CHECK(CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 500),
 INDEX idx_intent_message_created(intent_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== lesson60-intent-message-withdrawal.sql =====
-- 第60课：保留原留言及请求编号，增加撤回状态和时间。
USE campus_market;
ALTER TABLE purchase_intent_messages
 ADD COLUMN is_withdrawn BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN withdrawn_at DATETIME(6) NULL,
 ADD CONSTRAINT chk_intent_message_withdrawal CHECK(
  (is_withdrawn=FALSE AND withdrawn_at IS NULL) OR
  (is_withdrawn=TRUE AND withdrawn_at IS NOT NULL)
 );

-- ===== lesson61-intent-message-read-state.sql =====
-- 第61课：每个意向、每个参与者各自保存单调递增的阅读进度。
USE campus_market;
CREATE TABLE purchase_intent_message_reads (
 intent_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 last_read_message_id BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(intent_id,user_id),
 CONSTRAINT fk_intent_read_intent FOREIGN KEY(intent_id) REFERENCES purchase_intents(id),
 CONSTRAINT fk_intent_read_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_intent_read_message FOREIGN KEY(last_read_message_id) REFERENCES purchase_intent_messages(id),
 CONSTRAINT chk_intent_read_message CHECK(last_read_message_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
