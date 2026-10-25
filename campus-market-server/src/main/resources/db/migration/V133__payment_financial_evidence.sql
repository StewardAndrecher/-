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
