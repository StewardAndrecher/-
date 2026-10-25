-- Campus Market: ONLY for a new empty campus_market database.
-- Never execute this initialization on an existing business database.

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


-- ===== lesson69-market-orders.sql =====
-- 第69课：新增订单表，保留原有商品、账号及购买意向数据。
USE campus_market;
CREATE TABLE market_orders (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_title_snapshot VARCHAR(50) NOT NULL,
 price_in_cents_snapshot BIGINT NOT NULL,
 product_version_snapshot BIGINT NOT NULL,
 buyer_nickname_snapshot VARCHAR(30) NOT NULL,
 seller_nickname_snapshot VARCHAR(30) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 cancelled_at DATETIME(6) NULL,
 active_product_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN status <> 'CANCELLED' THEN product_id ELSE NULL END) STORED,
 CONSTRAINT uq_order_active_product UNIQUE(active_product_id),
 CONSTRAINT uq_order_buyer_request UNIQUE(buyer_id,request_id),
 CONSTRAINT fk_order_product FOREIGN KEY(product_id) REFERENCES products(id),
 CONSTRAINT fk_order_buyer FOREIGN KEY(buyer_id) REFERENCES users(id),
 CONSTRAINT fk_order_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_order_participants CHECK(buyer_id <> seller_id),
 CONSTRAINT chk_order_price CHECK(price_in_cents_snapshot BETWEEN 1 AND 99999999),
 CONSTRAINT chk_order_version CHECK(product_version_snapshot BETWEEN 0 AND 9007199254740991),
 CONSTRAINT chk_order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED')),
 CONSTRAINT chk_order_cancelled CHECK(
  (status='PENDING_PAYMENT' AND cancelled_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL)),
 INDEX idx_order_buyer_created(buyer_id,created_at DESC,id DESC),
 INDEX idx_order_seller_created(seller_id,created_at DESC,id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===== lesson70-order-lifecycle.sql =====
-- 第70课：扩展订单状态；单条ALTER由MySQL原子DDL提交。
USE campus_market;
ALTER TABLE market_orders
 DROP CHECK chk_order_status,
 DROP CHECK chk_order_cancelled,
 ADD COLUMN payment_due_at DATETIME(6) GENERATED ALWAYS AS (created_at + INTERVAL 15 MINUTE) STORED,
 ADD COLUMN paid_at DATETIME(6) NULL,
 ADD COLUMN closed_at DATETIME(6) NULL,
 ADD COLUMN payment_kind VARCHAR(16) NULL,
 MODIFY COLUMN active_product_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING_PAYMENT','PAID') THEN product_id ELSE NULL END) STORED,
 ADD CONSTRAINT chk_order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED','PAID','CLOSED_TIMEOUT')),
 ADD CONSTRAINT chk_order_lifecycle CHECK(
  (status='PENDING_PAYMENT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL) OR
  (status='PAID' AND cancelled_at IS NULL AND paid_at IS NOT NULL AND paid_at < payment_due_at AND closed_at IS NULL AND payment_kind IS NOT NULL AND payment_kind='SIMULATED') OR
  (status='CLOSED_TIMEOUT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NOT NULL AND closed_at >= payment_due_at AND payment_kind IS NULL)
 ),
 ADD INDEX idx_order_timeout(status,payment_due_at,id);


-- ===== lesson71-order-fulfillment.sql =====
-- 第71课：卖家交付与买家收货。单条ALTER保留既有订单字段。
USE campus_market;
ALTER TABLE market_orders
 DROP CHECK chk_order_status,
 DROP CHECK chk_order_lifecycle,
 ADD COLUMN delivered_at DATETIME(6) NULL,
 ADD COLUMN completed_at DATETIME(6) NULL,
 MODIFY COLUMN active_product_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING_PAYMENT','PAID','DELIVERED','COMPLETED') THEN product_id ELSE NULL END) STORED,
 ADD CONSTRAINT chk_order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED','PAID','CLOSED_TIMEOUT','DELIVERED','COMPLETED')),
 ADD CONSTRAINT chk_order_lifecycle CHECK(
  (status='PENDING_PAYMENT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL AND delivered_at IS NULL AND completed_at IS NULL) OR
  (status='CANCELLED' AND cancelled_at IS NOT NULL AND paid_at IS NULL AND closed_at IS NULL AND payment_kind IS NULL AND delivered_at IS NULL AND completed_at IS NULL) OR
  (status='CLOSED_TIMEOUT' AND cancelled_at IS NULL AND paid_at IS NULL AND closed_at IS NOT NULL AND closed_at >= payment_due_at AND payment_kind IS NULL AND delivered_at IS NULL AND completed_at IS NULL) OR
  (status IN ('PAID','DELIVERED','COMPLETED') AND cancelled_at IS NULL AND paid_at IS NOT NULL AND paid_at < payment_due_at AND closed_at IS NULL AND payment_kind IS NOT NULL AND payment_kind='SIMULATED' AND (
   (status='PAID' AND delivered_at IS NULL AND completed_at IS NULL) OR
   (status='DELIVERED' AND delivered_at IS NOT NULL AND delivered_at >= paid_at AND completed_at IS NULL) OR
   (status='COMPLETED' AND delivered_at IS NOT NULL AND delivered_at >= paid_at AND completed_at IS NOT NULL AND completed_at >= delivered_at)
  ))
 );


-- ===== lesson73-order-events.sql =====
-- 第73课：先停旧后端，再建表并补录历史快照，最后启动新后端。
USE campus_market;
CREATE TABLE order_events (
 order_id BIGINT NOT NULL,
 event_no INT NOT NULL,
 action VARCHAR(32) NOT NULL,
 from_status VARCHAR(24) NULL,
 to_status VARCHAR(24) NOT NULL,
 actor_role VARCHAR(8) NOT NULL,
 actor_id BIGINT NULL,
 actor_nickname VARCHAR(50) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_id,event_no),
 CONSTRAINT uq_order_event_action UNIQUE(order_id,action),
 CONSTRAINT fk_event_order FOREIGN KEY(order_id) REFERENCES market_orders(id) ON DELETE CASCADE,
 CONSTRAINT fk_event_actor FOREIGN KEY(actor_id) REFERENCES users(id),
 CONSTRAINT chk_event_no CHECK(event_no BETWEEN 1 AND 4),
 CONSTRAINT chk_event_time CHECK(recorded_at >= occurred_at),
 CONSTRAINT chk_event_actor CHECK(
  (actor_role IN ('BUYER','SELLER') AND actor_id IS NOT NULL) OR
  (actor_role='SYSTEM' AND actor_id IS NULL)
 ),
 CONSTRAINT chk_event_transition CHECK(
  (action='CREATED' AND event_no=1 AND from_status IS NULL AND to_status='PENDING_PAYMENT' AND actor_role='BUYER') OR
  (action='SIMULATED_PAYMENT' AND from_status IS NOT NULL AND from_status='PENDING_PAYMENT' AND to_status='PAID' AND actor_role='BUYER') OR
  (action='DELIVERED' AND from_status IS NOT NULL AND from_status='PAID' AND to_status='DELIVERED' AND actor_role='SELLER') OR
  (action='COMPLETED' AND from_status IS NOT NULL AND from_status='DELIVERED' AND to_status='COMPLETED' AND actor_role='BUYER') OR
  (action='CANCELLED' AND from_status IS NOT NULL AND from_status='PENDING_PAYMENT' AND to_status='CANCELLED' AND actor_role='BUYER') OR
  (action='TIMEOUT_CLOSED' AND from_status IS NOT NULL AND from_status='PENDING_PAYMENT' AND to_status='CLOSED_TIMEOUT' AND actor_role='SYSTEM') OR
  (action='HISTORY_SNAPSHOT' AND event_no=1 AND from_status IS NULL AND to_status IN ('PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT') AND actor_role='SYSTEM')
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 这是迁移时观察到的状态，不伪造历史操作人或历史事件发生时间。
INSERT INTO order_events(order_id,event_no,action,from_status,to_status,actor_role,actor_id,actor_nickname,occurred_at,recorded_at)
 SELECT id,1,'HISTORY_SNAPSHOT',NULL,status,'SYSTEM',NULL,'迁移补录',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
 FROM market_orders;


-- ===== lesson74-order-timeout-outbox.sql =====
-- 第74课：与下单同事务的待投递任务；不向RabbitMQ做数据库事务内双写。
USE campus_market;
CREATE TABLE order_timeout_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 published_at DATETIME(6) NULL,
 last_error VARCHAR(40) NULL,
 CONSTRAINT uq_timeout_outbox_order UNIQUE(order_id),
 CONSTRAINT fk_timeout_outbox_order FOREIGN KEY(order_id) REFERENCES market_orders(id) ON DELETE CASCADE,
 CONSTRAINT chk_timeout_attempts CHECK(attempts>=0),
 CONSTRAINT chk_timeout_lease CHECK((lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
 INDEX idx_timeout_outbox_due(published_at,next_attempt_at,lease_until,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 历史待付款订单仍由原扫描任务补偿；不伪造历史消息。


-- ===== lesson87-product-newest-indexes.sql =====
USE campus_market;

-- 第87课：最新商品列表复合索引；已有受管库由Flyway执行一次。
-- 不删除旧索引；LIKE '%keyword%'仍不能依靠普通B树进行标题定位。
ALTER TABLE products
    ADD INDEX idx_products_status_created_id (status, created_at DESC, id DESC),
    ADD INDEX idx_products_category_status_created_id (category_code, status, created_at DESC, id DESC),
    ALGORITHM=INPLACE,
    LOCK=NONE;


-- ===== lesson89-admin-roles.sql =====
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


-- ===== lesson90-product-moderation.sql =====
USE campus_market;

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


-- ===== lesson91-product-reports.sql =====
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


-- ===== lesson92-notifications.sql =====
CREATE TABLE notification_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 source_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recipient_id BIGINT NOT NULL,
 category VARCHAR(16) NOT NULL,
 resource_id BIGINT NOT NULL,
 title VARCHAR(80) NOT NULL,
 body VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL,
 delivered_at DATETIME(6) NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 last_error VARCHAR(40) NULL,
 UNIQUE KEY uq_notification_source(source_key,recipient_id),
 INDEX idx_notification_due(delivered_at,next_attempt_at,created_at,event_id),
 CONSTRAINT fk_notification_outbox_user FOREIGN KEY(recipient_id) REFERENCES users(id) ON DELETE CASCADE,
 CONSTRAINT chk_notification_target CHECK(category IN ('ORDER','REPORT','MODERATION') AND resource_id BETWEEN 1 AND 9007199254740991),
 CONSTRAINT chk_notification_content CHECK(CHAR_LENGTH(title) BETWEEN 1 AND 80 AND CHAR_LENGTH(body) BETWEEN 1 AND 500),
 CONSTRAINT chk_notification_attempts CHECK(attempts>=0),
 CONSTRAINT chk_notification_lease CHECK((lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE user_notifications (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recipient_id BIGINT NOT NULL,
 category VARCHAR(16) NOT NULL,
 resource_id BIGINT NOT NULL,
 title VARCHAR(80) NOT NULL,
 body VARCHAR(500) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 read_at DATETIME(6) NULL,
 UNIQUE KEY uq_notification_event(event_id),
 INDEX idx_notification_user(recipient_id,id DESC),
 INDEX idx_notification_unread(recipient_id,read_at,id DESC),
 CONSTRAINT fk_notification_source FOREIGN KEY(event_id) REFERENCES notification_outbox(event_id) ON DELETE CASCADE,
 CONSTRAINT fk_notification_user FOREIGN KEY(recipient_id) REFERENCES users(id) ON DELETE CASCADE,
 CONSTRAINT chk_user_notification_target CHECK(category IN ('ORDER','REPORT','MODERATION') AND resource_id BETWEEN 1 AND 9007199254740991),
 CONSTRAINT chk_user_notification_content CHECK(CHAR_LENGTH(title) BETWEEN 1 AND 80 AND CHAR_LENGTH(body) BETWEEN 1 AND 500),
 CONSTRAINT chk_user_notification_read CHECK(read_at IS NULL OR read_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ===== lesson101-order-reviews.sql =====
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


-- ===== lesson104-order-issues.sql =====
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


-- ===== lesson105-order-issue-resolutions.sql =====
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


-- ===== lesson128-product-details-and-profiles.sql =====
USE campus_market;
-- Additive migration: keep historical product/user values unchanged.
ALTER TABLE products
 ADD COLUMN campus_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN condition_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN handover_location VARCHAR(80) NOT NULL DEFAULT '',
 ADD CONSTRAINT chk_product_campus CHECK (campus_code IN ('UNSPECIFIED','EAST','WEST','OTHER')),
 ADD CONSTRAINT chk_product_condition CHECK (condition_code IN ('UNSPECIFIED','NEW','LIKE_NEW','GOOD','FAIR')),
 ADD INDEX idx_products_campus_newest (status,campus_code,created_at DESC,id DESC);
CREATE TABLE product_gallery (
 product_id BIGINT NOT NULL,
 position TINYINT NOT NULL,
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(product_id,position),
 UNIQUE KEY uk_product_gallery_image(product_id,image_id),
 CONSTRAINT fk_gallery_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE,
 CONSTRAINT fk_gallery_image FOREIGN KEY(image_id) REFERENCES product_images(id),
 CONSTRAINT chk_gallery_position CHECK(position BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO product_gallery(product_id,position,image_id)
 SELECT id,0,cover_image_id FROM products WHERE cover_image_id IS NOT NULL;
ALTER TABLE product_publications ADD COLUMN details_json JSON NULL;
ALTER TABLE product_draft_backups ADD COLUMN details_json JSON NULL;
ALTER TABLE users
 ADD COLUMN avatar_image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN bio VARCHAR(160) NOT NULL DEFAULT '',
 ADD COLUMN campus_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT fk_user_avatar FOREIGN KEY(avatar_image_id) REFERENCES product_images(id),
 ADD CONSTRAINT chk_user_campus CHECK(campus_code IN ('UNSPECIFIED','EAST','WEST','OTHER')),
 ADD CONSTRAINT chk_profile_version CHECK(profile_version BETWEEN 0 AND 9007199254740991);
CREATE TABLE account_recovery_codes (
 user_id BIGINT NOT NULL PRIMARY KEY,
 code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_recovery_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===== lesson129-order-refund-applications.sql =====
USE campus_market;
-- Refund application workflow only; no payment-provider execution or "credited" flag.
CREATE TABLE order_refunds (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount_in_cents BIGINT NOT NULL,
 reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 description VARCHAR(1000) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REQUESTED',
 version BIGINT NOT NULL DEFAULT 0,
 explanation VARCHAR(1000) NOT NULL DEFAULT '',
 payment_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 active_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('REQUESTED','APPROVED') THEN order_id ELSE NULL END) STORED,
 UNIQUE KEY uk_refund_request(buyer_id,request_id),
 UNIQUE KEY uk_refund_active_order(active_order_id),
 KEY idx_refund_buyer(buyer_id,id DESC),
 KEY idx_refund_seller(seller_id,id DESC),
 KEY idx_refund_order(order_id,id DESC),
 CONSTRAINT fk_refund_order FOREIGN KEY(order_id) REFERENCES market_orders(id),
 CONSTRAINT fk_refund_buyer FOREIGN KEY(buyer_id) REFERENCES users(id),
 CONSTRAINT fk_refund_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_refund_status CHECK(status IN ('REQUESTED','APPROVED','REJECTED','WITHDRAWN')),
 CONSTRAINT chk_refund_reason CHECK(reason IN ('NOT_AS_DESCRIBED','DAMAGED','HANDOVER','OTHER')),
 CONSTRAINT chk_refund_amount CHECK(amount_in_cents BETWEEN 1 AND 99999999),
 CONSTRAINT chk_refund_version CHECK(version BETWEEN 0 AND 9007199254740991),
 CONSTRAINT chk_refund_demo CHECK(payment_kind='SIMULATED'),
 CONSTRAINT chk_refund_participants CHECK(buyer_id<>seller_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE order_refund_events (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 refund_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 actor_role VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 explanation VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_refund_events(refund_id,id),
 CONSTRAINT fk_refund_event FOREIGN KEY(refund_id) REFERENCES order_refunds(id),
 CONSTRAINT fk_refund_actor FOREIGN KEY(actor_id) REFERENCES users(id),
 CONSTRAINT chk_refund_event_role CHECK(actor_role IN ('BUYER','SELLER')),
 CONSTRAINT chk_refund_event_action CHECK(action IN ('REQUESTED','APPROVED','REJECTED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===== lesson130-verified-email-binding.sql =====
USE campus_market;
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


-- ===== lesson131-content-and-abuse-governance.sql =====
USE campus_market;
CREATE TABLE account_write_restrictions (
 user_id BIGINT PRIMARY KEY,
 restricted_until DATETIME(6) NULL,
 reason VARCHAR(200) NOT NULL DEFAULT '',
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_restriction_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE account_restriction_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL,
 appeal_version BIGINT NULL,
 reason VARCHAR(200) NOT NULL,
 restricted_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_restriction_events(user_id,id),
 UNIQUE KEY uk_account_appeal(user_id,appeal_version),
 CONSTRAINT fk_restriction_event_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_restriction_event_actor FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE public_write_windows (
 user_id BIGINT NOT NULL,
 action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 bucket BIGINT NOT NULL,
 uses INT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,action,bucket),
 CONSTRAINT fk_write_window_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===== lesson132-image-storage-management.sql =====
USE campus_market;
CREATE TABLE image_storage_records (
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 byte_size BIGINT NOT NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 trashed_at DATETIME(6) NULL,
 purged_at DATETIME(6) NULL,
 physical_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 KEY idx_image_storage_trash(trashed_at,image_id),
 CONSTRAINT fk_storage_image FOREIGN KEY(image_id) REFERENCES product_images(id) ON DELETE CASCADE,
 CONSTRAINT chk_storage_size CHECK(byte_size BETWEEN 1 AND 10485760)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===== lesson133-payment-financial-evidence.sql =====
-- Additive financial evidence. DEMO and REAL books never mix; no payment channel is enabled here.
CREATE TABLE payment_attempts (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_order_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 book VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 amount_in_cents BIGINT NOT NULL,
 currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CREATED',
 confirmation_source VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 succeeded_at DATETIME(6) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 succeeded_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='SUCCEEDED' THEN order_id ELSE NULL END) STORED,
 UNIQUE KEY uq_payment_request(order_id,request_id),
 UNIQUE KEY uq_payment_merchant_order(merchant_order_no),
 UNIQUE KEY uq_payment_channel_trade(channel,merchant_scope,channel_trade_no),
 UNIQUE KEY uq_payment_success_order(succeeded_order_id),
 UNIQUE KEY uq_payment_order_pair(id,order_id),
 UNIQUE KEY uq_payment_book_pair(id,book),
 UNIQUE KEY uq_payment_channel_pair(id,channel,merchant_scope),
 CONSTRAINT fk_payment_order FOREIGN KEY(order_id) REFERENCES market_orders(id),
 CONSTRAINT chk_payment_channel CHECK(channel IN ('SIMULATED','WECHAT','ALIPAY')),
 CONSTRAINT chk_payment_book CHECK((channel='SIMULATED' AND book='DEMO') OR (channel IN ('WECHAT','ALIPAY') AND book='REAL')),
 CONSTRAINT chk_payment_amount CHECK(amount_in_cents BETWEEN 1 AND 99999999 AND currency='CNY'),
 CONSTRAINT chk_payment_state CHECK(status IN ('CREATED','SUCCEEDED','FAILED','CLOSED')),
 CONSTRAINT chk_payment_confirmation CHECK((status<>'SUCCEEDED' AND succeeded_at IS NULL AND confirmation_source IS NULL) OR (status='SUCCEEDED' AND succeeded_at IS NOT NULL AND ((channel='SIMULATED' AND confirmation_source IN ('LOCAL_SIMULATION','LEGACY_IMPORT')) OR (channel<>'SIMULATED' AND channel_trade_no IS NOT NULL AND confirmation_source IN ('VERIFIED_CALLBACK','VERIFIED_QUERY'))))),
 CONSTRAINT chk_payment_version CHECK(version BETWEEN 0 AND 9007199254740991),
 KEY idx_payment_order_created(order_id,created_at,id),
 KEY idx_payment_reconcile(channel,merchant_scope,status,succeeded_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE payment_callback_receipts (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 verified_at DATETIME(6) NOT NULL,
 processed_at DATETIME(6) NULL,
 UNIQUE KEY uq_payment_callback(channel,merchant_scope,event_id),
 CONSTRAINT fk_callback_payment FOREIGN KEY(payment_id,channel,merchant_scope) REFERENCES payment_attempts(id,channel,merchant_scope),
 CONSTRAINT chk_callback_channel CHECK(channel IN ('WECHAT','ALIPAY')),
 CONSTRAINT chk_callback_time CHECK(processed_at IS NULL OR processed_at>=verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE order_refunds ADD UNIQUE KEY uq_refund_order_pair(id,order_id);
CREATE TABLE payment_refund_attempts (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 application_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount_in_cents BIGINT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CREATED',
 created_source VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPLICATION_APPROVAL',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 succeeded_at DATETIME(6) NULL,
 UNIQUE KEY uq_refund_application(application_id),
 UNIQUE KEY uq_refund_merchant(merchant_refund_no),
 UNIQUE KEY uq_refund_channel(channel,merchant_scope,channel_refund_no),
 UNIQUE KEY uq_refund_payment_pair(id,payment_id),
 CONSTRAINT fk_attempt_application FOREIGN KEY(application_id,order_id) REFERENCES order_refunds(id,order_id),
 CONSTRAINT fk_refund_channel_pair FOREIGN KEY(payment_id,channel,merchant_scope) REFERENCES payment_attempts(id,channel,merchant_scope),
 CONSTRAINT fk_refund_payment_order FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempts(id,order_id),
 CONSTRAINT chk_refund_attempt_amount CHECK(amount_in_cents BETWEEN 1 AND 99999999),
 CONSTRAINT chk_refund_attempt_origin CHECK(created_source IN ('APPLICATION_APPROVAL','LEGACY_IMPORT')),
 CONSTRAINT chk_refund_attempt_state CHECK(status IN ('CREATED','PROCESSING','SUCCEEDED','FAILED','CLOSED')),
 CONSTRAINT chk_refund_attempt_success CHECK((status<>'SUCCEEDED' AND succeeded_at IS NULL) OR (status='SUCCEEDED' AND succeeded_at IS NOT NULL AND (channel='SIMULATED' OR channel_refund_no IS NOT NULL))),
 KEY idx_refund_attempt_payment(payment_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Each immutable journal is one balanced debit/credit pair, in cents. No editable wallet balance.
CREATE TABLE finance_journals (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 refund_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 book VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount_in_cents BIGINT NOT NULL,
 currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
 debit_account VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 credit_account VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 payment_posting_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN kind='PAYMENT' THEN payment_id ELSE NULL END) STORED,
 UNIQUE KEY uq_journal_payment(payment_posting_id),
 UNIQUE KEY uq_journal_refund(refund_attempt_id),
 CONSTRAINT fk_journal_payment_book FOREIGN KEY(payment_id,book) REFERENCES payment_attempts(id,book),
 CONSTRAINT fk_journal_refund_payment FOREIGN KEY(refund_attempt_id,payment_id) REFERENCES payment_refund_attempts(id,payment_id),
 CONSTRAINT chk_journal_amount CHECK(amount_in_cents BETWEEN 1 AND 99999999 AND currency='CNY'),
 CONSTRAINT chk_journal_accounts CHECK((kind='PAYMENT' AND refund_attempt_id IS NULL AND debit_account='PROVIDER_CLEARING' AND credit_account='SELLER_PENDING') OR (kind='REFUND' AND refund_attempt_id IS NOT NULL AND debit_account='SELLER_PENDING' AND credit_account='PROVIDER_CLEARING')),
 KEY idx_journal_book_time(book,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE finance_reconciliation_runs (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 bill_date DATE NOT NULL,
 file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVIEW_REQUIRED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_reconcile_file(channel,merchant_scope,bill_date,file_sha256),
 CONSTRAINT chk_reconcile_status CHECK(status IN ('MATCHED','REVIEW_REQUIRED')),
 CONSTRAINT chk_reconcile_channel CHECK(channel IN ('SIMULATED','WECHAT','ALIPAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE finance_reconciliation_lines (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 line_no INT NOT NULL,
 merchant_reference VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 local_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 provider_amount_in_cents BIGINT NULL,
 local_amount_in_cents BIGINT NULL,
 outcome VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(run_id,line_no),
 UNIQUE KEY uq_reconcile_line(run_id,kind,merchant_reference),
 CONSTRAINT fk_reconcile_run FOREIGN KEY(run_id) REFERENCES finance_reconciliation_runs(id),
 CONSTRAINT chk_reconcile_kind CHECK(kind IN ('PAYMENT','REFUND')),
 CONSTRAINT chk_reconcile_outcome CHECK(outcome IN ('MATCHED','AMOUNT_MISMATCH','TRANSACTION_MISMATCH','MISSING_LOCAL','MISSING_PROVIDER')),
 CONSTRAINT chk_reconcile_amount CHECK((provider_amount_in_cents IS NULL OR provider_amount_in_cents BETWEEN 1 AND 99999999) AND (local_amount_in_cents IS NULL OR local_amount_in_cents BETWEEN 1 AND 99999999))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE seller_settlements (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 seller_id BIGINT NOT NULL,
 channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_settlement_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 amount_in_cents BIGINT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ON_HOLD',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 settled_at DATETIME(6) NULL,
 UNIQUE KEY uq_settlement_request(seller_id,request_id),
 UNIQUE KEY uq_provider_settlement(channel,merchant_scope,provider_settlement_no),
 CONSTRAINT fk_settlement_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_settlement_channel CHECK(channel IN ('WECHAT','ALIPAY')),
 CONSTRAINT chk_settlement_amount CHECK(amount_in_cents BETWEEN 1 AND 9007199254740991),
 CONSTRAINT chk_settlement_state CHECK(status IN ('ON_HOLD','PROCESSING','SUCCEEDED','FAILED')),
 CONSTRAINT chk_settlement_success CHECK((status<>'SUCCEEDED' AND settled_at IS NULL) OR (status='SUCCEEDED' AND settled_at IS NOT NULL AND provider_settlement_no IS NOT NULL)),
 KEY idx_settlement_seller(seller_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE seller_settlement_items (
 settlement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount_in_cents BIGINT NOT NULL,
 PRIMARY KEY(settlement_id,payment_id),
 UNIQUE KEY uq_payment_settlement(payment_id),
 CONSTRAINT fk_settlement_item FOREIGN KEY(settlement_id) REFERENCES seller_settlements(id),
 CONSTRAINT fk_settlement_payment FOREIGN KEY(payment_id) REFERENCES payment_attempts(id),
 CONSTRAINT chk_settlement_item_amount CHECK(amount_in_cents BETWEEN 1 AND 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Only existing, explicitly SIMULATED receipts are imported; channel IDs are never fabricated.
INSERT INTO payment_attempts(id,order_id,request_id,merchant_order_no,channel,merchant_scope,book,amount_in_cents,status,confirmation_source,created_at,succeeded_at)
 SELECT UUID(),id,request_id,CONCAT('LEGACY',id),'SIMULATED','DEMO','DEMO',price_in_cents_snapshot,'SUCCEEDED','LEGACY_IMPORT',created_at,paid_at FROM market_orders WHERE payment_kind='SIMULATED' AND paid_at IS NOT NULL;
INSERT INTO finance_journals(id,payment_id,book,kind,amount_in_cents,debit_account,credit_account,created_at)
 SELECT UUID(),id,'DEMO','PAYMENT',amount_in_cents,'PROVIDER_CLEARING','SELLER_PENDING',succeeded_at FROM payment_attempts WHERE confirmation_source='LEGACY_IMPORT';
INSERT INTO payment_refund_attempts(id,application_id,order_id,payment_id,merchant_refund_no,channel,merchant_scope,amount_in_cents,created_source,created_at)
 SELECT UUID(),r.id,r.order_id,p.id,CONCAT('LEGACYREFUND',r.id),'SIMULATED','DEMO',r.amount_in_cents,'LEGACY_IMPORT',r.updated_at FROM order_refunds r JOIN payment_attempts p ON p.order_id=r.order_id AND p.status='SUCCEEDED' AND p.channel='SIMULATED' WHERE r.status='APPROVED' AND r.payment_kind='SIMULATED';


-- ===== lesson134-external-identity-and-media-indexes.sql =====
-- Server-verified OAuth identity linkage only. No client-supplied openid binding endpoint.
CREATE TABLE user_external_identities (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 provider VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 app_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 subject_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 verified_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_external_identity(provider,app_id,subject_id),
 UNIQUE KEY uq_user_external_app(user_id,provider,app_id),
 CONSTRAINT fk_external_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT chk_external_provider CHECK(provider IN ('WECHAT_MINIPROGRAM','WECHAT_OPEN','ALIPAY')),
 CONSTRAINT chk_external_nonempty CHECK(CHAR_LENGTH(app_id)>0 AND CHAR_LENGTH(subject_id)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE product_gallery ADD INDEX idx_gallery_image(image_id,product_id);
ALTER TABLE users ADD INDEX idx_user_campus(campus_code,id);


-- ===== lesson135-bounded-data-retention.sql =====
-- Retention is explicit and bounded; financial records and audit history are never deleted by this policy.
CREATE TABLE database_maintenance_runs (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 policy_version INT NOT NULL,
 cutoff_at DATETIME(6) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 summary_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT chk_maintenance_status CHECK(status IN ('COMPLETED','NO_CHANGES'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE login_sessions ADD INDEX idx_session_retention(revoked_at,token_hash);
ALTER TABLE account_email_challenges ADD INDEX idx_email_retention(expires_at,id);
ALTER TABLE public_write_windows ADD INDEX idx_write_window_retention(bucket,user_id);
ALTER TABLE notification_outbox ADD INDEX idx_notification_retention(delivered_at,event_id);
ALTER TABLE order_timeout_outbox ADD INDEX idx_timeout_retention(published_at,event_id);


-- ===== lesson136-order-query-indexes.sql =====
-- Composite indexes for exact participant + status lists and their pagination order.
ALTER TABLE market_orders ADD INDEX idx_order_buyer_status_created(buyer_id,status,created_at DESC,id DESC), ADD INDEX idx_order_seller_status_created(seller_id,status,created_at DESC,id DESC);
