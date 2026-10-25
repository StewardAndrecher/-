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
